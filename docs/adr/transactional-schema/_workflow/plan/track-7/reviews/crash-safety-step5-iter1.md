# Crash-safety / durability review — Track 7 Step 5, iteration 1

**Artifact:** commit `736cab68ec` — "Gate schema commits against operator freezes" (branch
`transactional-schema`; working tree at `e34daacf8c` = artifact + docs, product code identical —
verified `git diff 736cab68ec HEAD -- core/src/main core/src/test` is empty for product files).
**Spec:** `docs/adr/transactional-schema/_workflow/track-7-design-drafts.md`, Draft B as amended
(pass-14 CN10/CS10 reversals, pass-15 CN19/CS15+CN21 reversals, pass-16 CS19/CN25 pins), with the
CS10→CS15→CS19 single-owner-clear chain and the CS14/CN23/CS18 counter-guard pins as the primary
verification targets.
**Perspective:** crash-safety / durability (project CS charter). Read-only review; no Maven run.
**Line numbers** cite the working tree (identical to the artifact for all in-scope files).

## Verdict summary

**No blockers. No should-fixes. Three suggestions (CS29–CS31).** The single-owner snapshot clear
is implemented exactly per the CS19 pin (nested try/finally opened immediately after the pin;
probe throws pre-pin need and get no clear; the old `applyCommitOperations` clear is deleted and
replaced by an explanatory comment); every enumerated escape path clears exactly once. All four
checkpoint throw sites fire with zero WAL / atomic-operation-table / durable state created by the
aborted commit attempt, and the two in-freezer checkpoints observe the same count-rebalanced,
depth-untouched discipline as the pre-existing throw-supplier path. Freeze/release bookkeeping
matches the V1/V8 orderings and the CN23/CS18 CAS-floor + log-not-throw pins at all four
registration sites and both release shapes. Crash-point enumeration finds no state written by an
aborted schema commit that could survive to disk, and the freezer cut/unpark touches heap only.
The Q-B5 message is a stable, storage-named contract distinct from the legacy supplier and is
pinned by tests on both sides (gate wording present on gate paths, absent on the legacy path).

---

## Decision criteria

A finding is a **blocker** if a crash (or an ordinary failure path) can leave durable state that
does not read as "commit never happened" for an aborted commit, can mask a durable commit as
failed (duplicate-apply risk), can strand the pin/clear or freeze counters in a way that silently
disarms a durability-relevant guard, or lets a finally-path throw mask a frozen body's primary
exception. **Should-fix**: a defensible but fragile shape whose failure requires a second bug or
an unlikely-but-reachable interleaving. **Suggestion**: hygiene, dead code, documentation, or
latent shapes unreachable without new callers.

## Premises (verified by reading, cited)

- **P1** — the entry probe sits at `AbstractStorage:2532-2534`, strictly BEFORE the pin at
  `:2536`; both sit inside `commit()`'s outer try (`:2516`), whose catch arms only rethrow via
  `logAndPrepareForRethrow` and contain no clear.
- **P2** — the nested try opens at `:2547` (first statement after the pin) and its finally
  (`:2575-2577`) performs the sole `clearThreadLocalSchemaSnapshot()` call on the commit path.
  The comment block `:2537-2546` states the CS19 rationale verbatim (deliberately NOT the
  method's outermost try; covers the previously-leaking `getSortedIndexOperations` /
  `getAtomicOperation` throws at `:2548-2549`).
