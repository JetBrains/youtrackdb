# Track 7 Design Drafts — Concurrency hardening (mutex handshake + freezer gate)

**Status:** design-phase artifact for the mandatory user design review. Not implementation.
**Grounded at HEAD `e2605c8ba3`.** All line numbers cite HEAD. Verified-by-reading claims are
marked **[verified]**; anything timing-dependent that could not be proven without running code is
marked **[inferred]**. This document reproduces the two-draft design report in full: Draft A =
metadata-write-mutex permit handshake; Draft B = freezer gate.

---

## 0. Plan corrections the user must rule on FIRST

These two corrections overturn plan-era assumptions and reshape the Step 3–4 scope. Rule on them
before decomposition.

1. **FM-A1 (pool close of an idle owner) is ALREADY healed at HEAD** by Track 3's seed-failure
   catch arm (`DatabaseSessionEmbedded.ensureTxSchemaState:3510-3519`) plus the release living
   inside the teardown the pool thread itself runs (`FrontendTransactionImpl.close()` finally
   `:1029-1040`). The plan-era wedge premise ("`checkOpenness` refuses the owner's teardown so the
   permit strands", design.md:808-815) no longer describes the base case. **Real Step-3 work is the
   race closures + diagnostics, not a from-scratch heal:** FM-A2 (rollback-throw wedge), FM-A3
   (Dekker window), FM-A4a (pool `clear()` under a live commit — a **newly found corrupt-state
   hazard** the frozen design never addressed), and FM-A4b (double-release → silent single-writer
   break).

2. **The operator freeze CANNOT engage mid-window at HEAD.** `freeze()` must take
   `stateLock.readLock` (`AbstractStorage:5510`) and therefore blocks behind an in-flight schema
   commit's `stateLock.writeLock`. The only residual gap is the **probe-to-entry window** (the
   commit holds SchemaShared.lock/IM lock but has not yet requested `stateLock.writeLock`, where
   freeze's readLock is grantable). **The registration-site count is 4, not the plan's 5** — no
   index-rebuild freeze exists at HEAD.

### Already-ruled scope decisions (carried forward, on the record)

- **CS2 (`endTxCommit` undo-bypass)** is **ABSORBED into Step 1**. (An `endTxCommit` failure after
  the reconcile phases propagates uncaught in the no-error branch of the commit finally, so neither
  the index undo/restore arms nor `undoReconciledCollections` run; pre-existing and shared with
  Track 4. It is handled in Step 1, not deferred to a later step.)
- **The top-level-DDL mutex gap is OUT OF SCOPE for Track 7.** The legacy non-transactional
  create/drop/rename DDL paths bypass the metadata-write mutex (documented at
  `IndexManagerEmbedded.java:774-781` and the `reassociateClassIndexesOnRename` javadoc
  `:1645-1651`); the legacy path is removed in an upcoming PR, so Track 7 does not widen the mutex
  to cover it.

---

# DRAFT A — Metadata-write-mutex permit handshake

## A.0 The machinery as it exists at HEAD (baseline facts)

| Piece | Where | Behavior |
|---|---|---|
| Permit | `MetadataWriteMutex.java:69` | `Semaphore(1)`; holder is a **plain volatile** `Holder(session, thread)` record (`:66`, `:78`) — no ordinal, no CAS |
| Engage | `MetadataWriteMutex.engage` `:89-104` | same-thread/different-session reject → `acquireUninterruptibly()` → holder write. **Window: permit acquired, holder+marker unwritten** |
| Engage wiring | `DatabaseSessionEmbedded.ensureTxSchemaState:3477-3521` | engage → `try { seed } catch { releaseMetadataWriteMutexForTx(); throw }` — the **seed-failure release arm already exists** |
| Marker | `DatabaseSessionEmbedded:295` | `volatile boolean metadataMutexEngaged`, set **after** engage returns (`:3599`) |
| Release gate | `releaseMetadataWriteMutexForTx:3617-3623` | non-atomic check-then-clear of the volatile marker, then `releaseFor(session)` |
| Release | `MetadataWriteMutex.releaseFor:117-124` | plain read → session compare → `holder = null` → `permit.release()`. **No CAS** — correct only under single-releaser |
| Sole release site | `FrontendTransactionImpl.close()` finally `:1029-1040` | reached from commit success (`doCommit` → `close()`, `:757`), rollback (`rollbackInternal` → `close()`, `:446`), and any path that closes the tx |
| Session activation | `activateOnCurrentThread:4575-4577` | sets a per-session `ThreadLocal<Boolean>` — **any number of threads may have the same session "active" simultaneously; there is no mutual exclusion** [verified] |
| Pool close | `DatabasePoolImpl.close:129-137` → `getAllResources()` (`ResourcePool.java:191-193` includes `resourcesOut`) | **checked-out sessions ARE realClosed** [verified] |
| realClose | `DatabaseSessionEmbeddedPooled.realClose:58-61` | `activateOnCurrentThread()` + `super.close()` → `internalClose(false)` (`DatabaseSessionEmbedded:3840-3842`) |
| internalClose | `:3264-3297` | non-atomic `status != OPEN` one-shot guard; `rollback()` wrapped in `catch (Exception e) { log }` — **a rollback throw is swallowed and close proceeds to `status = CLOSED`** |
| Owning thread | `FrontendTransactionImpl:220` (`storageTxThreadId` at begin), `assertOnOwningThread:167-171` | commit asserts owning thread (`:271`); **`rollbackInternal` deliberately does not** — that is what makes foreign teardown runnable |
| checkOpenness | `:4587-4591` plain (non-volatile) status read; guards `commitImpl` (`:4394`) and `rollback()` (`:4499`) [verified — matches design.md:810-815's claims] |
| Stranding monitor | `AbstractStorage:489-491` (`StaleTransactionMonitor`, YTDB-550) scans tsMins; an idle open tx pins an atomic op from `beginInternal` (`FrontendTransactionImpl:219-220`) so it **is** reported |

So: cross-thread close is *possible and structurally intended* (that is what `realClose` does), but
nothing excludes the owner thread running concurrently.

## A.1 Failure-mode enumeration at HEAD

**FM-A1 — pool.close() of an *idle* checked-out session holding the mutex (owner between operations).**
Pool thread: `realClose` → `internalClose` → `rollback()` (openness still OPEN, passes) →
`rollbackInternal` → `clear()` → `close()` → finally releases the permit
(`FrontendTransactionImpl:1039`, marker true, `releaseFor` matches). Owner later hits
`checkOpenness` → loud `DatabaseException`; its own `close()` no-ops
(`DatabaseSessionEmbeddedPooled.close:36-38` `isClosed()` guard).
**Verdict: ALREADY SAFE at HEAD.** The plan-era wedge premise ("checkOpenness refuses the owner's
teardown so the permit strands", design.md:808-815) does **not** describe the base case anymore —
Track 3 put the release inside the teardown the pool thread itself runs. The wedge survives only in
the variants below.

**FM-A2 — teardown rollback throws before `tx.close()` → stranded permit. WEDGED-UNTIL-RESTART.**
`rollbackInternal` has throw points *before* it reaches `close()`: `status == ROLLED_BACK` →
`IllegalStateException` (`:410-411`); `INVALID/COMPLETED` → throw (`:432-434`); a throw out of
`invalidateChangesInCacheDuringRollback()` (`:429`) or `clear()` (`:430`) [inferred reachable —
both touch record/cache state that a sick session can corrupt]. `internalClose` **swallows** it
(`:3281-3284`) and proceeds to `status = CLOSED`. The marker is still true; no other code path
calls `releaseMetadataWriteMutexForTx`. Permit held forever; every later DDL parks in
`acquireUninterruptibly` (uninterruptible — an operator cannot even kill the waiter).
**Verdict: real wedge path at HEAD.** Compounding: a later schema write *on the same session/thread*
passes the engage reject (holder.session == this session, `MetadataWriteMutex:91-93` only rejects
*different* session) and parks forever on its own permit (**FM-A7**).

**FM-A3 — mid-flight engage vs. pool teardown (the design's window). WEDGE POSSIBLE, NARROWED AT HEAD.**
Owner is inside `engage()` (permit acquired at `:102`, marker not yet true at `:3599`) — or still
*parked* on the acquire. Pool thread runs the full teardown: its release pass sees
`metadataMutexEngaged == false` → **no-op** (`:3618-3620`), session goes CLOSED, tx cleared. Owner
resumes, writes holder, sets marker, and continues on a dead session.
- If anything throws inside the seed `try` (likely: the tx was cleared/rolled back under it, loads
  fail) → the catch arm (`:3510-3519`) releases → **healed**. This arm post-dates the frozen design
  and covers most of the window [verified code, reachability inferred].
- If the seed *succeeds* (plain-field status reads have no happens-before edge to the closer's
  writes, so the owner can miss `CLOSED` [inferred]), the owner's marker/holder now record an
  acquisition that **no future teardown will release** — the pool thread's one-shot `tx.close()`
  already ran. Owner's eventual commit is refused (`checkTransactionValid`, `doCommit:695-703`
  `RollbackException`), its session `close()` no-ops, permit strands.
**Verdict: the structural hole is exactly the design's Dekker window — the release pass ran before
the acquisition finished recording, and nothing re-runs it.**

**FM-A4 — pool teardown racing an *actively executing* owner.** Two distinct hazards:
- **(a) Owner mid-commit (COMMITTING).** `rollbackInternal`'s `BEGUN, COMMITTING` arm (`:414-431`)
  flips to ROLLBACKING and `clear()`s `recordOperations` **while the owner's storage commit iterates
  them** (`applyCommitOperations` reads `frontendTransaction.getRecordOperationsInternal()`,
  `AbstractStorage:2610`), and `close()` calls `atomicOperation.deactivate()` (`:1013-1026`) under
  the owner. **Corrupt-state risk** — the four storage locks do not protect the *tx object*, which
  the pool thread mutates lock-free. The pool thread then releases the permit while the owner still
  runs inside the four-lock window: a successor schema tx engages the mutex and serializes behind
  the zombie **on `SchemaShared.lock`** (`commitSchemaCarry:3175` holds it for the whole commit) —
  the design's "commit-phase zombie excluded structurally by schema-lock scope" (design.md:869-874)
  **holds at HEAD** [verified]. Single-writer degrades to lock-serialization; tx-object integrity
  does not.
- **(b) Owner teardown racing pool teardown (both reach `tx.close()`).** Both run
  `releaseMetadataWriteMutexForTx`: the volatile check-then-clear (`:3618-3621`) is not atomic → both
  can pass; `releaseFor` is not CAS → both null-and-release → **permit count = 2 → two concurrent
  schema transactions admitted silently forever after**. This is the worst-class failure (silent
  single-writer break) and is exactly the gap Track 3's handoff flagged (track-3.md:513-517, 538).

**FM-A5 — leaked session, never closed (or GC'd non-pooled session).** Permit held forever; `Holder`
pins the session strongly. YTDB-550 monitor reports the long-running tx. **This is I-C3's *accepted*
outcome** — loudly wedged until restart, reclamation is YTDB-1114. Only gap vs. design: the engage
has no timed re-wait/holder-naming diagnostic (design.md:888-890) and is uninterruptible.

**FM-A6 — engage-then-crash-before-tx-open.** Unreachable: engage lives only inside
`ensureTxSchemaState`, which requires an active tx (`:3480-3481`), and owner-thread seed failures
release via the catch arm. **ALREADY SAFE.**

**FM-A7 — same-session re-engage after a strand.** A later schema write on the same session/thread
passes the engage reject (holder.session == this session; `MetadataWriteMutex:91-93` only rejects a
*different* session) and parks forever on its own permit. Compounds FM-A2 (custom data wiped but
permit still held → same-session retry parks forever).

**FM-A8 — release racing the next session's engage.** `releaseFor` nulls holder *before*
`permit.release()` (`:121-123`); the next engager acquires then writes its own holder; the
same-thread reject only trusts same-thread holder writes (program order). **ALREADY SAFE under the
single-releaser premise**; must stay safe under concurrent releasers via A.2.

### Summary table

| # | Scenario | HEAD verdict |
|---|---|---|
| A1 | pool close, idle owner | **safe** (heals via owning-session teardown) |
| A2 | teardown rollback throws pre-close | **wedged until restart** |
| A3 | teardown vs mid-flight engage | **wedge possible** (narrowed by seed-failure arm) |
| A4a | teardown vs live commit | **corrupt-state risk** (tx object); permit OK-ish (zombie serializes on schema lock) |
| A4b | owner finally vs pool finally | **double release → silent single-writer break** |
| A5 | leaked session | accepted (loud wedge, monitored) |
| A6 | engage w/o tx | unreachable |
| A7 | re-engage after strand | infinite uninterruptible park (compounds A2) |
| A8 | release vs next engage | safe; must survive Track 7 changes |

## A.2 Elaborated mechanism (Track 3 handoff sketch → full design)

**State.**
- `MetadataWriteMutex.holder` becomes `AtomicReference<Holder>`, `Holder(session, ordinal, thread)`;
  ordinal drawn from a monotonic generator at acquire.
- Session side: replace `metadataMutexEngaged` with `volatile long engagedOrdinal` (0 = none) — a
  **session field**, like today's marker, so it survives `FrontendTransactionImpl.clear()`'s
  custom-data wipe (the exact reason Track 3 moved the marker out of tx `userData`,
  track-3.md:490-495; satisfies the D7 "must survive the wipes" caveat).
- Session side: `volatile boolean teardownIntent` — the Dekker mark. Dedicated flag, **not**
  `STATUS.CLOSED`, because teardown's own `rollback()` must still pass `checkOpenness` (`:4499`;
  CLOSED is set only after rollback in `internalClose:3287`) — the design's constraint maps verbatim
  onto HEAD code.

**Engage (owner thread, in `engage()` / `ensureTxSchemaState`):**
1. Reject: same-thread + different-session → throw (unchanged). **New:** same-thread + *same*-session
   holder → throw `IllegalStateException` instead of self-parking (closes FM-A7's silent
   forever-park).
2. Acquire the permit — see Q-A3 for `acquireUninterruptibly` vs. timed re-wait loop.
3. `ord = ordinalGen.incrementAndGet()`; `holder.set(new Holder(session, ord, thread))` (plain set
   is safe: only the permit owner writes a non-null holder).
4. `session.engagedOrdinal = ord` (volatile store).
5. **Dekker re-check:** read `session.teardownIntent`. If set → self-release (`releaseFor(session,
   ord)`), zero `engagedOrdinal`, throw `DatabaseException("session was closed while engaging the
   schema mutex")`. Volatile store (holder/ordinal) followed by volatile load (mark) on one side;
   volatile store (mark) followed by the release pass's loads on the other — Java volatile sequential
   consistency guarantees at least one side sees the other. This closes **FM-A3** completely:
   teardown-missed-engage → engage sees mark, self-heals; engage-missed-mark → teardown's release
   pass sees the ordinal and heals; both see each other → one CAS winner, one warn-noop.

**Release — `releaseFor(session, expectedOrdinal)`:**
- `h = holder.get()`; mismatch on null / session / **ordinal** → warn-log, return (never throw — it
  runs in teardown finallys where a throw masks the real exception; D7 caveat).
- `holder.compareAndSet(h, null)` — loser warn-noops; winner calls `permit.release()`.
- The session-level gate becomes an **atomic claim**: `long ord = ENGAGED_ORDINAL.getAndSet(session,
  0); if (ord == 0) return; mutex.releaseFor(session, ord);` — the `getAndSet` makes exactly one of
  any number of racing teardowns proceed (closes **FM-A4b** on its own; the CAS + ordinal in
  `releaseFor` is the second, independent belt).

**What the ordinal concretely protects against (ABA):** pooled session objects are *recycled* —
`ResourcePool.returnResource` re-queues the same instance and `reuse()` re-opens it
(`DatabaseSessionEmbeddedPooled:46-50`). A stale releaser that captured "session S is engaged" before
being descheduled can wake after S was released, returned, re-acquired by a new borrower, and
**re-engaged**. With session-only keying its release matches the *new* acquisition and frees a permit
a live schema tx still needs → two writers. With `(session, ordinal)` keying the stale presenter's
old ordinal mismatches → warn-noop. Session identity ABA is real here, not theoretical [verified:
same object recycled].

**Teardown protocol (per thread):**
1. `realClose()` entry (`DatabaseSessionEmbeddedPooled:58`): set `session.teardownIntent = true`
   **before** `super.close()`.
2. Run the session's own teardown exactly as today (rollback → `tx.close()` → finally → release
   pass). Foreign thread allowed *because* it is the owning session's own teardown (I-C3's one
   legitimate foreign releaser).
3. **New, closes FM-A2:** add a second release-pass call in a `finally` around `internalClose`'s
   rollback block (`DatabaseSessionEmbedded:3279-3284`), so a rollback that throws before
   `tx.close()` still releases. Idempotent by the `getAndSet` claim; the orphaned tx-local schema
   state itself needs no undo (it lives in tx custom data and dies with the tx object; the shared
   schema was never touched — D4).

**Behavior matrix under the new design:**

| FM | Outcome |
|---|---|
| A1 | unchanged (heals; release now CAS'd) |
| A2 | **healed** by the internalClose-level release finally |
| A3 | **closed** by the Dekker pair (all three interleavings terminate in "permit has a releaser") |
| A4a | tx-object race **not** in scope of the handshake — permit heals, zombie serializes on schema lock; see Q-A2 |
| A4b | **closed** (getAndSet claim + CAS + ordinal) |
| A5 | unchanged by design (I-C3); diagnostic improves if Q-A3 adopts the timed loop |
| A7 | **loud throw** instead of uninterruptible park |
| A8 | preserved: winner-nulls-before-release ordering kept inside the CAS winner |

**Invariants established:** (1) at most one release per acquisition (ordinal + CAS); (2) at least
one release per acquisition whose session teardown *starts* (Dekker + the internalClose finally);
(3) no release of a successor's acquisition (ordinal); (4) the release path never throws; (5) all
tx-scoped state mutation stays on the teardown running the owning session's own close path (I-C3).

## A.3 Alternatives

| Alternative | Trade-offs |
|---|---|
| **(i) `tryAcquire`-timeout + ownership re-check loop** instead of Dekker | Fixes the *parked-engager* case elegantly (waiter periodically re-checks its own session's `teardownIntent`/status and aborts without acquiring). But the **acquired-but-unrecorded** window still needs a post-acquire re-check — you end up writing the Dekker pair anyway. Verdict: **complementary, not alternative** — design.md:888-890 already wants a timed re-wait with a holder-naming diagnostic; adopting it also fixes uninterruptibility (FM-A5/A7 waiters become killable). Recommend both. |
| **(ii) Pool-layer prevention** (pool.close waits/refuses while a borrowed session's mutex is engaged) | Moves the wedge into `pool.close()` (unbounded wait on a stuck owner — the pool close is spec'd one-shot/no-retry); does nothing for non-pooled sessions (FM-A2 path is pool-independent); does not remove FM-A4b (owner close vs pool close can still race after a timeout-and-proceed). Cheap to add as a *courtesy drain* but cannot be the correctness mechanism. |
| **(iii) Status quo + watchdog force-release** | This is cross-thread reaping-lite: a watchdog that frees the permit of a "stranded" holder violates I-C3, and a false positive admits a second writer while the zombie still runs — protected only by schema-lock serialization, and unprotected for the tx object. This exact family was tried (passes 7/8) and withdrawn to YTDB-1114 (D7 record). Rejected. |

## A.4 Open design questions for the user

- **Q-A1 (FM-A2 closure placement):** a release-pass `finally` inside `internalClose` (minimal,
  proposed) vs. restructuring so teardown *always* reaches `tx.close()` (cleaner ownership, bigger
  diff)?
- **Q-A2 (FM-A4a):** should `realClose` detect an in-flight foreign-thread commit
  (`storageTxThreadId != currentThread && status == COMMITTING`, both readable at
  `FrontendTransactionImpl:158,117`) and **skip the rollback/clear**, letting the zombie finish and
  release via its own close — instead of `clear()`-ing tx state under a live commit? The frozen
  design accepts the zombie; it is silent about the pool thread actively corrupting the tx object it
  races. My recommendation: yes, defer — the permit then heals through the owner's own finally.
- **Q-A3:** replace `acquireUninterruptibly` with the design's timed re-wait loop (holder-naming
  diagnostic, interruptible)? Affects FM-A5/A7 operability.
- **Q-A4:** set `teardownIntent` only in `realClose`, or in every `internalClose(false)` (harmless
  and covers non-pooled abnormal closes)?
- **Q-A5:** is the new same-session re-engage **throw** (FM-A7) acceptable API behavior, given it
  can only fire after a prior stranding bug?

---

# DRAFT B — Freezer gate

## B.1 What the freezer actually gates at HEAD, and whether the outage is real

**Semantics [verified].** `OperationsFreezer.startOperation` (`OperationsFreezer.java:30-58`) has
exactly **one** production caller: `AtomicOperationsManager.startToApplyOperations:128`, i.e. the
WAL-apply phase of a commit, entered via `AbstractStorage.startTxCommit:6340-6342`. **Reads never
touch the freezer.** At depth 0 with `freezeRequests > 0` the entrant either throws —
`throwFreezeExceptionIfNeeded` (`:114-118`) throws the first supplier in the map, so *any* registered
throw-mode freeze makes *all* entrants throw — or enqueues on the `WaitingList` and `LockSupport.park`s
(`:44-48`), woken only when `releaseOperations` drops the count to 0 (`:88-112`). `freezeOperations`
(`:72-86`) increments the count then spin-waits (`Thread.yield`) for `operationsCount` to drain.

**Registration sites at HEAD — four, not the design's five** (no index-rebuild freeze exists; the
design's count needs correcting):

| Site | Kind (proposed) | Mode | Duration | Locks held while registering |
|---|---|---|---|---|
| `AbstractStorage.freeze(db, throwException)` `:5508-5560` (via `DatabaseSessionEmbedded.freeze`, `:3220-3226`) | **OPERATOR** | throw (`:5522`) or **park** (`:5526`), caller-chosen | until `release()` `:5563-5580` — minutes (filesystem snapshot) | `stateLock.readLock` (`:5510`) |
| `AbstractStorage.doSynch` `:5347-5385` | TRANSIENT | park | engine+data flush | callers hold `stateLock.readLock` (`synch():5319`, `freeze():5510`) |
| `DiskStorage.copyWALToBackup` `:357-365` | TRANSIENT | park | WAL flush + segment ops | `backupLock` only |
| `DiskStorage.storeBackupDataToStream` `:1249-1263` | TRANSIENT | park | WAL cut + new segment | `backupLock` only |

**The outage trace — REAL at HEAD, end to end [verified statically]:**
1. Operator: `db.freeze(false)` → park-mode freeze registers (`:5526`), drains in-flight applies,
   storage returns with the freeze **still engaged** for the snapshot's duration. `release()` takes
   no `stateLock` (`:5563-5580`), so it stays callable.
2. Any schema tx commits: `commitSchemaCarry:3175-3200` acquires SchemaShared.write → IM exclusive
   (`IndexManagerEmbedded:92`) → `stateLock.writeLock` → commit window → `applyCommitOperations` →
   `startTxCommit` (`:2766`) → `startOperation` → `freezeRequests > 0`, no throw supplier → **parks
   holding all four locks**.
3. Every read path acquires `stateLock.readLock` (e.g., `AbstractStorage:761, :920, :1798,
   :1825...`) → blocked behind the held write lock; lock-based schema reads block on
   `SchemaShared.lock`; all other commits block. **Total read+write outage for the whole
   operator-freeze window.** Exactly I-freezer-1's target scenario.

**What is ALREADY closed / bounded at HEAD (verified, important corrections to the plan-era text):**
- **Throw-mode operator freeze (`freeze(db, true)`) + schema commit: already loud, no outage.** The
  throw fires inside `startOperation` *before* `operationDepth.increment()` and with
  `operationsCount` already re-balanced (`:38-40`) — no depth/count leak. `startTxCommit` sits
  **outside** the rollback-paired `try` (`:2766`), and reconciliation runs after it, so nothing
  structural was published: the exception unwinds through `commitSchemaCarry`'s finallys (locks
  released), the tx rolls back via `doCommit`'s catch (`FrontendTransactionImpl:748-750`). The "zero
  locks held" letter is violated for microseconds; the property that matters (no parked writer, reads
  resume) holds.
