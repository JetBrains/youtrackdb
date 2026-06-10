# YTDB-382 — Adversarial pass 7: concurrency (2026-06-10)

Seventh adversarial pass, single lens: races, lock ordering, memory visibility,
atomicity seams, thread-binding. Targeted the seams the pass-6 resolutions
created: the F64 four-lock order, the F65 three-tier proxy routing, F66's
commit-time re-derivation, F68's re-parse promotion, F69's post-release
listener dispatch, F71's owner-tracked mutex with the `releaseStranded` reap
API (the explicitly open Phase-1 checkpoint), and F74's corrected pin
envelope. All claims verified against the live tree; reference-accuracy
questions (callers of `resetTsMin`, `startStorageTx`,
`IndexManagerEmbedded.acquireExclusiveLock`, `rollbackInternal`) resolved
through PSI find-usages.

Verdict: 0 BLOCKER, 3 MAJOR, 1 minor. The four-lock order itself held against
every inversion hunt — the caller-set audits all conformed. The common root
this round: the pass-6 resolutions are sound at the lock-architecture level
but lean on auxiliary machinery nobody audited for **thread ownership** — the
per-thread `TsMinHolder`, the `OperationsFreezer`'s ThreadLocal depth and park
gate, and the in-memory key sources the F66 re-derivation silently assumes.
F74's corrected premises themselves all re-verified clean (see the dry list).

---

## C16: The reap path cannot release a stranded schema tx's `tsMin` pin — the holder is thread-owned, the only release API throws cross-thread, and the existing cross-thread close deliberately leaks the pin [MAJOR]

D7's reap bullet (F71/F74 fold) says the reap path "runs the session's full tx
rollback (ending the atomic operation — releasing the F74 pins: the `tsMin`
snapshot floor, plus the WAL segment pin only if stranded mid-commit-window)
and then `releaseStranded(session)`". F71 marked one half of this as a Phase-1
checkpoint. The verification splits the checkpoint in two, and the half D7's
text relies on for the routine case fails.

**The half that passes.** This tree's `AtomicOperationsManager` has no
ThreadLocal current-operation: `endAtomicOperation(operation, error)` takes
the operation explicitly (`AtomicOperationsManager:258`), and the table
transitions are keyed by operation timestamp
(`AtomicOperationsTable.rollbackOperation:390`). A mid-body stranded tx never
contacts the table at all — `rollbackInternal` (`FrontendTransactionImpl:356`)
routes to `close()` (`:400`), which only calls `atomicOperation.deactivate()`
(`:953`) — so the operation-object half of the checkpoint is satisfiable
cross-thread.

**The half that fails: the `tsMin` floor.** The pin lives in a per-thread
`TsMinHolder` — `tsMinThreadLocal` (`AbstractStorage:367`), registered in the
weak-keyed `tsMins` set (`:375`). `activeTxCount` is a plain int documented
"Only accessed by the owning thread" (`TsMinHolder:84`). The only release
point is `resetTsMin()` (`AbstractStorage:4679`), and it is hard-bound to the
calling thread: it reads `tsMinThreadLocal.get()` (`:4680`) and throws
`IllegalStateException` when that holder's count is zero (`:4682`–`:4686`).
PSI: the only production tx-end caller is `FrontendTransactionImpl.close()`
(`:955`); the `cleanupSnapshotIndex` hit at `AbstractStorage:6418` is a
Javadoc link, not a call. And `close()` already encodes the cross-thread
answer: it calls `resetTsMin()` only `if (storageTxThreadId ==
Thread.currentThread().threadId())` (`:954`), with the comment "in that case
tsMin belongs to the originating thread's TsMinHolder and must not be reset"
(`:121`–`:123`). The existing cross-thread close path — the
`DatabaseSessionEmbeddedPooled.realClose` pattern the reaper would naturally
reuse (`:58`–`:61`, `activateOnCurrentThread()` + `super.close()`) —
**deliberately leaks the pin**, because the alternative corrupts the closing
thread's own holder.