- **P3** — `applyCommitOperations`' outermost finally (`:3236-3241`) now contains ONLY
  `ensureThatComponentsUnlocked` plus a comment documenting the deleted clear ("a second clear
  here would drive the pin count negative on every failed commit"). Grep over `core/src/main`
  finds exactly two `clearThreadLocalSchemaSnapshot` mentions in `AbstractStorage` — the `:2576`
  clear and that comment.
- **P4** — `applyCommitOperations` has exactly two callers, both lexically inside `commit()`'s
  nested try: `:2557` (data branch, under `stateLock.readLock`) and `:3292` (via
  `commitSchemaCarry`, called at `:2552`). `commitSchemaCarry` itself has exactly one caller
  (`:2552`). Verified by grep; no other entry into the pin/clear window exists.
- **P5** — checkpoint (2) is the abort-predicate acquisition at `:3281-3284`
  (`stateLock.exclusiveLockWithAbort(this::isOperatorFreezeActive, OPERATOR_FREEZE_ABORT_POLL_NANOS)`),
  placed after `committedSchema.acquireSchemaWriteLock` (`:3273`) and
  `indexManager.acquireExclusiveLockForCommit` (`:3275`), before `enterCommitWindow` (`:3290`).
  Its throw site holds SchemaShared(write) + IM exclusive, NOT the storage write lock.
- **P6** — checkpoints (3)/(4) live in `OperationsFreezer.startOperation(boolean, Supplier)`:
  loop-top gate `:105-107` (after `operationsCount.decrement()` `:100`, before
  `throwFreezeExceptionIfNeeded()` `:109`), park-decision gate `:118-120` (after
  `addThreadInWaitingList` `:113`, before `LockSupport.park` `:123`); the depth increment is at
  `:132`, below the loop. The legacy throw-supplier discipline being mirrored is `:100` →
  `:109` → throw (count re-balanced, depth untouched) — identical placement class.
- **P7** — `startTxCommit(atomicOperation, schemaContext != null)` (`:2789`) precedes the
  rollback-paired inner try; the WAL-side sequencing inside
  `AtomicOperationsManager.startToApplyOperations` (`:140-165`) is freezer entry FIRST, then
  `segmentLock.exclusiveLock` → `idGen.nextId()` → `atomicOperationsTable.startOperation` →
  `atomicOperation.startToApplyOperations(commitTs)`. A freezer throw therefore precedes any
  table registration and any commitTs assignment.
- **P8** — the four registration sites carry kinds: `AbstractStorage.freeze` both arms OPERATOR
  (`:5797-5804`); `doSynch` TRANSIENT (`:5620-5621`, release in finally `:5653`);
  `DiskStorage.copyWALToBackup` TRANSIENT (`:361`, release in finally `:368`);
  `DiskStorage.storeBackupDataToStream` TRANSIENT (`:1256`, release in finally `:1270`).
  `release()` calls `unfreezeWriteOperations(-1)` (`:5850`) and `releaseOperations` maps
  `id == -1` explicitly to `FreezeKind.OPERATOR` (`OperationsFreezer:232-235`).
- **P9** — both decrements are `decrementToFloor` (`OperationsFreezer:248`, `:259`), a
  CAS-loop `decrement-only-if-positive` RMW (`:292-302`) returning −1 on an underflow attempt
  with the counter left untouched; underflow is logged via `LogManager.error` (`:249-256`,
  `:259-267`), never thrown; the clamp's return value feeds the `requests == 0` cut decision
  (`:270`). No lockstep cross-counter assert exists (documented at `:225-228`).
- **P10** — V1 arm ordering: `operatorFreezeRequests.incrementAndGet()` (`:165`) →
  `operatorFreezeIds.add(id)` (`:166`) → `freezeRequests.incrementAndGet()` (`:169`) →
  cut-and-unpark (`:191-195`) strictly after both. V8 retract ordering: `freezeRequests` first
  (`:248`), kind counter second (`:259`).
- **P11** — `ModificationOperationProhibitedException` implements `HighLevelException`
  (`ModificationOperationProhibitedException.java:31-32`); `commit()`'s
  `catch (RuntimeException)` binds the `logAndPrepareForRethrow(RuntimeException)` overload
  (`AbstractStorage:7973-7991`), which returns the SAME instance and never calls `setInError`
  for any RuntimeException — the gate abort neither wraps nor poisons the storage.
- **P12** — `MetadataDefault.immutableCount` is a plain session-confined int;
  `makeThreadLocalSchemaSnapshot` builds a snapshot only on the 0→1 edge and always increments
  (`:78-84`); `clearThreadLocalSchemaSnapshot` always decrements and nulls on the 1→0 edge
  (`:88-93`); the new accessor `getThreadLocalSchemaSnapshotPinCount()` (`:102-104`) is a pure
  read, production-unused (grep: only `FreezerGateTest` calls it).
- **P13** — Step-1's endTxCommit gating is untouched: the commit's diff to
  `AbstractStorage` (161 changed lines) contains no hunk in `endTxCommit` (`:6535`), the
  `atomicOperation.isRollbackInProgress()` discrimination (`:3140-3170` region), or the two
  test hooks (`commitWindowTestHook` `:4076`, `postEngineBuildTestHook` `:4097`) beyond the
  one-line `startTxCommit` call-site change at `:2789`.

---

## 1. Single-owner snapshot clear (CS10/CS15/CS19 chain)

### 1.1 Placement — exact per the CS19 pin

Per P1/P2 the nested try opens immediately after the pin, not at the method's outer try. A probe
throw at `:2533` never enters the nested try → zero clears, and zero pins were taken → balanced.
An outermost-finally placement (the shape CS19 forbids) would have cleared on the probe throw and
driven the count to −1; the implemented shape cannot. The comment even documents the CS19
side-benefit: the previously latent leak on `getSortedIndexOperations`/`getAtomicOperation`
throws (formerly between the pin and the old `:3142`-era clear) is now covered.

### 1.2 Old clear deleted; no other callers

Per P3 the old clear site is gone (comment-only). Per P4, `applyCommitOperations` has exactly the
two in-`commit()` callers, so clear ownership moved with no orphan path. The other
`clearThreadLocalSchemaSnapshot` sites in the codebase (`DatabaseSessionEmbedded:2403` — the
`executeReadRecord` pin/clear pair at `:2226`, balanced within one method) are pre-existing,
untouched, and nest correctly around/within the commit pair because the count is a depth counter
(P12). (`EntityImpl:4178` makes an unpaired pin on a deserialization edge path — pre-existing,
outside this commit's blast radius; noted, not a Step-5 finding.)

### 1.3 Escape-path enumeration — exactly one clear each

| # | Escape path | Pin taken? | Clears | Trace |
|---|---|---|---|---|
| E1 | Probe throw (`:2533`) | no | 0 | pre-pin, pre-nested-try; outer catch rethrows (P11, no wrap) |
| E2 | `getSortedIndexOperations` / `getAtomicOperation` throw (`:2548-2549`) | yes | 1 | inside nested try → `:2576` |
| E3 | `acquireSchemaWriteLock` / `acquireExclusiveLockForCommit` throw (`:3273/:3275`) | yes | 1 | unwinds enclosing finallys already entered → `:2576` |
| E4 | Checkpoint-2 abort throw (`:3283`) | yes | 1 | IM finally (`:3301`) → schema finally (`:3304`) → `:2576`; write lock NOT held (P5), nothing published, no WAL state (§2) |
| E5 | `exclusiveLockWithAbort` interrupt → `DatabaseException` (`ScalableRWLock:699-720`) | yes | 1 | same unwind as E4; write bit not acquired (phase-1 throw) |
| E6 | Checkpoint-3/4 gate throw (freezer, `OperationsFreezer:106/:119`) | yes | 1 | `applyCommitOperations` outer finally → `ensureThatComponentsUnlocked` (no-op: no components locked yet, `releaseLocks` is guarded/idempotent, `AtomicOperationsManager:491-530`) → `exitCommitWindow` → writeLock unlock → IM → schema → `:2576` |
| E7 | Any in-apply exception incl. version-conflict `ConcurrentModificationException` from `commitEntry`, validation, `commitIndexes` | yes | 1 | error-arm rollback + registry undo run inside `applyCommitOperations`; rethrow unwinds as E6 → `:2576` |
| E8 | `endTxCommit` failure, shape (1) rollback-in-progress | yes | 1 | registry undo, rethrow → `:2576` |
| E9 | `endTxCommit` failure, shape (2) in-doubt (`setInError`, publication standing) | yes | 1 | rethrow → `:2576`; the clear is session-local and independent of the error state |
| E10 | Promotion failure (logged, storage in error, commit SUCCEEDS) | yes | 1 | normal return → `:2576` |
| E11 | `ensureThatComponentsUnlocked` itself throws | yes | 1 | still inside nested try → `:2576` |
| E12 | Data-branch `stateLock.readLock().lock()` or apply failure | yes | 1 | read-lock finally (`:2560-2562`) → `:2576` |
| E13 | Success (both branches) | yes | 1 | `return result` routes through the nested finally |

The clear itself (`MetadataDefault:88-93`) is a plain decrement and cannot throw, so no escape
path can skip it once the nested try is entered, and none can run it twice (single lexical site,
single frame). `rebuildThreadLocalSchemaSnapshot` (`:2933` region) mutates the snapshot in place
and preserves the count (P12) — no interaction with the pairing.

### 1.4 `immutableCount` semantics under the new accessor

The accessor (P12) is read-only and production-unused; it changes no semantics. Cross-thread test
reads are HB-safe as written: `FreezerGateTest.writeLockAbortThrowsWhenFreezeEngagesAfterProbe`
reads the count on the worker thread itself and publishes via `AtomicReference` +
`CountDownLatch`; `assertRecycledBorrowRunsDdl` reads after `done.await()`/join edges. The
five-path pin-balance test matrix required by the amended CS15 test pin — (a) probe rejection,
(b) checkpoint-2 abort, (c) version-conflict data commit, (d) failed schema-carry commit,
(e) success — is present: (a) in `probeThrowsOnPreEngagedOperatorFreeze`, (b) in
`writeLockAbortThrowsWhenFreezeEngagesAfterProbe`, (c)/(d)/(e) in
`pinBalanceAcrossCommitOutcomesOnRecycledPooledSessions`, each with the recycled-and-re-borrowed
DDL health probe the pin demands. **No issue.**

## 2. Checkpoint throw sites vs WAL / atomic-operation state

### 2.1 In-freezer gates mirror the throw-supplier discipline

Traced discipline at `OperationsFreezer.startOperation` (P6). Per loop iteration:
`operationsCount.decrement()` (`:100`) → loop-top gate (`:105-107`) → legacy
`throwFreezeExceptionIfNeeded()` (`:109`) → enqueue (`:113`) → park-decision gate (`:118-120`)
→ conditional park (`:122-124`) → `operationsCount.increment()` (`:126`). Net count across any
throw: entry increment (`:95`) − iteration decrement (`:100`) = 0. Depth (`:132`) is incremented
only after the loop exits. Both new gates therefore fire with the count re-balanced and the
depth untouched — the identical envelope as the pre-existing `:109` supplier throw. A woken
entrant that loops re-increments (`:126`) then re-decrements (`:100`) before it can throw, so the
multi-iteration case is also net-zero. `endOperation` is correctly NOT owed on any throw path
(depth never incremented).

### 2.2 No WAL / table state precedes the gates

Per P7, checkpoint-3/4 throws precede `atomicOperationsTable.startOperation` and
`atomicOperation.startToApplyOperations(commitTs)` — no table entry, no commitTs, no apply-phase
WAL record exists for the aborted attempt. What HAS run before the freezer entry on this path:
`checkOpennessAndMigration()` and `makeStorageDirty()` (`:2773-2775`) — the dirty flag is
open-lifecycle metadata, not commit state, and is the identical pre-throw footprint of the
legacy throw-mode freeze at HEAD (byte-for-byte precedent). Structure publication
(`reconcileCollections`) and record serialization run strictly after `startTxCommit` (`:2791+`),
so `structurePublished` is false and no undo arm is owed.

### 2.3 Checkpoint-2 abort: locks-only, nothing durable

Per P5, the throw at `:3283` fires before `applyCommitOperations` is entered: no
`makeStorageDirty`, no reconcile, no working set, no WAL interaction — the commit attempt's
durable footprint is empty. The two metadata locks unwind through the already-entered finallys
(`:3301`, `:3304`); the write bit was never durably held (`exclusiveLockWithAbort` releases it
on both abort branches and on any phase-2 throw — `ScalableRWLock:759-790`, hardened in
`5811042e95`), so no reader is queued behind a gave-up writer. The unwind then funnels to E4's
single clear.

### 2.4 startTxCommit's armed overload does not perturb Step-1 gating

Per P13 the armed overload only threads `(schemaArmed, factory)` into the freezer; `endTxCommit`
(`:6535`), its failure-shape discrimination via `isRollbackInProgress()`, the
`endTxCommitFailureTestHook` mirror (`:6536-6547`), and both commit-window test hooks are
untouched. A gate throw from `startTxCommit` skips the inner try entirely, so neither the
error-arm rollback nor the endTxCommit else-arm runs — correct, since the atomic operation never
entered apply; the transaction-level rollback (doCommit catch → `rollbackInternal`) owns the
still-active atomic operation exactly as it does for the legacy throw-mode freeze. The now-unused
one-arg `startTxCommit(AtomicOperation)` overload remains (`:6643-6645`) — see CS31.

## 3. Freeze/release bookkeeping

- **Kinds at all sites** — P8 confirms the charter mapping exactly: both `freeze()` arms
  OPERATOR; `doSynch` + both DiskStorage backup sites TRANSIENT; `release()`'s `-1` sentinel
  maps explicitly to the OPERATOR decrement. The nesting case (freeze() → doSynch, fr 1→2→1
  while op stays 1) is documented at the doSynch site and tolerated by construction.
- **CAS-floor on BOTH counters** — P9: `decrementToFloor` is a single-RMW CAS loop; there is no
  decrement-then-set shape anywhere; the clamp result feeds the `requests == 0` cut decision, so
  a floored (no-op) release can never fire a spurious cut nor suppress a legitimate one.
- **Transient-release finallys cannot mask** — the release path's only failure-capable
  operations are two `LogManager.error` calls (logging, non-throwing by codebase convention),
  `ConcurrentHashMap`/`Long2ObjectOpenHashMap` operations, and `LockSupport.unpark` (never
  throws). `decrementToFloor` cannot throw. So a `doSynch` body failure (e.g. `flushAllData`)
  or a backup-body failure propagates unmasked through `:5653`/`DiskStorage:368/:1270`. The
  underflow log wording at both counters names the double-release cause and the preserved
  arming. **No issue.**
- **Double-release shapes** — id-keyed double release of an operator freeze: first release
  removes the id (`:237`, kind OPERATOR, both decrements); second maps to TRANSIENT (set miss)
  → one floored `freezeRequests` attempt, logged, `operatorFreezeRequests` untouched. Sentinel
  double release: both counters floored, logged. Both shapes pinned by
  `FreezerGateTest.doubleReleaseFloorsCountersAndGateStillArms`, including the re-arm
  assertion (the silent-disarm outage class). Quiesce theft remains the documented accepted
  residual (risk-list #6) — unchanged by this commit.
- **One bookkeeping defect found** — the `operatorFreezeIds` set leaks the id of every
  storage-level `freeze()`/`release()` cycle (CS29 below).

## 4. Crash-point enumeration

Convention: "crash" = process death at the stated instant; on-disk truth = data files + WAL +
dirty flag; recovery = WAL replay of complete atomic units, discard of incomplete ones.

**During an operator freeze, schema commit aborting at each checkpoint:**

| Crash point | Durable state written by the aborted commit | Recovery outcome |
|---|---|---|
| At/after probe throw (E1) | none (no pin, no lock, no WAL, no dirty-flag change) | commit never happened |
| At/after checkpoint-2 abort (E4/E5) | none (§2.3) | commit never happened |
| At/after checkpoint-3/4 throw (E6) | dirty flag possibly (re)set by `makeStorageDirty` — lifecycle metadata identical to the legacy throw-mode footprint; no table entry, no apply WAL record | WAL replay finds no atomic unit for the attempt → pre-commit state; commit never happened |
| Mid-unwind (between gate throw and tx rollback) | as above | same |
| Mid-frontend-rollback after a gate throw | the tx's atomic operation never entered apply; any rollback-side WAL activity is an incomplete/rolled-back unit | discarded; commit never happened |

Reachability note (defense-in-depth confirmed, not assumed): checkpoints (3)/(4) are
production-reachable only through manager-level layering (a schema commit parked behind a
TRANSIENT quiesce when an operator freeze registers) — the public `freeze()` cannot engage while
the commit holds `stateLock.writeLock` (freeze takes the read lock first, `:5782`). The gate
remains the authoritative backstop exactly as the design and the test comment state; its
crash envelope is clean either way.

**Crash during the freezer cut/unpark (operator arm or release-side):** every touched structure
is heap-only — `operatorFreezeRequests`/`freezeRequests` (AtomicIntegers), `operatorFreezeIds`
(CHM key set), `WaitingList.head/tail`/node links/latches, the `cutWaitingList` monitor, park
permits. Nothing durable is read or written by `freezeOperations`/`releaseOperations`
themselves. A crash mid-cut leaves the on-disk state whatever the frozen/flushed storage already
was; restart rebuilds the freezer from zero. **No issue.**

**Crash while frozen (post-`freeze()`, mid-snapshot):** `doSynch` flushed engines and data before
`freeze()` returned; the commit changes nothing here (kind parameter only). Pre-existing
behavior, out of Step-5 scope.

**Pre-existing, unchanged (recorded for completeness, not findings):** (a) `freeze()`'s
index-freeze-loop or `doSynch` throw leaves the OPERATOR registration engaged until an operator
`release()` — pre-existing leak shape at HEAD; the taxonomy actually improves its symptom for
schema commits (loud gate abort naming the freeze, instead of an indefinite park). (b) A
sentinel `release(-1)` while a transient is also active leaves a throw-mode supplier registered
until the count next reaches 0 — pre-existing `requests == 0` cleanup semantics, untouched.

## 5. Q-B5 exception-message contract

`operatorFreezeGateException()` (`AbstractStorage:6686-6691`) is the single factory (Q-B3
shared-helper pin): the probe (`:2533`), checkpoint-2 (`:3283`), and — via the threaded
supplier (`:6655-6656`) — checkpoints (3)/(4) all construct through it. The message embeds the
storage name twice (ctor `dbName` arg + text: "operator freeze in progress on storage
'<name>'"), is retry-directive, and is disjoint from the legacy supplier's "Modification
requests are prohibited" (`:5800-5801`, unchanged). Type is
`ModificationOperationProhibitedException` (HighLevelException, P11): no `setInError`, no
RuntimeException wrapping on the commit rethrow path, so the pinned type/message reach the
caller intact. `FreezerGateTest` pins the fragment on gate paths AND its absence on the legacy
throw-mode path (`throwModeFreezeKeepsLegacySupplierForDataWrites`). The design ruling's example
wording used an em-dash where the code uses `" - "` — the ruling text is explicitly "e.g.", the
tested contract is the code's string. **No issue.**

---

## Findings

### CS29 [suggestion] — `operatorFreezeIds` leaks one entry per storage-level operator freeze/release cycle

`AbstractStorage.freeze()` discards the id returned by `freezeWriteOperations`
(`:5797-5804`), and the paired `release()` releases via the `-1` sentinel (`:5850`), which
`releaseOperations` maps to OPERATOR **without** removing the registration's id from
`operatorFreezeIds` (`OperationsFreezer:232-235` — the set is only consulted/removed on the
id-keyed branch `:237`). Every `freeze()`/`release()` cycle therefore strands one `Long` in the
set for the storage's lifetime, and the set's javadoc ("ids of registered OPERATOR freezes")
becomes inaccurate after the first cycle. Impact today: unbounded-but-glacial heap growth
(operator freezes are rare) and a latent misclassification: a hypothetical future caller
presenting a stale operator id to `releaseOperations` would resolve kind=OPERATOR and, while a
genuine operator freeze is concurrently active, decrement the live freeze's counters without
tripping the CAS-floor (values stay ≥ 0) — a quiesce-theft-shaped disarm. No production path
retains those ids, so this is unreachable today; ids are monotonic, so no collision path exists.
**Fix direction:** either have `freeze()` retain its id (per-storage stack/deque) and `release()`
release by id, or purge `operatorFreezeIds` of the released registration on the sentinel branch
(e.g., record sentinel-released operator ids at registration time), or at minimum correct the
javadoc to admit the sentinel-path residue. Counterexample driving the severity down: no current
caller can present a stale id, and the counters — the gate's only decision inputs — are
unaffected.

### CS30 [suggestion] — park-decision throw leaves the entrant's node enqueued; document the (benign) residue

The checkpoint-(4) throw (`OperationsFreezer:118-120`) fires after `addThreadInWaitingList`
(`:113`), so the throwing thread's node stays linked until the next cut, which will `unpark` a
thread that never parked here. Consequences: one stray park-permit delivered to that thread
later (benign — `LockSupport` consumers must tolerate spurious wakeups, and this freezer's own
loop re-checks; same class as the documented duplicate-unpark of the cut's tail copy,
`WaitingList:31-35`), plus a bounded retention of the node/thread reference until the next cut.
This deviates from the pre-existing supplier throw (`:109`), which fires before the enqueue and
leaves no residue, and it is rare (only the engage-during-enqueue race window). Nothing to fix
functionally; add one sentence to the checkpoint-(4) comment naming the stale node and the
stray-unpark as deliberate and benign, so a future reader does not "fix" the ordering by moving
the gate above the enqueue (which would reopen the V1 race the ordering exists to close).

### CS31 [suggestion] — dead one-arg `startTxCommit(AtomicOperation)` overload

After the call-site change at `:2789`, the one-arg overload (`AbstractStorage:6643-6645`) has no
callers (verified by grep). Delete it, or fold the delegation into the two-arg method's javadoc.
Zero behavioral impact.

## Alternative-hypothesis checks (per major no-issue verdict)

- *"The nested finally might still double-clear against some surviving clear"* — refuted by P3
  (comment-only at the old site) and the exhaustive grep (P2/P3): exactly one clear statement
  exists on the commit path; `forceClearThreadLocalSchemaSnapshot` (throws on non-zero count)
  and `rebuildThreadLocalSchemaSnapshot` (count-preserving) cannot decrement.
- *"Some path enters applyCommitOperations without the pin"* — refuted by P4: both callers sit
  inside the nested try, after the pin; there is no reflective/subclass caller (method is
  private; `DiskStorage` overrides nothing on this path).
- *"The gate throw could be re-wrapped or trigger setInError, breaking the loud-retryable
  contract and the type-pinned tests"* — refuted by P11: the `RuntimeException` overload of
  `logAndPrepareForRethrow` returns the same instance and never calls `setInError`.
- *"Checkpoint-3/4 might fire after WAL/table registration on some interleaving"* — refuted by
  P7: the freezer entry is the first statement of `startToApplyOperations`; the table
  registration is lexically after it in the same method, under `segmentLock`.
- *"The count re-balance could go negative or leak across the woken-then-throw iteration"* —
  refuted by the §2.1 net-zero trace over both single- and multi-iteration paths.
- *"A transient-release finally could throw and mask the frozen body's exception"* — refuted in
  §3: no throwing operation exists on the release path (CAS loop, CHM ops, logging, unpark).
- *"The dirty flag set by an aborted checkpoint-3/4 commit could make a frozen snapshot
  unrecoverable"* — refuted: the flag only routes the next open through WAL replay, which finds
  no complete unit for the attempt; identical to the legacy throw-mode footprint at HEAD.

## Hypothesis log

| # | Hypothesis | Method | Outcome |
|---|---|---|---|
| H1 | Probe throw leaks the pin (CS10 class) | read `:2532-2547` | refuted — probe precedes pin and nested try |
| H2 | Outermost-finally clear (CS19 violation) | read `:2537-2577` | refuted — nested try opens immediately after the pin |
| H3 | Old clear survives → double clear on failed commits (CS15 class) | grep + read `:3236-3241` | refuted — deleted, comment documents why |
| H4 | Hidden third caller of `applyCommitOperations` / `commitSchemaCarry` | grep | refuted — 2 and 1 callers, all inside `commit()` |
| H5 | CP2 abort leaves durable/WAL state or a held write bit | read `:3273-3306`, `ScalableRWLock:685-790` | refuted — pre-apply, bit released on all abort/throw branches |
| H6 | CP3/4 throw leaks freezer count/depth or WAL table entry | trace §2.1/§2.2 | refuted — net-zero count, depth untouched, pre-registration |
| H7 | Armed overload perturbs Step-1 endTxCommit gating / test hooks | diff inspection (P13) | refuted — one-line call-site change only |
| H8 | A registration site missed the kind (5th site) | grep `freezeWriteOperations` | refuted — exactly 4 sites, kinds per charter |
| H9 | Release-finally decrement can throw and mask | read `releaseOperations` + `decrementToFloor` | refuted — log-not-throw throughout |
| H10 | Decrement-then-set shape hiding somewhere | grep for counter mutations | refuted — only `incrementAndGet`/`decrementToFloor` |
| H11 | Sentinel release leaves stale state | read `:231-268` | **confirmed for `operatorFreezeIds`** → CS29 (counters unaffected) |
| H12 | Park-decision throw leaves waiting-list residue | read `:113-124`, `WaitingList` | confirmed, benign → CS30 |
| H13 | Cut/unpark touches anything durable | read `freezeOperations`/`releaseOperations`/`WaitingList` | refuted — heap only |
| H14 | Gate message collides with legacy supplier / lacks storage name | read `:5798-5801`, `:6686-6691`, tests | refuted — distinct, named, pinned both ways |
| H15 | Accessor changes `immutableCount` semantics or races in tests | read `MetadataDefault:78-104`, test HB edges | refuted — pure read, latch/join-ordered reads |
| H16 | freeze() failure path newly wedges/disarms the gate | read `:5806-5828` vs HEAD shape | refuted — pre-existing leak shape; taxonomy improves the symptom |

## Null-verdict statement

On the charter's five mandated probes: (1) single-owner clear — **no defect** (three suggestions
are adjacent hygiene, none touches the pairing); (2) checkpoint throw sites vs WAL/atomic-op
state — **no defect**; (3) freeze/release bookkeeping — **one suggestion (CS29)**, counters and
masking discipline sound; (4) crash points — **no defect**; (5) Q-B5 contract — **no defect**.
No blocker or should-fix was licensed by the evidence.