- **The operator freeze cannot engage mid-window.** `freeze()` must take `stateLock.readLock`
  (`:5510`) and therefore blocks until an in-flight schema commit finishes. The design treats "a
  freeze engaging after the write lock is held" as the case needing the authoritative in-window gate;
  at HEAD **only the two backup transients** (which hold `backupLock`, not `stateLock`) can engage
  mid-window — and those are bounded, which the design accepts anyway.
- **The real residual race** is narrower: the operator freeze engaging in the gap after the commit
  holds SchemaShared.lock/IM lock but before it requests `stateLock.writeLock` (freeze's readLock is
  grantable there). The commit then acquires the write lock post-freeze and parks → outage. This is
  the probe-to-entry window that makes an entry-only probe insufficient — confirming D7's rejection
  of alternative "(v) separate pre-call probe".
- Data commits park inside `stateLock.readLock` today; combined with a queued write-lock acquirer
  this can already stall readers (writer preference in `ScalableRWLock`) — **pre-existing, not
  Track 7 scope**, but worth a line in the track file so nobody "fixes" it incidentally.

**Two plan ordering constraints are already structurally satisfied at HEAD:** "throw strictly before
the depth increment" (`:38-40`) and "gate + `startTxCommit` outside the rollback-paired try"
(`:2766`). The diff is therefore genuinely small.