**Interleaving (the routine case F71 names).** A client opens a schema tx on
pooled worker thread T, mutates schema, vanishes between operations. T
returned to the pool and serves other sessions; T's holder carries the
stranded `+1` and the stranded snapshot floor. The reaper thread runs the full
tx rollback per D7: `rollbackInternal` → `close()` → skip `resetTsMin`
(different thread). Outcome: T's `activeTxCount` never returns to zero — its
later begin/end pairs oscillate around the stranded `+1` — so `tsMin` is
never reset and keeps ratcheting down (`Math.min`, `:4653`). The global
low-water-mark (`computeGlobalLowWaterMark:6954`) is capped forever;
`cleanupSnapshotIndex` (`:6451`) and the records-GC task can evict nothing
newer; the snapshot/visibility indexes grow without bound. The weak-key
eviction (`:370`–`:373`) only fires when the owner **thread** dies — a pooled
thread does not. No error is ever raised; the only symptom is the YTDB-550
monitor's warnings — the design's reap story claims to fix exactly this
invisible-pin shape and does not. The other arm is worse: a reaper that calls
`resetTsMin()` directly either throws (count zero on its own holder) or, if
the reaper thread happens to have its own active tx, decrements the **wrong**
holder and releases a snapshot floor that live tx still needs — premature
snapshot-entry eviction, an SI visibility violation on an unrelated
transaction.

**Mid-commit-window strand (the rarer half).** Two further thread-owned
pieces make a cross-thread `endAtomicOperation` impossible there: the
freezer's per-thread depth — `endOperation` throws "Invalid operation depth"
from any thread that did not call `startOperation`
(`OperationsFreezer:59`–`:62`, called from `AtomicOperationsManager:442`) —
and the per-component `ReentrantReadWriteLock`s, whose release is silently
**skipped** for a non-owner (`releaseLocks` gates on `isExclusiveOwner`,
`AtomicOperationsManager:480`; `SharedResourceAbstract:60`–`:61` is
`isWriteLockedByCurrentThread()`), leaving the locked components wedged. This
half requires owner-thread death inside `AbstractStorage.commit`, which
strands `stateLock` itself and wedges the storage today regardless — so the
load-bearing defect is the mid-body `tsMin` half above.

**Affected:** D7 (reap bullet), F71 (the Phase-1 checkpoint — resolved in the
negative), F74 (the "releasing whatever pin exists at reap time"
parenthetical), F61, F38, D5.

**Resolution direction.** Make the pin release operation-scoped instead of
thread-scoped: capture the `TsMinHolder` reference on the
`FrontendTransactionImpl`/`AtomicOperation` at `startStorageTx` (the tx
already captures `storageTxThreadId`, `:186`), make the release path operate
on the captured holder with a cross-thread-safe decrement (`activeTxCount`
becomes atomic; `tsMin` reset stays the existing volatile/opaque write), and
have both `close()` arms and `releaseStranded` release by captured holder. The
mid-commit-window strand should be explicitly scoped out of the reap story
(owner-thread death inside the commit poisons the storage today; restart is
the recovery), so D7's pin-release sentence applies to mid-body strands only.

## C17: F66's re-derivation has no key source for the delete leg — the values are unloaded at `deleteRecord`, tx reads refuse tx-deleted RIDs, and an in-window committed re-read self-deadlocks [MAJOR]

The F66 fold (D12's completeness invariant) prescribes: for each tx-created
index, re-derive entries from the tx's complete record-operation set —
"deletes of committed rows contribute removes (composing with population's
puts inside `commitIndexes` — put then remove nets to absent)". A remove needs
the deleted row's **key**, i.e. its field values. Every source fails:

- **In-memory: gone.** `RecordOperation` retains only the live record
  reference (`RecordOperation:36`), and the record is unloaded right after the
  eager flush — that is the flush's stated reason
  (`FrontendTransactionImpl:482`: "execute it here because after this
  operation record will be unloaded"). F66's own rejection rationale concedes
  this: retaining deleted records' values until commit "changes the data
  path's lifecycle" — the design rejected the only in-memory source and then
  specified a mechanism that needs it.
- **Tx-routed read: refused.** `loadRecord` throws `RecordNotFoundException`
  for a tx-deleted RID (`FrontendTransactionImpl:455`–`:456`); `exists`
  returns false (`:436`–`:441`); `executeReadRecord` checks `isDeletedInTx`
  before storage (F23). The committed version is unreadable through the
  session.
- **In-window storage re-read: deadlocks.** The composition point F66 names is
  `commitIndexes`, inside the D19 `stateLock.writeLock()` window. A committed
  read through the session path re-enters `stateLock.readLock()`
  (`readRecord:2031`/`:4584`, F54's established fact) under the held
  non-reentrant write lock — the exact F54 self-deadlock, now on the
  re-derivation leg rather than the population scan.

**Consequence.** An implementer who cannot produce the remove key ships the
re-derivation without the delete leg, and C10's interleaving 1 returns intact
despite F66 reading as resolved: delete `r`, `createIndex`, commit →
population puts `r`'s committed key, no remove nets it out → durable dangling
entry; for a UNIQUE index, phantom `RecordDuplicatedException` on future
inserts of that value. The delete-bad-rows-then-add-UNIQUE-index migration is
the headline YTDB-382 workload.

**Affected:** F66, D12 (completeness invariant), F54, D3.

**Resolution direction.** Remove the need for committed keys instead of
finding them: make the population scan **tx-aware** — skip every RID present
in the tx's record-operation set — and have the re-derivation contribute
final-state entries only (inserts and updates put their final values, both
available in memory; updates' old committed keys never enter the engine
because population skipped the row). Deletes then need no remove at all: the
row is simply never put. This also simplifies the F66 invariant's wording
("population covers committed rows the tx did not touch; re-derivation covers
exactly the tx-touched rows"). The alternative — a tx-bypassing committed-read
primitive in the session-layer phase — re-creates the cross-layer entanglement
F54/F66 removed, and should be rejected on that ground. D12's regression-test
pair (dangling entry / missing entry) stays as specified.

## C18: DDL against a frozen storage parks the schema commit inside the `OperationsFreezer` while holding all four locks — the freeze window loses every read [MAJOR]

The D7/D19 ordering proof covers the four locks, but the commit path contains
a fifth synchronization object the spine never names: the
`OperationsFreezer`. `startTxCommit` (`AbstractStorage:2293` → `:4710`) calls
`startToApplyOperations`, whose first statement is
`writeOperationsFreezer.startOperation()` (`AtomicOperationsManager:107`) —
and with a freeze active that call parks on `LockSupport.park`
(`OperationsFreezer:30`–`:47`; the no-exception freeze variant parks rather
than throws).

**Interleaving.** Operator freezes the storage for a filesystem snapshot:
`freeze(db, false)` takes `stateLock.readLock()` (`:3889`), engages the
freezer (`:3905`), syncs, releases `stateLock` (`:3930`) and **returns with
the freezer engaged** — the steady frozen state holds no locks; `release(db)`
(`:3942`) later unfreezes, also lock-free. A client now issues DDL. The
schema-carrying commit takes the D7 mutex, `SchemaShared.lock`,
`IndexManagerEmbedded.lock`, and `stateLock.writeLock()` per D19 — nothing
blocks, the freeze holds no locks — then reaches `startTxCommit` and parks on
the frozen gate **holding all four**. From that moment every
`stateLock.readLock()` acquisition parks behind the held write lock
(writer-preference `ScalableRWLock`): every record read, every query, every
lock-based schema and index-metadata read. The same end state is reachable
from the other side — a DDL commit that acquires the four locks while
`freezeOperations` is draining (`:72`–`:83`) parks the freeze briefly, then
parks itself once the freeze engages.

**The delta is design-created.** Today the same interleaving parks a data
commit holding only `stateLock.readLock()` (`:2285` then `:2293`): other
readers are read-read compatible, and a frozen database keeps serving reads —
the freeze feature's operating contract during backup. Under D19 a single
parked DDL statement converts the freeze window into a total outage for its
whole duration (operator-scale: minutes), recoverable only when `release(db)`
unfreezes and the commit drains. No deadlock — `release` takes no locks — but
D7's "does not block data commits or snapshot-based schema reads" premise
fails wholesale for the window, and nothing in the spine warns the operator
that freeze plus DDL behaves this way. A second, smaller envelope note: the
backup path's segment freeze (`DiskStorage:1248`) and any `freezeOperations`
caller busy-spin on `Thread.yield()` (`OperationsFreezer:81`–`:83`) until
`operationsCount` drains, so an F48-scale commit window now holds those
callers in a multi-minute spin — pre-existing shape, stretched by F48.

**Affected:** D19, D7 (ordering proof names four locks; the freezer is a
fifth gate), F48, D12.

**Resolution direction.** Gate schema-carrying commits on the freezer
**before** lock acquisition. The naive reorder (enter the freezer first, then
take the locks) deadlocks against `freeze()`'s own order — `freeze` holds
`stateLock.read` while draining `operationsCount` (`:3889` → `:3905`), so a
commit inside the freezer waiting for `stateLock.write` and a freeze waiting
for the drain would cycle. The safe shape is check-and-back-off at commit
entry: after acquiring the four locks, probe `freezeRequests`; if a freeze is
pending or active, release all four, park on the freezer gate, and retry the
acquisition. Alternatively, declare DDL-during-freeze rejected (route schema
commits through the `throwException` freeze semantics so they fail loudly
instead of parking). Either way, name the freezer in D7/D19's ordering
discussion so the next reviewer does not rediscover it.

## C19: `releaseStranded` plus a stalled-but-alive owner — an "assertion"-guarded `Semaphore` release breaks D5's mutual exclusion when asserts are compiled out [minor]

F71's fold words the owner bookkeeping as "normal release **asserts** owner ==
current thread". `Semaphore.release()` has no ownership check of its own — it
unconditionally increments the permit count from any thread. If the
bookkeeping is a Java `assert` (disabled in production), the following
interleaving breaks D5's single-writer guarantee outright:

1. Session S1's schema tx runs a long server-side statement on worker thread
   T1; the client connection drops. The reaper declares S1 stranded "between
   operations", runs the rollback, and calls `releaseStranded(S1)` — but T1 is
   stalled, not dead (GC pause, slow I/O, long scan). Nothing excludes this:
   `activateOnCurrentThread` only sets the calling thread's flag
   (`DatabaseSessionEmbedded:3332`–`:3333`, `activeSession` ThreadLocal
   `:250`) and never deactivates the owner, so reaper and owner are both
   "active" on S1 concurrently — the same dual-activation the
   `realClose` path already permits.
2. Session S2 acquires the mutex and starts its schema tx.
3. T1 wakes, its statement fails or completes, and S1's outermost
   commit/rollback `finally` runs the **normal** release path. With asserts
   off, the owner comparison evaluates nothing and `release()` runs: permits
   go 0 → 1 while S2 still believes it holds the mutex.
4. Session S3 acquires; S2 and S3 are now concurrent schema writers — D5's
   pessimistic single-writer model is gone, and both commits will fight over
   the shared promotion under the same four locks.

The fix is one wording pin plus one semantic: the normal release must be a
hard compare-owner-and-clear (CAS on an owner token keyed by session and
acquisition epoch, no-op or throw when the token does not match), never a
bare `assert` plus unconditional `release()`. The same token check makes the
zombie's step-3 release a detected no-op. A residual caveat belongs in D7's
reap bullet regardless: between reap and the zombie's wake-up, two threads
operate on one session's tx state with no synchronization (the
`assertOnOwningThread` guard explicitly exempts `close()`/`rollbackInternal`,
`FrontendTransactionImpl:130`–`:131`), so the reap path must tolerate the
owner racing it — today that tolerance is unspecified.

**Affected:** D7, F71, F38, D5.

---

## Attacks run that produced no new finding

- **F64 four-lock acyclicity — index-lock holders acquiring the schema
  lock.** PSI caller set of `IndexManagerEmbedded.acquireExclusiveLock` (10
  sites: `load:70`, `reload:89`, `addCollectionToIndex:115`,
  `removeCollectionFromIndex:145`, `create:161`, `addIndexInternal:230`,
  `createIndex:325`, `dropIndex:463`, `recreateIndexes:491`,
  `removeClassPropertyIndex:550`). No locked region touches
  `SchemaShared.lock`: the manager `load` chain is pure map parsing
  (`IndexAbstract.loadMetadataFromMap:120`–`:175`) plus tx-routed entity reads
  (`stateLock.read` — index → state, conforming); `createIndex`'s snapshot
  read (`:405`) runs **before** the lock (`:325`); `releaseExclusiveLock`'s
  `forceSnapshot` takes only `snapshotLock` (`SchemaShared:218`–`:229`).
  F64's "no path takes the index lock before the schema lock" holds.
- **`snapshotLock` vs `stateLock` inversion under the trailing
  `forceSnapshot`.** The commit holds all four locks when it takes
  `snapshotLock`; the reverse path (`makeSnapshot`, `SchemaShared:194`–`:216`)
  builds `ImmutableSchema` entirely from in-memory schema state and lock-free
  index-manager reads (`ImmutableSchema:62`–`:118`; `getIndexes` is a CHM
  values view, `IndexManagerAbstract:180`) — no `stateLock` under
  `snapshotLock`, so it stays a leaf and no cycle exists.
- **Tier-2 captured-delegate reads torn by F68's in-place re-parse.** Every
  `SchemaClassImpl` getter takes `acquireSchemaReadLock` (e.g. `getName:222`,
  `properties():336`), which the promotion's held schema write lock excludes;
  snapshot readers read `SchemaImmutableClass` copies. No lock-free reader of
  the shared mutable class state exists on the proxy surface.
- **F68 promotion re-parse self-deadlocking on the storage read path (the
  F54 shape relocated).** Fails: every changed per-class record is in the
  tx's record-operation set at promotion time, so the re-parse sources
  in-memory entities/bytes; no storage read is needed, and a `session.load`
  of those RIDs would be served from the tx record cache anyway. Worth a
  one-line pin in the plan, not a finding.
- **F74's corrected premises.** All re-verified: `startStorageTx` only
  snapshots the operations table and updates the thread's `TsMinHolder`
  (`AbstractStorage:4629`–`:4660`, `AtomicOperationsManager:81`–`:104` — no
  table write, no WAL contact); table registration happens only in
  `startToApplyOperations` (`:118`–`:125`), whose sole frontend-tx caller is
  `startTxCommit` (`:2293`, PSI: `startStorageTx`'s only production caller is
  `beginInternal:185`); read-only txs never reach `internalCommit`
  (`isWriteTransaction` gate, `FrontendTransactionImpl:669`–`:670`) and their
  rollback is `close()` with no table contact. The corrected envelope
  (commit-window WAL pin, body pins heap only) stands.
- **Reload conformance under the four-lock order.**
  `IndexManagerEmbedded.reload` orders indexLock.write → `stateLock.read`
  (`:89` → `loadEntity:91`), with its wrapping tx read-only (no commit-time
  `stateLock` acquisition); `SchemaShared.reload` orders schema.write →
  state.read. Both conform; the commit's schema → index → state entry
  sequence cannot cycle with them.
- **Freeze-vs-commit deadlock (as opposed to C18's availability loss).**
  `freeze()` orders `stateLock.read` → freezer; the commit orders
  `stateLock.write` → freezer — same direction, no cycle; `release(db)`
  takes no locks, so the parked commit always drains once the operator
  releases. C18 is an outage, not a deadlock.
- **Non-reentrant D7 `Semaphore` self-deadlock via listener-driven DDL.**
  `MetadataUpdateListener` consumers (plan caches, push dispatch) perform no
  DDL, and the engage is owner-checked idempotent per F44/F71's bookkeeping,
  so no same-session re-acquire path was found. The non-reentrancy is worth
  remembering if listener registration ever opens to user code.
- **Zombie session mutating its tx-local view after reap.** Isolated by
  construction: the tx-local `SchemaShared` and overlay are discarded state,
  the shared schema was never touched mid-tx (D4), and the zombie's eventual
  commit fails `checkTransactionValid` on the rolled-back status. The
  unsynchronized dual-thread access to session internals is recorded inside
  C19 rather than as a separate finding.
- **`SharedContext.lock` and genesis edges.** Premises unchanged since the
  pass-6 dry list (F72 keeps genesis single-threaded; reload's
  SharedContext → schema → index → state chain conforms); not re-tilled.