## B.2 Proposed design

**Taxonomy.** `enum FreezeKind { OPERATOR, TRANSIENT_QUIESCE }`, a new parameter of
`freezeOperations`/`freezeWriteOperations` (`AtomicOperationsManager:268-273`). Site mapping per the
table above; both arms of `freeze()` are OPERATOR. Representation: a second counter
`operatorFreezeRequests` incremented **before** `freezeRequests` and decremented **after** it in
release (publish-kind-before-count / retract-count-before-kind), so any entrant that observes the
freeze also observes its kind — the plan's "taxonomy must publish before the `freezeRequests`
increment" constraint, realized as counter ordering.

**Gate rule.**
- A **schema-armed entrant** — a commit whose apply was entered from the schema-carry branch;
  concretely a boolean threaded `startTxCommit → startToApplyOperations → startOperation` (the arm
  signal is `schemaContext != null`, the same D19 signal that chose the write-lock branch,
  `AbstractStorage:2523-2531`) — **never parks while `operatorFreezeRequests > 0`**: it throws
  `ModificationOperationProhibitedException` at **(1)** the loop-top (where `throwFreezeExceptionIfNeeded`
  runs today, `:40`) and **(2)** the park-decision point (`:46`, after enqueue, immediately before
  `LockSupport.park`). It may park when only transient freezes are active.
- **Data commits: byte-for-byte today's semantics** (park unless a throw-supplier freeze exists).
- **Reads: untouched** (they never enter the freezer).
- **Operator-arm cut-and-unpark:** the OPERATOR arm of `freezeOperations`, right after its
  increments, runs the exact detach-and-unpark block `releaseOperations` already owns
  (`cutWaitingList` + unpark loop, `:101-110`, `WaitingList.java:30-67`). An already-parked schema
  entrant wakes, loops to the top (`while (freezeRequests.get() > 0)`, `:35` — the loop already
  re-evaluates on every wake [verified]), sees OPERATOR, throws. Woken *data* entrants re-enqueue
  and re-park through the same loop — a bounded, rare thundering herd.
- The **park-decision check closes the engage-during-enqueue race**, including the cut firing before
  the entrant enqueued: the entrant re-reads the kind after joining the list; volatile counter
  ordering guarantees it sees an operator freeze whose cut it missed.
- Optional best-effort **entry probe** before the four locks (throws with literally zero locks) —
  see Q-B3.

**Exception construction:** a park-mode operator freeze registers **no** throw supplier, so the gate
needs its own `ModificationOperationProhibitedException` factory (storage name + "operator freeze in
progress"); tests assert the type (I-freezer-1's "loud by exception type, not generic error").

**Lock-order / deadlock proof.**
1. The gate adds **no blocking edge**: its two outcomes are *throw* (releases everything by
   unwinding through `commitSchemaCarry`'s finallys `:3183-3199`) and *park* (already exists today;
   now restricted to transient freezes). The cut-and-unpark only unparks; counters are lock-free.
2. The remaining park (schema entrant vs transient quiesce, four locks held) is bounded iff the
   transient freeze bodies never need the locks the parked commit holds. Verified per site:
   `copyWALToBackup`'s frozen section is WAL-only (`:357-364`); `storeBackupDataToStream`'s is
   WAL-cut + segment (`:1249-1263`); neither touches `stateLock`/schema/IM locks. `doSynch`'s
   *registration* can block earlier at its caller's `stateLock.readLock` (`synch():5319`) while a
   schema commit holds the write lock — then its freeze simply isn't registered yet, so no cycle;
   ordering is always "acquire readLock → freeze", never "freeze → acquire stateLock".
3. The throw path cannot leak freezer state: count re-balanced and depth untouched before the throw
   (`:38-40`); it cannot be masked by `endTxCommit` (never reached — outside the try) and cannot fire
   the undo arms against unpublished structure (reconcile runs after `startTxCommit`).
4. Mutex interaction: the thrown commit unwinds to `doCommit`'s catch → `rollbackInternal` →
   `tx.close()` → mutex released in the normal teardown finally (`FrontendTransactionImpl:1039`). No
   new mutex path.

**Invariants established:** a schema commit is never parked while an operator freeze is active, at
any interleaving point (pre-engaged / mid-entry / layered-over-transient); an operator freeze never
observes a growing read-blocked queue caused by a schema commit; data-commit gate semantics
unchanged; freezer depth/count never leak on the throw path.

## B.3 Alternatives

| Alternative | Trade-offs |
|---|---|
| **Entry-probe only** | The SchemaShared→stateLock gap (B.1) still parks the commit in-window. Rejected in D7 with exactly this reasoning; the trace above confirms it at HEAD. |
| **Any-freeze abort for schema commits** | DDL aborts against every routine backup/synch quiesce — spurious failures on healthy workloads. Rejected (D7 alternatives). |
| **Bounded-timeout park** (no taxonomy; schema entrant parks with a deadline, then throws) | No registration-site changes, but keeps the outage up to the timeout, aborts against legitimately slow transients, adds a tuning knob. Weaker than the taxonomy on both sides. |
| **Freeze takes `stateLock.writeLock`** (exclude commits at the lock layer) | Recreates the outage on the other side: readers pile up behind the queued/held write lock for the freeze duration — the opposite of freeze's contract (reads flow). |
| **Hoist the freezer above the four locks** (make `startOperation` lock #0 of the schema commit) | Entrant would park with zero locks — attractive — but the freezer's engagement would then span the whole commit (today it spans `startTxCommit`→`endAtomicOperation`), so `freezeOperations`' drain spin (`:81-83`) waits on entire schema commits (including promotion), inflating every backup/synch freeze latency; also a bigger re-plumbing of `AtomicOperationsManager`. Worth discussing, but the gate achieves the property with a far smaller diff. |

## B.4 Open design questions for the user

- **Q-B1 (arming scope):** design says frontend-commit path only. But legacy **top-level DDL
  micro-transactions** (e.g., `reassociateClassIndexesOnRename`'s `executeInTxInternal`,
  `IndexManagerEmbedded:1657-1679`) reach the same `startTxCommit` while holding the schema/IM locks
  — under a park-mode operator freeze they park holding those locks (schema reads block; smaller than
  the full outage but same shape). Arm them too, or leave as part of the documented top-level-DDL gap
  (removed in the upcoming PR, per §0)?
- **Q-B2 (site count):** the design's fifth site (index rebuild) does not exist at HEAD — confirm
  nothing on the rebuild-on-open path (`IndexManagerEmbedded` rebuild triggers) freezes, and correct
  the count in the track file when Move 2 lands.
- **Q-B3:** include the best-effort entry probe (throws with zero locks in the common pre-engaged
  case, nicer failure surface) or rely solely on the in-window gate (smaller diff; throw then happens
  microseconds inside the window, released on unwind)?
- **Q-B4:** is the operator-arm thundering herd (waking parked data commits, which re-park)
  acceptable? (Rare — once per operator freeze.)
- **Q-B5:** message/typing of the gate's own `ModificationOperationProhibitedException` — reuse the
  wording of `freeze(db, true)`'s supplier (`:5523-5524`, "Modification requests are prohibited") or
  a distinct "operator freeze in progress; schema commit aborted" text (better diagnosability, new
  string for tests to pin)?

---

## Invariant cross-reference

- **I-C3 (tx-scoped resources torn down only on the owning thread).** Upheld by Draft A: all
  tx-scoped state mutation stays on the teardown running the owning session's own close path; the one
  legitimate foreign releaser (pool shutdown) runs that session's own teardown, and the ordinal + CAS
  make its release idempotent against the owner's. No new cross-thread reaper (YTDB-1114 boundary
  preserved). FM-A5 (leaked session) is I-C3's accepted loud-wedge outcome.
- **I-handshake-1 (the mutex has exactly one releaser and never wedges).** Upheld by Draft A's
  behavior matrix: at most one release per acquisition (ordinal + CAS); at least one release per
  acquisition whose teardown starts (Dekker pair + the internalClose-level release finally); the
  release path never throws. Closes FM-A2, FM-A3, FM-A4b; FM-A7 becomes a loud throw.
- **I-freezer-1 (a schema commit never turns a freeze into a read outage).** Upheld by Draft B: the
  kind-aware gate at the loop-top and park-decision sites plus the operator-arm cut-and-unpark
  guarantee a schema commit never parks while an operator freeze is active (pre-engaged, mid-entry,
  or layered over a transient), asserted by exception type. Bounded transient parks remain, which the
  design accepts.

## Provenance / verification key

- **[verified]** — established by reading the cited code at HEAD `e2605c8ba3`.
- **[inferred]** — timing- or reachability-dependent; consistent with the code but not proven without
  execution. Explicitly tagged inline at each such claim (FM-A2 throw reachability; FM-A3
  seed-throw reachability and the plain-field visibility miss).

---

## Rulings — user design review (2026-07-20)

Both §0 plan corrections **ACCEPTED** by the user. Already-ruled scope decisions **CONFIRMED** on
record: CS2 (`endTxCommit` undo-bypass) absorbed into Step 1; the top-level-DDL mutex gap out of
scope (legacy path removed in an upcoming PR). All ten open questions ruled as follows; these rulings
are binding inputs to the adversarial review and step decomposition.

- **Q-A1 — Option 1.** FM-A2 closed by a release-pass `finally` wrapped around `internalClose`'s
  rollback block; idempotent via the `getAndSet(engagedOrdinal, 0)` claim. Accepted cost: two release
  call sites, both funneling through the same atomic claim — documented in the mutex javadoc.
- **Q-A2 — Option 1.** `realClose` detects an in-flight foreign-thread commit
  (`storageTxThreadId != currentThread && status == COMMITTING`) and SKIPS the rollback/clear,
  deferring tx teardown to the owner's own path. Pin: on skip, the pool thread still sets
  `teardownIntent` and performs only safe session-level close bookkeeping. Racy detection accepted:
  late-skip = commit already finished; late-rollback = today's behavior, strictly no worse.
- **Q-A3 — Option 1.** Engage uses a timed re-wait loop replacing `acquireUninterruptibly`, with four
  pinned semantics: (1) wait is UNBOUNDED — never spuriously fails a DDL; (2) periodic WARN on each
  timeout naming the holder (session, thread, ordinal, elapsed hold), interval a ~10s constant, not
  configurable; (3) interruptible — restore the interrupt flag, throw `DatabaseException` naming the
  holder; (4) loop-top re-check of the waiter's own `teardownIntent`/session status — a waiter whose
  session was torn down aborts instead of acquiring.
- **Q-A4 — Option 2.** `teardownIntent` is set at the top of EVERY `internalClose(false)`, making the
  invariant unconditional: "teardown marks before it tears." Hygiene pin: the flag is cleared on
  every session re-open/`reuse()` path.
- **Q-A5 — Accepted.** Same-session re-engage on a stranded holder throws immediately. Three pins:
  (1) message names the stranded holder (ordinal, thread, elapsed) and the likely cause ("previous
  acquisition on this session was never released"); (2) type is `IllegalStateException`, consistent
  with the engage-order violation throw at the same seam; (3) regression test pins type + message.
- **Q-B1 — Option 1.** Gate arms the frontend schema-carry commit path only (`schemaContext != null`).
  Legacy top-level DDL stays unarmed — ACCEPTED RISK, recorded in the track file and PR description:
  legacy DDL can park under an operator freeze holding schema/IM locks (partial schema-read outage);
  accepted because the legacy path is removed in an upcoming PR; revisit if the removal slips. Gate
  design stays legacy-agnostic so later arming is additive.
- **Q-B2 — Settled by independent verification (episode t31.e1).** Exactly 4 production
  freeze-registration sites (`AbstractStorage.doSynch` :5349; `AbstractStorage.freeze` :5522/:5526 —
  two textual calls, one site, one release point; `DiskStorage.copyWALToBackup` :357;
  `DiskStorage.storeBackupDataToStream` :1249); exactly 1 production `startOperation` caller
  (`AtomicOperationsManager.startToApplyOperations`:128); NO rebuild-registered freeze site. Correct
  the count in track-7.md at decomposition and record three taxonomy implementation constraints:
  (1) rebuild transitively touches `doSynch`'s TRANSIENT freeze via `RecreateIndexesTask.run`'s
  finally `synch()` — existing site, not a 5th registration; (2) `freeze()` nests a `doSynch`
  TRANSIENT inside the OPERATOR freeze — kind counters must tolerate nesting; (3) `release()` uses
  the `-1` sentinel (`unfreezeWriteOperations(-1)`) — the release side must map `release()` →
  OPERATOR-decrement explicitly.
- **Q-B3 — Include the best-effort entry probe** (at `commitSchemaCarry` entry, before
  SchemaShared.lock) in addition to the authoritative in-window gate. Two pins: (1) probe and gate
  call ONE shared helper — single counter-read + single `ModificationOperationProhibitedException`
  factory, no drift possible; (2) test matrix covers both paths explicitly: pre-engaged freeze →
  probe throws with zero locks; freeze engaging in the probe-to-entry window → in-window gate throws.
- **Q-B4 — Herd accepted.** The OPERATOR-arm cut-and-unpark may wake parked data commits, which
  re-park (bounded: once per operator-freeze engagement, at most the concurrently-parked committers,
  one loop iteration each). Pins: (1) comment at the cut site documenting the herd as deliberate and
  bounded; (2) test pins correctness — data entrants all re-park (none admitted), schema-armed
  entrant wakes and throws.
- **Q-B5 — Option 2.** The gate/probe exception keeps type `ModificationOperationProhibitedException`
  and carries a DISTINCT message including the storage name, e.g. "Schema commit aborted: operator
  freeze in progress on storage '<name>' — modifications are prohibited until release; retry after
  the storage is released". The message is a stable, tested contract distinguishing gate/probe throws
  from the legacy throw-mode supplier's wording.

These rulings complete the mandatory user design review for Track 7. Next gates: pre-implementation
adversarial review of this agreed design, then step decomposition into track-7.md.

---

## Amendments — adversarial pass 14 triage (2026-07-21)

Adversarial pass 14 attacked the full agreed design including the 2026-07-20 rulings. Reports:
`adversarial-pass14-concurrency.md` (CN10–CN18, verified-safe traces V1–V8) and
`adversarial-pass14-durability.md` (CS10–CS14, refuted-hypotheses section). User-approved triage:
**two blockers reversed** (CN10, CS10 — design amended below), **five should-fixes strengthened**
into an explicit skip-protocol specification and a widened release finally (CN11, CN12, CN13, CN14,
CN15 + CS11, CS12), **one suggestion folded as a guard plus an accepted risk** (CS14), and **four
suggestions folded as implementation notes** (CN16, CN17, CN18, CS13). The pass-14 verified-safe
orderings (V1, V2) are carried as mandatory implementation invariants. Every finding is addressed by
ID below; where an amendment changes earlier text it says so explicitly.

### Reversed decisions (design changes)

#### CN10 [BLOCKER, REVERSED] — the gate gains a THIRD checkpoint at the `stateLock.writeLock` acquisition

**What CN10 found.** Both existing checkpoints — the Q-B3 entry probe (at `commitSchemaCarry` entry)
and the in-window gate (inside `startOperation`) — sit on opposite sides of the
`stateLock.writeLock` acquisition (`AbstractStorage:3180`), and nothing gates that acquisition. In
the traced interleaving, an armed schema commit S holds `SchemaShared.lock` + IM lock and then
requests `stateLock.writeLock`, blocking behind a data commit D parked in the freezer while holding
`stateLock.readLock`; because `ScalableRWLock` is writer-preference, S's queued write request also
stalls every new `stateLock.readLock` acquirer → full read+write outage for the whole operator-freeze
window, with the gate installed. S never reaches `startOperation`, so the in-window gate never runs;
the probe already passed. I-freezer-1's read outage survives the design. This **supersedes §B.2's
lock-order proof item 1's implicit assumption** that an armed commit in the probe-to-entry window
always *reaches* `startOperation`, and **supersedes B.1's residual-race sentence** "The commit then
acquires the write lock post-freeze and parks → outage" (the commit does not even park — it blocks on
the write lock).

**Amended design.** The schema-carry branch acquires `stateLock.writeLock` through a **timed-acquire
loop** using the existing `ScalableRWLock.exclusiveTryLockNanos` (`:586`), replacing the plain
`stateLock.writeLock().lock()` at `commitSchemaCarry:3180`. Between attempts the loop re-checks
`operatorFreezeRequests` and, if an operator freeze is (or becomes) active, throws the shared
Q-B3/Q-B5 `ModificationOperationProhibitedException` factory exception. This is the **third
checkpoint** of the gate.

- **Loop bound/interval.** Per-attempt timeout is one polling interval; the loop is otherwise
  unbounded (a schema commit is never spuriously failed by contention alone — it only throws on an
  operator freeze, matching the Q-A3 unbounded-wait philosophy). Reuse the freezer gate's polling
  cadence for the interval (a small constant, aligned with the reviewer's fix direction — one
  polling interval collapses the held-lock window); the exact constant is pinned at decomposition.
  Each timeout re-checks `operatorFreezeRequests`; a non-active recheck simply retries the
  `exclusiveTryLockNanos`.
- **Locks held at the throw site.** `SchemaShared.lock` (write) and the IM exclusive lock are held;
  `stateLock.writeLock` is NOT yet held (the acquire is what is being attempted). The unwind proof
  (§B.2 item 1 / V5) **extends to this throw site**: the throw unwinds through `commitSchemaCarry`'s
  already-held SchemaShared/IM finallys (`:3196-3199`), releasing both metadata locks; no
  `stateLock.writeLock` was acquired, so nothing is held past the unwind, and — critically — no data
  commit's `stateLock.readLock` is ever queued behind an S that gave up, so reads keep flowing. The
  held-lock window collapses to at most one polling interval.
- **Data commits unchanged.** Data commits' `stateLock.readLock` acquisition
  (`applyCommitOperations` data branch, `:2535`) is **UNCHANGED** — they still take the read lock
  plainly and still park in the freezer under any freeze (byte-for-byte today's semantics, per Q-B4).
  Only the armed writer's storage-lock acquisition becomes freeze-aware.
- **Snapshot-pin safety.** This third checkpoint throws after the snapshot pin (`:2525`), so it is
  inside `applyCommitOperations`' pin/clear window only if it lands there; since the timed loop lives
  in `commitSchemaCarry` before `applyCommitOperations` is entered, its throw is subject to the CS10
  paired-clear requirement below — the CS10 try/finally wrap must span the entire pin→lock-acquired
  region, not just the entry probe (see CS10).

**Amended Draft B invariant statement (supersedes the Invariant cross-reference sentence "the
kind-aware gate at the loop-top and park-decision sites plus the operator-arm cut-and-unpark
guarantee a schema commit never parks while an operator freeze is active").** I-freezer-1 is upheld
by **four checkpoints**, none of which lets an armed schema commit block or park while an operator
freeze is active: (1) the **entry probe** (Q-B3, at the commit path before any lock — pre-engaged
freeze); (2) the **`stateLock.writeLock` timed-acquire checkpoint** (CN10, between the metadata locks
and the write lock — a freeze engaging in the probe-to-write-lock window); (3) the **loop-top gate**
inside `startOperation` (a freeze already active when the entrant reaches the freezer); (4) the
**park-decision re-check** inside `startOperation` (a freeze engaging during enqueue, incl. the
operator-arm cut firing before the entrant enqueued), backed by the **operator-arm cut-and-unpark**
for the already-parked-behind-a-transient case. All four throw the one shared factory exception; the
first three throw with the write lock not yet held (metadata locks unwind cleanly), the fourth throws
from inside the freezer with the same clean unwind as HEAD's throw-mode path (V5).

#### CS10 [BLOCKER, REVERSED] — Q-B3 probe is hoisted ABOVE the thread-local schema snapshot pin

**What CS10 found.** `AbstractStorage.commit` pins the thread-local schema snapshot at `:2525`
(`session.getMetadata().makeThreadLocalSchemaSnapshot()`) before branching into `commitSchemaCarry`
(`:2532`); the only paired clear is `applyCommitOperations`' outermost finally at `:3142`. The Q-B3
probe as ruled ("at `commitSchemaCarry` entry") throws between the pin and that clear, so on the
common pre-engaged-freeze rejection the pin leaks: `MetadataDefault.immutableCount` stays at 1 for the
pooled session's lifetime (P6), permanently freezing the session's immutable snapshot at the
freeze-time schema. Consequences: every later read/validation/serialization on that session runs
against the stale snapshot (a concurrent session's schema commit is invisible → **durably wrong
bytes**), and any later DDL trips `forceClearThreadLocalSchemaSnapshot`'s non-zero-count
`IllegalStateException` — poisoning arbitrary future pool borrowers. The ruled type/message test would
pass while the leak ships.

**Amended design (supersedes Q-B3 pin 1's probe placement "at `commitSchemaCarry` entry, before
SchemaShared.lock").** The entry probe is **hoisted above the snapshot pin** — it runs at the commit
path *before* `session.getMetadata().makeThreadLocalSchemaSnapshot()` (before `AbstractStorage:2525`,
the CS10 seam), so its throw has genuinely zero side effects (no pin taken, no locks held, no WAL/table
state). It remains "before SchemaShared.lock" and "zero locks", honoring Q-B3's intent.

**Defensive paired clear.** Additionally, the pin→`applyCommitOperations`-finally region is wrapped so
that **any** throw escaping between the `:2525` pin and the established `:3142` clear cannot leak
`immutableCount` — a try/finally (or equivalent guard) spanning from immediately after the pin through
the point where `applyCommitOperations`' own finally takes over, clearing the snapshot on the escape.
This belts not just the hoisted probe but also the CN10 third-checkpoint throw (which lands after the
pin) and any future throw introduced in that window.

**Amended Q-B3 test matrix (adds to Q-B3 pin 2's two cases).** A third case: **probe throw → assert no
snapshot pin leaked** — `immutableCount` is balanced (returns to its pre-commit value) after a probe
rejection, verified on a pooled session that is then **recycled** and re-borrowed (the recycled
borrower must see a healthy snapshot and be able to run DDL). The pinned type/message assertion cannot
catch this; the count-balance assertion is mandatory.

### Q-A2 skip protocol specification (resolves CN11 + CS12 + CN13 + CN14 + CS11 together)

The pass-14 findings collectively show that ruling Q-A2's "skip the rollback/clear; the permit heals
through the owner's own finally" is underspecified in four composing ways. This block replaces the
loose Q-A2 prose with an explicit protocol. It **supersedes Q-A2's pin sentence** "on skip, the pool
thread still sets `teardownIntent` and performs only safe session-level close bookkeeping" and Q-A2's
A.4 rationale "the permit then heals through the owner's own finally".

**(1) What the pool thread does on detecting an in-flight foreign commit — the exhaustive whitelist.**
On the skip branch the pool thread performs ONLY:
- set `teardownIntent = true` (the Dekker mark);
- decrement the session count / remove its own thread-local activation bookkeeping that is private to
  the pool thread;
- log.

And explicitly does **NOT**:
- run `rollback()` / `rollbackInternal()` / `clear()` (**resolves CN14/CS11's foreign-mutation race** —
  no `clear()` of `recordOperations`, no `localCache.shutdown()`, no `closeActiveQueries()` under the
  live commit);
- run the mutex release pass (**resolves CN11 + CS12 Reading-B ambiguity** — the pool thread never
  releases a live commit's permit; control returns *before* entering the rollback try/finally that
  carries the CN12 release pass, so the release pass is not reached on the skip path);
- set `status = CLOSED`, or null `sharedContext`, or call `storage.close(this)`, or run
  `callOnCloseListeners()` (**resolves CS11/CN14** — anything the promotion phase or `checkOpenness`
  reads is OFF-limits: per CS11's trace, `status = CLOSED` alone would flip `checkOpenness` under the
  committing owner, drive the schema-carry promotion's `session.load` to throw, and land
  `setInError` on a *durable* commit, poisoning the storage);
- consume `internalClose`'s one-shot guard — the skip path returns **before** the guard flips
  `status`, so the owner's later `close()` still runs the full `internalClose` on the owning thread.

Rationale recorded: the whitelist is the minimum that marks intent without touching any state the live
commit (promotion load, tx-teardown cache access, after-commit callbacks) or `checkOpenness` reads.

**(2) The OWNER becomes the completer.** On commit completion (success or failure), a `finally` in the
owner's commit path checks `teardownIntent` and, if set, runs the full `internalClose` itself on the
owning thread. Its `tx.close()` finally releases the mutex through the normal path. This is the single
releaser of a live commit's permit (satisfying CN11's ordering requirement: the only releaser of a live
commit's permit is the owner). The owner-run `internalClose` is made **strand-proof** by the widened
FM-A2 release finally (CN12 below) — which also covers the owner-run path — so CS12's Reading-B
counterexample (owner post-skip rollback throws before `tx.close()`, permit stranded) cannot occur:
the widened finally releases even when the owner's rollback throws before reaching `tx.close()`.
(**Resolves CS12** by pinning Reading of record: the pool thread does not release; the owner completes
teardown and the widened finally guarantees the release.)

**(3) Skip DETECTION must not read plain fields.** The `status` and `storageTxThreadId` reads used for
the skip decision are given a defined happens-before: `FrontendTransactionImpl.status` and
`storageTxThreadId` are made **volatile** (both are written only on tx-boundary paths; cost is nil).
This **resolves CN13**: with volatile reads a stale-`COMMITTING` false-skip is impossible — the fields
are written under the owner's tx-boundary transitions and a volatile read on the pool thread sees the
last write in synchronization order, so the pool thread cannot observe a `COMMITTING` value left over
from a prior (reused) transaction while the current transaction is idle-BEGUN. The residual after this
change is exactly the benign TOCTOU the ruling already accepts: late-skip = commit genuinely already
finished (the owner's own close then handles teardown); late-rollback = today's behavior, strictly no
worse. Chosen mechanism: volatile fields (rather than reading under a lock), because the reads happen
on a foreign thread with no lock in common with the owner's tx-boundary writes, and volatile is the
minimal edge that makes the reused-object stale-`COMMITTING` read impossible.

**Amended Draft A invariant (2) (supersedes "at least one release per acquisition whose session
teardown *starts*").** Restated to carve out the skip arm: *at least one release per acquisition whose
session teardown **completes** — where the Q-A2 skip arm deliberately starts a mark-only teardown that
releases nothing and hands completion to the owning thread, whose own teardown (guaranteed to run,
since the skip does not consume the one-shot guard) carries the release under the CN12-widened finally.*

### CN12 [SHOULD-FIX, STRENGTHENED] — the FM-A2 release finally is WIDENED, and the pool-close loop is made throw-isolating

**What CN12 found.** The Q-A1 release finally placed "around `internalClose`'s rollback block" is one
block too low: `internalClose` runs `closeActiveQueries()` (`:3270`), `localCache.shutdown()` (`:3271`),
and the `if (isClosed()) { status = CLOSED; return; }` early return (`:668-670`) **before** the rollback
block. A pre-rollback throw (same `[inferred]`-reachability class the design already accepts for FM-A2)
never enters the release finally → permit strands one line above its fix; the exception then propagates
out of `realClose` and **aborts `DatabasePoolImpl.close`'s `for` loop (`:131-134`)**, stranding every
remaining checked-out session's permit (FM-A1 resurrected via loop abort).

**Amended design (supersedes Q-A1's "a release-pass `finally` wrapped around `internalClose`'s rollback
block" and behavior-matrix row "A2 — healed by the internalClose-level release finally").**
- The release pass is **hoisted to `internalClose`'s OUTER finally** (next to `activeSession.remove()`,
  `:3294`), so it covers the whole teardown body — including the pre-rollback throw points
  (`closeActiveQueries()`, `localCache.shutdown()`) and the paths that reach the outer finally. It is a
  no-op when nothing is engaged (the `getAndSet(engagedOrdinal, 0)` claim returns 0), so widening costs
  nothing.
- **Early-return path.** The `if (isClosed()) return` early return (`:3273-3276`) is routed through the
  same outer finally (the finally runs on the early return), so a teardown that starts and early-returns
  still runs the release pass. For the storage-already-closed sub-case the permit's relevance is bounded
  by SharedContext lifecycle drop (`YouTrackDBInternalEmbedded:837/:1045`); the release pass runs
  regardless, so it cannot be bypassed by the early return.
- **CN11 ordering still applies wherever the release pass lands:** the Q-A2 skip path must return before
  reaching this outer finally (per skip-protocol (1)), so the widened finally never releases a live
  commit's permit.
- **Pool-close loop isolation.** `DatabasePoolImpl.close`'s `realClose` loop (`:131-134`) wraps each
  `realClose()` in a per-session `try/catch (Throwable)` that logs and continues, so one throwing
  `realClose` cannot strand the other sessions' permits (the loop always reaches every checked-out
  session).

This also covers the owner-run `internalClose` from skip-protocol (2), closing CS12's Reading-B strand.

### CN15 [SHOULD-FIX, CORRECTED RISK] — Q-B1 accepted-risk wording corrected to the true blast radius

**What CN15 found.** The Q-B1 accepted-risk record understates the legacy exposure. The legacy
(non-tx-local) class-create/alter branches call `session.addCollection`
(`SchemaEmbedded:389`, `SchemaClassEmbedded:611`, `DatabaseImport:912`) → `AbstractStorage.addCollection`
(`:1655-1684`), which takes **`stateLock.writeLock()`** (`:1657`) then reaches an unarmed depth-0
freezer entrant (`calculateInsideAtomicOperation` → `startToApplyOperations` → `startOperation`). Under
a park-mode operator freeze it parks at `OperationsFreezer:47-48` **holding the storage write lock** →
every `stateLock.readLock` acquirer blocks → **full read+write outage**, not the "partial schema-read
outage" the ruling recorded.

**Corrected wording (supersedes the Q-B1 ruling sentence "legacy DDL can park under an operator freeze
holding schema/IM locks (partial schema-read outage)").** Legacy top-level DDL under a park-mode
operator freeze can park in the freezer **holding `stateLock.writeLock`** on the storage-level entry
points (`addCollection` and siblings) → **FULL read+write outage** (every `stateLock.readLock` acquirer
blocks), plus the smaller `executeInTxInternal` micro-tx family holding schema/IM locks. **Risk remains
ACCEPTED** — the legacy path is removed in an upcoming PR — but the accepted-risk record in the track
file and PR description must state the full-outage blast radius via `stateLock.writeLock`, and the
revisit trigger ("if the removal slips") inherits the corrected severity. No Track 7 code change is
demanded; the gate design stays legacy-agnostic so arming the legacy path later is additive (Q-B1
Option 1 unchanged).

### Suggestions folded

#### CS14 — release() underflow guard folded; retract-window spurious throw accepted as risk

- **(a) Underflow guard [FOLDED].** `release()` / `unfreezeWriteOperations(-1)`'s OPERATOR-kind
  decrement gains an underflow guard: the decrement asserts/logs-and-clamps at 0 so a double `release()`
  can never drive `operatorFreezeRequests` negative and silently disarm the gate (the exact outage
  Track 7 exists to prevent — a double `release(-1)` would leave `op == -1`, the next real operator
  freeze registering with `op == 0`, the gate disarmed). Floor-at-zero (or throw) on **both** the
  `freezeRequests` and `operatorFreezeRequests` decrements, plus a lockstep sanity assert, plus a test
  for the double-`release()` shape. This rides Q-B2's already-mandated `release()` → OPERATOR-decrement
  explicit mapping.
- **(b) Retract-window spurious throw [ACCEPTED AS RISK].** The kind-counter guarantee is **one-sided**:
  the pinned ordering (publish kind-before-count on arm; retract count-before-kind on release)
  guarantees only that an entrant observing an operator freeze's `freezeRequests` contribution also
  observes `operatorFreezeRequests > 0`. The converse is not guaranteed: during release there is a
  nanosecond window where `operatorFreezeRequests > 0` while the operator freeze's `freezeRequests`
  contribution is already retracted, so a schema-armed entrant (or the probe) racing the freeze-release
  instant can throw "operator freeze in progress" though only a transient (or nothing) remains.
  **Accepted:** rare, loud, retryable (clean rollback per the Q-B5 message). Recorded in the risk list
  below. The Q-B3/Q-B5 tests must NOT pin no-false-positives (the guarantee is one-sided by design).

#### Implementation notes (CN16, CN17, CN18, CS13 — folded, no Track 7 code change beyond the pins named)

- **CN16 — Dekker mark on `internalClose(true)` asymmetry.** Q-A4 sets `teardownIntent` at the top of
  every `internalClose(false)`; the pooled recycle path `internalClose(true)` runs a full teardown with
  no mark. Its only legitimate caller is the owner thread, so within the threat model the Dekker pair is
  never needed there; but since Q-A4 already clears the flag on every `reuse()`, set `teardownIntent`
  unconditionally at the top of `internalClose` (**both arms**) so "teardown marks before it tears" is
  literally unconditional (misuse-only exposure otherwise). This aligns with Q-A4 Option 2.
- **CN17 — engage-path self-release is a THIRD release site.** The Dekker self-release in engage step 5
  must also route through the `getAndSet(engagedOrdinal, 0)` claim (claim first, `releaseFor` only on a
  non-zero harvest), so all three releasers (owner finally, pool/foreign teardown, engage self-release)
  funnel through one atomic claim, making the documented "all releasers funnel through one atomic claim"
  discipline literally true. The `releaseFor` `(session, ordinal)` CAS is kept as the independent
  **second belt** (V3) — do not remove it; V2 shows single-release holds even in the both-act case via
  the per-engage `Holder` CAS.
- **CN18 — double-idle-teardown benign noise (enumerate as FM-A4c).** Pool `realClose` and the
  borrower's own `close()` can both pass `internalClose`'s plain-read one-shot guard and both run the
  full teardown of the same *idle* open transaction. The permit is safe (the `getAndSet` claim, A4b).
  Residuals are noise/bounded-nuisance on a discard path: concurrent `clear()` of the same HashMaps
  (CME noise, swallowed), and a `storageTxThreadId` check-then-zero race that can make the owner skip
  `resetTsMin()` (tsMin-floor pin + StaleTransactionMonitor noise until the owner's next tx). Enumerate
  as **FM-A4c** with an explicit accept; optionally serialize teardown entry with an atomic one-shot
  session claim (subsuming the plain-field status guard) if cheap at decomposition.
- **CS13 — post-pool-shutdown choreography still races the kept-alive zombie (in-memory only).** The
  skip's protection ends at the pool thread. After `DatabasePoolImpl.close` realCloses all sessions and
  `sessionCount` hits 0, the auto-close timer (`checkAndCloseStorages` → `forceDatabaseClose`) and
  instance close both run `SharedContext.close` **before** `storage.shutdown()`;
  `IndexManagerEmbedded.close()` clears the `indexes`/`classPropertyIndex` maps **without the IM lock**
  while a zombie may be mid-`publishReconciledIndexes` holding that lock — an unguarded post-durability
  publish throw → masked success + spurious tx-object rollback of a durable commit. **Durable state is
  safe** (`storage.shutdown()` serializes behind the zombie on `stateLock.writeLock` (P4); crash
  outcomes reduce to WAL atomic-unit completeness); the map race exists at HEAD independent of Track 7.
  **No Track 7 code change**; record one line in the track file: "the skip protects against the pool
  thread only; `SharedContext.close`/auto-close remain unsynchronized with a live commit — in-memory
  only, durable state serialized by `stateLock`." Optional courtesy note: `forceDatabaseClose` could
  take `ctx.close()` after `shutdown()`.

#### Mandatory implementation invariants carried from the verified-safe traces (V1, V2)

The pass-14 "no counterexample exists" verdicts depend on specific orderings; implementation MUST
preserve them with explicit comments, or the safety proofs lapse:

- **Arm-side (V1):** the operator arm increments `operatorFreezeRequests` **before** `freezeRequests`
  (publish kind-before-count) and runs the cut-and-unpark **strictly after both** increments.
- **Entrant-side (V1):** the entrant's park-decision kind check reads the counters **strictly after**
  `addThreadInWaitingList` returns (enqueue-before-recheck); both counters are atomics. These two close
  the pass-13 C1 engage-during-enqueue race (the tail-CAS/cut-read dichotomy has no third case).
- **Draft A engage (V2):** the ordinal store (engage step 4) is **strictly before** the teardown-intent
  mark read (engage step 5) — ordinal-store-before-mark-read — so the Dekker pair is formally sound
  (if the teardown's `getAndSet` returns 0, the engager sees the mark and self-releases; otherwise the
  teardown harvests the ordinal; the both-act case resolves to one `permit.release()` via the per-engage
  `Holder` CAS, no ABA).
- **Release-side (V8, one-sided by design):** retract count-before-kind — accepted per CS14(b).

### Amended risk list (accepted risks on record after pass 14)

1. **Q-B1 legacy full-outage (CN15):** legacy top-level DDL under a park-mode operator freeze parks
   holding `stateLock.writeLock` → full read+write outage. Accepted; evaporates when the legacy path is
   removed (upcoming PR); revisit if removal slips.
2. **Kind-counter retract-window spurious throw (CS14b):** a schema commit/probe racing the
   operator-freeze-release instant may throw though the operator freeze just released. Rare, loud,
   retryable. Accepted.
3. **FM-A4c double-idle-teardown noise (CN18):** CME noise + possible skipped `resetTsMin` on concurrent
   idle teardown. Bounded nuisance on a discard path. Accepted (optional one-shot session claim).
4. **CS13 post-pool choreography (in-memory):** `SharedContext.close`/auto-close race a kept-alive
   zombie's in-memory map publish; durable state safe. Accepted; recorded, no Track 7 code change.
5. **Q-A2 residual TOCTOU (CN13, post-volatile):** late-skip = commit already finished; late-rollback =
   today's behavior. Accepted (the only residual once the fields are volatile).

Adversarial pass 14 triage complete: CN10/CS10 reversed (design amended above); CN11–CN15, CS11, CS12
strengthened into the skip protocol and widened-finally pins; CS14 guard folded, retract-window throw
accepted as risk; CN16-18/CS13 folded as implementation notes. Refreshed user design-review verdict and
a scoped adversarial round 2 (amended parts only) are required before decomposition.

### Design review verdict (refreshed after pass-14 amendments)

Design review (amended design, §Amendments applied): user-approved — 2026-07-21. Scoped adversarial round 2 (pass 15, amended parts only) dispatched per the track workflow; decomposition remains blocked until round 2 completes and its findings are triaged.

---

## Amendments round 2 — adversarial pass 15 triage (2026-07-21)

Adversarial round 2 (pass 15) attacked ONLY the pass-14 amendments and their splice points.
Reports: `adversarial-pass15-concurrency.md` (CN19–CN24, hypothesis log, verified-safe
V-15.1–V-15.5) and `adversarial-pass15-durability.md` (CS15–CS18, incl. their "Fix bound"
subsections). User-approved triage: **two blockers reversed** (CN19 re-amends the CN10 third
checkpoint; CS15+CN21 merged re-amends the CS10 defensive clear), **three should-fix clusters
strengthened** into the skip protocol (CN20+CS17 merged; CN22; CS16), **two suggestion clusters
folded** as implementation pins (CN23+CS18 merged; CN24), and **one pre-existing residual risk
(quiesce theft) added** to the risk list. Every finding is addressed by ID below; each
amendment-of-an-amendment names the pass-14 text it supersedes.

### Reversed (re-amendments of pass-14 amendments)

#### CN19 [BLOCKER, REVERSED] — the third checkpoint uses a NEW abort-predicate lock primitive, not a `tryLockNanos` retry loop

**What CN19 found.** `ScalableRWLock.exclusiveTryLockNanos` (`:586-616`) releases the write bit on
every drain timeout (`:611`), so a retry loop forfeits writer admission between attempts: under
sustained reader traffic whose critical sections exceed one polling interval T (ordinary data
commits qualify — unbounded transaction size), the armed schema commit retries indefinitely while
holding `SchemaShared.lock` + IM lock — an **unbounded schema-read outage on the NO-freeze path**,
with general read throughput throttled to slip-in trickle. The two requirements are mutually
unsatisfiable with that primitive: T must be small (the amendment's own one-interval outage bound)
and T must exceed the longest reader residence (liveness) — no constant T does both. ("Reuse the
freezer gate's polling cadence" was also a dangling reference — the freezer gate is park-based, it
has no cadence.)

**Amended design (supersedes the pass-14 CN10 amendment's "timed-acquire loop using the existing
`ScalableRWLock.exclusiveTryLockNanos (:586)` … Loop bound/interval … reuse the freezer gate's
polling cadence" text, and the CN10 §"Snapshot-pin safety" cross-reference's "timed loop"
phrasing).** The third checkpoint acquires `stateLock.writeLock` **once** through a new
`ScalableRWLock` primitive that carries an abort predicate, replacing the plain
`exclusiveLock()`/`.writeLock().lock()` at `commitSchemaCarry:3179`:

- **Signature sketch.** `boolean exclusiveLockWithAbort(BooleanSupplier abort, long pollNanos)`
  (or equivalent). Phase 1 loops `stampedLock.tryWriteLock(pollNanos)` checking `abort` between
  attempts — while queued the writer blocks no readers (readers poll `isWriteLocked()`, they never
  enqueue against the stamped writer queue, `:320-357`). Phase 2 acquires the write bit **ONCE**
  and holds it (new readers refused, writer-preference, exactly like `exclusiveLock`), then polls
  `abort` inside the existing reader-drain spin (`:604-609`). This restores single-acquisition
  admission control — bounded acquisition = max residual reader residence, deterministic — while
  freeze-abort latency stays one poll granularity.
- **Abort predicate.** `operatorFreezeRequests > 0`, evaluated inside the drain loop. On predicate
  true: **release the write bit fully** (no residual writer-intent state — no queue entry, no held
  bit; the primitive is reusable) and return false; the caller then throws the shared gate-factory
  `ModificationOperationProhibitedException`.
- **Poll cadence.** Poll the abort predicate on each drain-spin yield iteration (or a small bounded
  batch of iterations — align with the CN19 fix direction; the exact granularity is pinned at
  decomposition). This bounds freeze-abort latency to one poll granularity without a shared
  phase-1/phase-2 time budget.
- **Guarantee pair restored.** (i) bounded acquisition under sustained reads (writer preference,
  single bit-acquisition — no inter-attempt release window, so no slip-in storm and no unbounded
  retry); (ii) freeze abort within one poll granularity (the bit is released before the throw, no
  reader stranded).
- **Interrupt handling (carried from Q-A3 pin (3)).** An `InterruptedException` from the stamped
  timed acquire restores the interrupt flag and throws `DatabaseException` naming the holder/state;
  the amendment previously left this unspecified.
- **Data commits unchanged.** Data commits' `stateLock.readLock` acquisition (`:2535`) is
  byte-for-byte unchanged; only the armed schema writer's storage-lock acquisition becomes
  freeze-aware.

**Four-checkpoint invariant statement stands** (from the pass-14 CN10 amendment), with **checkpoint
(2) now defined as this abort-predicate single-acquisition** (not a timed retry loop). The unwind
proof is unchanged: on abort the write bit was never durably held past the throw, `SchemaShared.lock`
+ IM lock unwind through `commitSchemaCarry`'s finallys (`:3196-3199`), and no data commit's readLock
is queued behind a writer that gave up (a failed single-acquisition dequeues the writer). Verified-safe
V-15.5 (no new deadlock edge) applies to this primitive as it did to the loop.

#### CS15 + CN21 [BLOCKER, REVERSED, MERGED] — SINGLE-OWNER CLEAR replaces the "defensive paired clear"

**What CS15/CN21 found.** The pass-14 "defensive paired clear" (a try/finally in `commit()`
spanning the pin→`applyCommitOperations`-finally region) **cannot express the dynamic span it
requires** and double-clears against the established `applyCommitOperations:3142` finally on every
failed commit: `:3142` clears the pin (1→0) on **every** exit including the common failure paths
(version-conflict `ConcurrentModificationException` in `commitEntry`, validation failure, index-commit
failure — both call sites `:2537`/`:3187` are inside the guarded region), then the wrapper clears
again (0→−1), driving `MetadataDefault.immutableCount` negative and poisoning the pooled/recycled
session (DDL throws `IllegalStateException`, `rebuildThreadLocalSchemaSnapshot` fails at count 0 on
the next class-creating commit, `getImmutableSchemaSnapshot` churns a fresh snapshot per read) — the
exact CS10 failure class, now on the **common** failure path. The two pass-14 sections also named
**contradictory span endpoints** (CN10 §"Snapshot-pin safety": "the entire pin→lock-acquired region";
CS10: "through the point where `applyCommitOperations`' own finally takes over").

**Amended design (supersedes the pass-14 CS10 amendment's "Defensive paired clear" paragraph AND the
CN10 amendment's §"Snapshot-pin safety" span-wording; resolves the CN10/CS10 span-endpoint
contradiction explicitly by removing the span entirely).** **`commit()` owns the pin/clear pairing as
a single owner:**

- The pin at `AbstractStorage:2525` (`makeThreadLocalSchemaSnapshot()`) **stays**.
- The **sole clear moves into `commit()`'s own outermost finally**, paired lexically with the `:2525`
  pin.
- The established `applyCommitOperations:3142` clear is **DELETED**. `applyCommitOperations` has
  exactly two callers, both inside `commit()` (`:2537` data branch, `:3187` via `commitSchemaCarry`),
  so ownership moves cleanly with no handoff and no path that loses the clear.
- **No armed flag** is needed in the primary design (single owner, one clear, one pin — nothing to
  arm/disarm). *Rejected alternative:* an armed-flag guard (boolean set after the pin, disarmed as the
  first statement inside `applyCommitOperations`' try). Rejected because it reintroduces a
  disarm-ownership question (a frame-entry gap where neither clear runs) and is strictly more moving
  parts than single-owner for the same guarantee.

**All escape paths funnel through `commit()`'s finally exactly once:**
- **Probe throw** — occurs **before** the `:2525` pin (per the CS10 hoist, unchanged), so no pin was
  taken and no clear is needed; the count is balanced because the probe never incremented it.
- **Third-checkpoint abort throw** (CN19) — occurs after the pin, inside `commitSchemaCarry`; unwinds
  into `commit()`'s finally → one clear.
- **Any `applyCommitOperations` exception** including ordinary version-conflict aborts and validation
  failures — no longer self-clears (the `:3142` clear is gone); unwinds into `commit()`'s finally →
  one clear.
- **`ensureThatComponentsUnlocked`-throws shape** (the residual a disarm-at-entry guard would have
  missed) — also covered, because the single clear lives in `commit()`, above that frame.
- **Success** — one clear in `commit()`'s finally.

The pin's slightly longer hold (through the lock-release finallys and promotion) is inert: the pin is
session-local and promotion runs before the `:3141` component-unlock finally.

**Amended test matrix (supersedes the pass-14 CS10 test-matrix "adds a third case" text).**
`immutableCount` balance (returns to its pre-commit value) is asserted after each of: (a) probe
rejection; (b) third-checkpoint abort; (c) failed data commit (version conflict in `commitEntry`);
(d) failed schema-carry commit (in-apply throw); (e) successful commit — **each verified on a pooled
session that is then recycled and re-borrowed** (the recycled borrower must see a healthy snapshot
and be able to run DDL). The pass-14 matrix tested only the probe path, which was already safe; cases
(c)/(d) are the ones that caught CS15.

### Strengthened (skip-protocol re-pins)

#### CN20 + CS17 [SHOULD-FIX, MERGED] — STRIKE "decrement the session count" from the skip whitelist

**What CN20/CS17 found.** The pass-14 skip whitelist is self-contradictory: its DO list says
"decrement the session count", but the **only** decrement mechanism is
`AbstractStorage.close(session)` (`:630-640`, throws `StorageException` on a negative count), which
the DON'T list forbids; and the owner's later **full** `internalClose` decrements again via
`storage.close(this)` (`:3290`). The double-decrement is deterministic on the amendment's intended
main sequence: last-session case → negative count → `StorageException` thrown from inside the owner's
completer finally (feeding CS16's masking hole); multi-session case → premature `sessionCount == 0` →
`forceDatabaseClose` runs `SharedContext.close` under a live non-zombie session (outside CS13's
accepted scope). (`ResourcePool` has no per-session count — verified whole file.)

**Amended design (supersedes the pass-14 skip-protocol (1) whitelist clause "decrement the session
count / remove its own thread-local activation bookkeeping").** The skip whitelist becomes exactly:
- set `teardownIntent`;
- remove pool-thread-**PRIVATE** activation bookkeeping (the ThreadLocal `activeSession` only —
  nothing touching `AbstractStorage.sessionCount`);
- log.

The owner's completer performs the **SOLE** session-count decrement via its full `internalClose` →
`storage.close(this)` (exactly once, on the owning thread). The count-to-zero transition is thereby
**deferred until the owner completes**, which is the safe choreography (the auto-close timer cannot
fire mid-commit for the skip path).

**Updated CS13 residual wording (supersedes the pass-14 CS13 implementation-note/risk-list line's
"after `DatabasePoolImpl.close` realCloses all sessions and `sessionCount` hits 0, the auto-close
timer …").** Because `sessionCount` now reaches 0 only when the owner's completer runs its full
`internalClose`, the skip path **defers** the count-to-zero transition until the zombie completes;
the `SharedContext.close`/auto-close race against a live commit therefore remains reachable only via
non-skip close choreographies (genuine all-sessions-closed), not via the skip path. Risk-list entry 4
is restated accordingly below.

#### CN22 [SHOULD-FIX, STRENGTHENED] — the completer handshake gains a Dekker-style re-check

**What CN22 found.** The owner-as-completer handshake is one-sided: the owner's completer finally can
read `teardownIntent == false` before the pool thread writes it, so in the "owner fast" interleaving
**neither** actor runs the deferred teardown — the pass-14 invariant (2) "whose own teardown
(guaranteed to run …)" overclaims, and the owner-never-returns/abandoned-borrower corner is unstated.

**Amended design (supersedes the pass-14 skip-protocol (2) sentence "On commit completion (success or
failure), a finally in the owner's commit path checks `teardownIntent`…" — adds the Dekker re-check
and the pinned ordering).** Mirror the V2 Dekker discipline:
- **POOL thread:** write `teardownIntent` (volatile) **FIRST**, then **re-validate** the skip
  condition (`status == COMMITTING`). If the re-check shows the commit already finished, the pool does
  **NOT** skip — it falls through to the normal **full teardown** path itself.
- **OWNER:** publish commit-completion state **FIRST**, then read `teardownIntent` in the completer
  finally.

**Three interleavings and outcomes:**
- **(a) pool marks before owner reads** → owner observes the mark → owner completes the full teardown.
- **(b) commit finishes before the pool's re-check** → the pool's re-check sees `status != COMMITTING`
  → the pool runs the full teardown itself.
- **(c) both act** (mark seen by owner AND pool re-check still `COMMITTING`) → benign FM-A4c-class
  overlap; the permit is safe via the `getAndSet(engagedOrdinal, 0)` claim (one harvester) and the
  `internalClose` one-shot guard (one full teardown).

**Restored invariant (supersedes the pass-14 invariant (2) phrase "whose own teardown (guaranteed to
run, since the skip does not consume the one-shot guard)").** *At least one completer always runs:*
the Dekker store-then-load pair guarantees at least one side (owner or pool) observes the other's
state and runs the full teardown; "guaranteed" is now justified by the handshake rather than asserted.
The only defeater is JVM death mid-completer (the accepted FM-A5 loud-wedge class, reported by the
StaleTransactionMonitor); an owner parked forever on a leaked transient-freeze release likewise
collapses to FM-A5, not a silent strand.

#### CS16 [SHOULD-FIX, STRENGTHENED] — completer placement pinned after `tx.close()`, throw-isolated

**What CS16 found.** The owner-run `internalClose` in the commit-path finally is **not** throw-isolated
(unlike the pool-close loop), and its placement vs `tx.close()` is unpinned: a teardown throw
(`callOnCloseListeners`, `localCache.shutdown`, or CS17's negative-count `StorageException`) **masks the
commit outcome** — the sharp arm reports a **durable** commit as failed → client retry → **duplicate
durable apply**; a wrong placement (before `tx.close()`) fires rollback listeners on a committed tx.

**Amended design (adds two pins to the pass-14 skip-protocol (2) completer).**
- **Placement:** the completer finally runs **STRICTLY AFTER the transaction's own `tx.close()`**
  (both the commit and rollback paths). "Completion" is defined as the tx-close boundary, not the
  storage-commit boundary. The mutex is released by `tx.close()`'s own finally FIRST; the completer's
  `internalClose` release pass is then a no-op via the `getAndSet` claim. (Post-`tx.close()`,
  `rollback()` no-ops because the tx is no longer active — P7 — so no rollback listeners fire on a
  durable commit.)
- **Throw isolation:** the completer's `internalClose` is wrapped in `try/catch (Throwable)`
  log-and-continue — matching the pool-loop isolation discipline — so a teardown throw can **NEVER**
  mask the commit outcome (neither the success return nor the original commit exception; optionally
  `addSuppressed` onto an in-flight failure).
- **Test:** durable commit + `teardownIntent` set + a throwing close listener → the commit call returns
  success (or the original failure), never the teardown throwable.

### Folded (implementation pins)

#### CN23 + CS18 [SUGGESTION, MERGED] — underflow-guard implementation pins + one new residual risk

Folded onto the pass-14 CS14(a) underflow guard:
- **CAS-floor, not decrement-then-set.** The floor-at-zero decrement MUST be a single atomic RMW —
  a decrement-only-if-positive CAS loop (`accumulateAndGet(x -> max(0, x-1))` or equivalent) — never
  `decrement-then-if-negative-set(0)`. The decrement-then-correct shape has a lost-update race: a
  buggy double-release drives −1, a legitimate concurrent `freeze()` increments −1→0, the corrective
  `set(0)` then **wipes the live freeze's registration**. The CAS-floor leaves the counter at 1 and
  the freeze intact. The clamp's result must feed the existing `requests == 0` cut decision
  (`OperationsFreezer:95-110`); concurrent cutters are benign (pass-14 V1).
- **No throw from release-finally-reachable decrements.** Decrements reachable from transient-release
  finallys (`doSynch`'s finally `:5381`, the backup transients' finallys) must **log, not throw** — a
  throw there would mask the frozen body's primary exception (e.g. a `flushAllData` failure operators
  must see), the same finally-masking class as CS16. The "(or throw)" option, if used at all, is
  confined to the operator `release()` API surface.
- **Lockstep sanity assert.** Either dropped, or made tolerant of the design's own legal transient
  `op > fr` windows (per the V1 arm order `op++`→`fr++` and the V8 retract order `fr--`→`op--`); a
  non-atomic cross-thread lockstep assert fires spuriously on a healthy system. If kept, restrict to a
  single-threaded test harness or a ±1 tolerance under an external quiescence guarantee.
- **New risk-list line — "quiesce theft" (pre-existing at HEAD, not introduced by Track 7).** The
  identity-less counters allow a double-`release()` racing another registered freeze to silently
  retract that freeze's protection while its frozen body runs (operator + transient both active,
  fr=2: double release drives fr 2→1→0 with no negative value, so no floor fires and the lockstep
  assert holds, yet the transient's quiesce is voided → writes admitted mid-`doSynch`/mid-backup →
  torn synch / corrupt backup). Documented residual, **no Track 7 code change** beyond the guard; if
  ever revisited, a per-id release ledger (the `freezeIdGen` id already exists; `release()`'s `-1`
  sentinel is the only anonymous caller) is the fix.

#### CN24 [SUGGESTION, FOLDED] — `teardownIntent` mark placement pinned AFTER the one-shot guard

**What CN24 found.** If the unconditional mark is set before `internalClose`'s one-shot guard, a no-op
close on a non-OPEN session (or one racing `reuse()`) plants a **stale mark** that survives into a
later borrow; that borrower's first engage reads the stale mark at Dekker step 5, self-aborts and
throws "session was closed while engaging the schema mutex", and since nothing clears it mid-borrow,
**every** engage on that borrow fails — a healthy session rendered DDL-dead. This false-positive
channel does not exist at HEAD.

**Amended design (supersedes the pass-14 CN16 implementation-note placement "set `teardownIntent`
unconditionally at the top of `internalClose` (both arms)").** The unconditional mark is set **AFTER
`internalClose`'s one-shot guard passes** (equivalently, cleared on the no-op early return), in both
arms, so a guard-returning no-op close never plants a stale mark ("every teardown that *tears* marks
first" — a guard-return tears nothing and must not mark). Q-A4's `reuse()`-clear stays as the
independent second belt.

### Amended risk list (superseding the pass-14 risk list; adds quiesce theft)

1. **Q-B1 legacy full-outage (CN15):** legacy top-level DDL under a park-mode operator freeze parks
   holding `stateLock.writeLock` → full read+write outage. Accepted; evaporates when the legacy path
   is removed (upcoming PR); revisit if removal slips.
2. **Kind-counter retract-window spurious throw (CS14b):** a schema commit/probe/third-checkpoint
   racing the operator-freeze-release instant may throw though the operator freeze just released.
   Rare, loud, retryable. Accepted; the guarantee is one-sided by design (tests must not pin
   no-false-positives).
3. **FM-A4c double-idle-teardown noise (CN18):** CME noise + possible skipped `resetTsMin` on
   concurrent idle teardown. Bounded nuisance on a discard path. Accepted.
4. **CS13 post-pool choreography (in-memory), narrowed by CN20+CS17:** `SharedContext.close`/auto-close
   can race a live commit's in-memory map publish only via **non-skip** close choreographies (genuine
   all-sessions-closed); the skip path now defers the `sessionCount`-to-zero transition until the owner
   completes, so it no longer triggers this window. Durable state safe (serialized by `stateLock`).
   Accepted; recorded; no Track 7 code change.
5. **Q-A2 residual TOCTOU (CN13, post-volatile):** late-skip = commit already finished; late-rollback =
   today's behavior. Accepted (the only residual once `status`/`storageTxThreadId` are volatile).
6. **Quiesce theft (CN23+CS18, PRE-EXISTING at HEAD):** identity-less freezer counters let a double
   `release()` racing another registered freeze silently void that freeze's quiesce while its frozen
   body runs (torn synch / corrupt backup). Not introduced by Track 7; the CAS-floor underflow guard
   does not detect it (no value goes negative). Documented residual; no Track 7 code change beyond the
   guard; per-id release ledger is the eventual fix if revisited.

Adversarial pass 15 triage complete: CN19 and CS15(+CN21) reversed (re-amended above); CN20/CS17,
CN22, CS16 strengthened into the skip protocol; CN23/CS18 and CN24 folded as implementation pins; one
pre-existing residual risk (quiesce theft) added to the risk list. A refreshed user design-review
verdict and a micro adversarial round 3 — scoped SOLELY to the CN19 abort-predicate lock primitive and
the CS15 single-owner clear — are required before decomposition.

### Design review verdict (refreshed after pass-15 round-2 amendments)

Design review (round-2 amendments, §Amendments round 2 applied): user-approved — 2026-07-21. Micro adversarial round 3 (pass 16) dispatched, scoped solely to the CN19 abort-predicate lock primitive and the CS15 single-owner clear; decomposition remains blocked until pass 16 completes and is triaged.

### Pass-16 triage and adversarial review closure (2026-07-21)

Micro adversarial round 3 (pass 16, report: adversarial-pass16-micro.md) attacked solely the CN19 abort-predicate primitive and the CS15 single-owner clear. Verdict: no blockers, no reversals; nine null verdicts confirmed both mechanisms sound. Two findings, both user-triaged STRENGTHEN (pins, no design change):

- **CS19 [should-fix, PINNED]** — supersedes the round-2 CS15+CN21 wording 'the sole clear moves into commit()'s own outermost finally': the clear lives in a NESTED try/finally opened immediately after the :2525 pin (lexically paired with it), NOT the method's literal outermost try (which opens at :2514, before the pin and the hoisted probe — a literal outermost finally would clear on every pre-pin throw, including probe rejections, driving immutableCount to −1). This placement also fixes a pre-existing latent pin leak on throws from getSortedIndexOperations/getAtomicOperation (:2527-2528). Test-matrix case (a) (probe-rejection balance on a recycled pooled session) is the enforcement net for this pin.
- **CN25 [suggestion, PINNED]** — exclusiveLockWithAbort gains a predicate re-check immediately after bit acquisition / drain completion, before returning true, closing the acquisition-success-edge miss window (a freeze arriving exactly then would otherwise be caught only at checkpoints (3)/(4) with all locks held). Guarantee (ii) is reworded to 'freeze abort within one poll granularity while acquisition is in progress'; the four-checkpoint invariant backstops the residual edge either way. Related wording note: the pass-14 sentence 'the first three checkpoints throw with the write lock not yet held' is inexact for the (3)-catches-it edge case; checkpoint semantics unchanged.

**Adversarial review closed.** Three rounds run (pass 14 full, pass 15 scoped, pass 16 micro); final round produced no reversals.

Adversarial review: passed, 6 accepted risks — 2026-07-21

(The 6 accepted risks are the Amended risk list of §Amendments round 2.) Decomposition into track-7.md is UNBLOCKED and proceeds next; the CS19/CN25 pins are binding inputs to it alongside all prior rulings, amendments, and the V1/V2/V8 mandatory orderings.
