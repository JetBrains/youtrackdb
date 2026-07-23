# Track 7 cumulative concurrency review — iteration 1

**Scope:** the entire Track 7 diff `e2605c8ba3..5808d9b11e` at HEAD `5808d9b11e` (production +
test code; docs/workflow files excluded). **Charter:** cross-step interaction — the compositions
the per-step slice reviews structurally could not see. Per-step findings already VERIFIED by the
gate reports (`gate-step{1,1-cs,3,4,5}-fixes-iter1.md`) are treated as settled and not
re-litigated; this review only asks whether the settled mechanisms compose.

**Decision criteria.** A finding is filed only when (1) a concrete interleaving of two or more
steps' mechanisms produces a state no single step's review could have caught, (2) the state is
reachable at HEAD (or its unreachability rests on an unpinned premise), and (3) the consequence
is a liveness, exclusion, or teardown-correctness violation — or an unpinned load-bearing premise
whose silent future violation would produce one. Pre-existing shapes byte-identical at the parent
commit are out of scope unless a Track 7 mechanism changes their consequence. NULL VERDICT is
licensed: every slice already had multi-perspective review with gated fixes.

---

## 1. Composition trace 1 — armed gate abort → unwind → mutex release funnel → skip protocol → owner-as-completer

The charter's first question: does an armed schema commit aborted by ANY gate checkpoint unwind
cleanly through the Step-3 mutex machinery, and does that unwind compose with a concurrent pool
`realClose` (Q-A2 skip) and the owner-as-completer?

### 1.1 The four abort points and what each leaves behind

Premises, each verified at HEAD:

1. **Checkpoint (1)** — `AbstractStorage.java:2545-2547`: throws before the snapshot pin at
   `:2549` and before the nested pin-owning try at `:2560`. State at throw: mutex engaged (from
   the tx's first schema write), tx status `COMMITTING` (set at
   `FrontendTransactionImpl.java:749` before `internalCommit`), **zero storage locks, zero pins,
   no freezer entry, no operations-table entry**.
2. **Checkpoint (2)** — `AbstractStorage.java:3294-3297` inside `commitSchemaCarry`: the abort
   `throw` sits BEFORE the `try` whose finally unlocks the write lock (`:3298-3313`), and
   `exclusiveLockWithAbort` returns `false` only with the write bit fully released
   (`ScalableRWLock.java:685-770`: both abort branches unlock-then-return; the phase-2
   catch-all at `:764-767` unlocks before rethrow). State at throw: schema WL + IM lock held —
   both unwound by `commitSchemaCarry`'s enclosing finallys (`:3315-3320`) — snapshot pin held,
   cleared exactly once by the nested finally at `:2588-2590`. No freezer entry, no table entry.
3. **Checkpoints (3)/(4)** — `OperationsFreezer.java:125-127` (loop-top) and `:150-152`
   (park-decision), reached via `startTxCommit` at `AbstractStorage.java:2802` →
   `startToApplyOperations` (`AtomicOperationsManager.java:141-165`) → `startOperation`. Both
   throws fire with `operationsCount` re-balanced (decremented at `:120`) and BEFORE the depth
   increment, so no `endOperation` is owed. Critically, the throw at `:2802` propagates **before
   the inner try at `AbstractStorage.java:2803` opens**, so neither `rollback(error, op)` nor
   `endTxCommit` runs — and that is correct: `startOperation` threw before
   `atomicOperationsTable.startOperation(commitTs, …)` (`AtomicOperationsManager.java:159`)
   registered anything, so the transaction's atomic operation is still the unregistered
   begin-time snapshot object, torn down like any never-committed transaction (premise 5 below).
   The outer finally's `ensureThatComponentsUnlocked` (`:3239`) is a no-op (no component was
   locked yet). All four commit locks unwind through `commitSchemaCarry`'s finallys; the commit
   window closes (`exitCommitWindow`, `:3308`).
4. **The gate exception is teardown-neutral**: `ModificationOperationProhibitedException` is a
   `HighLevelException`, so `logAndPrepareForRethrow(RuntimeException)`
   (`AbstractStorage.java:7991-8009`) neither logs it as an error nor touches error state (the
   RuntimeException overload never calls `setInError` at all), and
   `moveToErrorStateIfNeeded` (`:5588-5594`) explicitly exempts `HighLevelException`.
5. **The unwind funnel**: `doCommit`'s catch (`FrontendTransactionImpl.java:770-777`) →
   `rollbackInternal` (`:425-495`: status `ROLLBACKING`, listener callbacks, cache
   invalidation, `clear()`) → `close()` (`:1028-1040`) → `closeInternal` (`:1042-1078`):
   `atomicOperation.deactivate()` + `resetTsMin` + null-out (`:1047-1063`), `setNoTxMode`
   (volatile `currentTx` store, `DatabaseSessionEmbedded.java:2492-2494`), volatile
   `status = INVALID` (`:1065`), and the **outermost finally releases the mutex through the
   single funnel** (`:1076` → `releaseMetadataWriteMutexForTx`,
   `DatabaseSessionEmbedded.java:3908-3928`: `getAndSet(0)` claim + `(session, ordinal)` CAS
   second belt). Then `close()`'s own finally fires the owner-as-completer (`:1038` →
   `completeDeferredTeardownAfterTxClose`, `DatabaseSessionEmbedded.java:3987-4005`).

**Conclusion:** every checkpoint abort funnels into exactly the same
`rollbackInternal → close() → closeInternal-finally → completer` path that every other commit
failure (version conflict, endTxCommit failure, seed failure) takes — the path Step 3's tests pin
(`teardownWithSkippedRollbackStillReleasesPermit`, `teardownThrowBeforeTxCloseStillReleasesPermit`,
`seedFailureReleasesPermitSoTheNextWriterIsNotStranded`). The permit is released exactly once via
the claim; if `rollbackInternal` itself throws mid-unwind (FM-A2 shape), the session's
`internalClose` widened outer-finally release (`DatabaseSessionEmbedded.java:3430`) is the belt,
exactly as at every other failure. `pinBalanceAcrossCommitOutcomesOnRecycledPooledSessions`
(`FreezerGateTest.java:753`) additionally pins that gate-aborted pooled sessions recycle cleanly
(pin balanced, session reusable).

### 1.2 Composition with the Q-A2 skip protocol and the completer

Pool `realClose` (`DatabaseSessionEmbeddedPooled.java:76-105`) during a gate-aborted commit, by
window:

- **Between `status = COMMITTING` (`FrontendTransactionImpl.java:749`) and
  `status = ROLLBACKING` (`:438`)** — this spans all four checkpoint throws and the whole
  storage-side unwind. `hasInFlightForeignCommit` (`DatabaseSessionEmbedded.java:4011-4020`,
  volatile `currentTx` at `:289` → volatile `status`/`storageTxThreadId`,
  `FrontendTransactionImpl.java:91/:167/:190-194`) answers **true** → the pool marks
  (`markTeardownIntent`, `:77`) and defers. The owner's unwind then reaches the completer at
  `FrontendTransactionImpl.java:1038` with `teardownIntent` set, `internalCloseInProgress`
  false, session status `OPEN` → the completer runs the full `internalClose(false)` on the
  owning thread (I-C3), whose `rollback()` no-ops (`currentTx` is already the NoTx placeholder
  after `setNoTxMode`; `rollback()` at `DatabaseSessionEmbedded.java:4893-4903` checks
  `isActive()`), listeners fire once, `storage.close(this)` decrements once
  (post-`status = CLOSED`, per the F2-verified ordering), and the completer's own release pass
  no-ops through the claim (the tx-close finally already harvested the ordinal). The trailing
  `status = TXSTATUS.ROLLED_BACK` write (`:470`) on the already-torn-down tx object is inert.
  The caller sees the gate exception; a subsequent `pooled.close()` guard-returns on
  `isClosed()` (`DatabaseSessionEmbeddedPooled.java:38-40`), correctly not re-entering the
  closing pool.
- **After `status = ROLLBACKING`** — `isCommittingOnForeignThread` answers false → the pool
  falls through to a full cross-thread teardown racing the owner's rollback. This is the
  **accepted late-rollback TOCTOU class** (Step 3 ruling; `FrontendTransactionImpl.java:184-188`
  documents it), identical for a gate abort and for any other commit failure — the gate adds no
  new state to this window. The both-act overlap is contained by the `teardownClaim` CAS
  (`DatabaseSessionEmbedded.java:3375`, F2-verified) and the release claim.
- **The Dekker pair itself**: pool writes mark then reads status
  (`DatabaseSessionEmbeddedPooled.java:77-78`); owner writes volatile `status = INVALID`
  (`FrontendTransactionImpl.java:1065`) then reads the mark in the completer
  (`DatabaseSessionEmbedded.java:3988`). Both volatile — at least one side always acts. The gate
  abort changes nothing in this pair: the abort path reaches the identical boundary.
- **Pool-side loop isolation**: a throwing `realClose` no longer aborts the pool loop
  (`DatabasePoolImpl.java:127-143`), and `ResourcePool.close()` after the loop only drains
  permits (`ResourcePool.java:187-189`) — harmless to a deferred (skipped) session the owner
  completes later.

**Verdict: the composition is sound.** The one residual is that no test drives this exact
composition (pool close racing a commit the GATE aborts) — filed as CN47 (suggestion), because
the skip keys purely on the volatile `COMMITTING` status, which spans the checkpoint throws
identically to the tested successful-commit window, and the abort unwind reuses the tested close
funnel byte-for-byte.

---

## 2. Composition trace 2 — Step 1 fresh-read scopes vs checkpoint aborts

Question: can a `computeWithFreshCommittedReads` scope be ACTIVE when a checkpoint-2/3/4 abort
unwinds, and does the scope clean up?

Premises:

1. The five scope sites at HEAD: the seed (`SchemaShared.java:289`, inside `copyForTx`, runs in
   `ensureTxSchemaState` strictly pre-commit), the commit-time serialization scope
   (`AbstractStorage.java:2879`), the enrollment scope (`:2909`), the promotion scope
   (`:3218`), and the scope machinery itself (`DatabaseSessionEmbedded.java:3727-3746`).
2. Checkpoint ordering: cp1 (`:2546`) and cp2 (`:3294`) fire before `applyCommitOperations` is
   entered; cp3/cp4 fire at `startTxCommit` (`:2802`), which precedes the `:2879`/`:2909` scopes
   inside the same try; the `:3218` promotion scope runs strictly after `endTxCommit` succeeded
   — after every checkpoint. The seed scope opens and closes inside `ensureTxSchemaState`
   (its value is returned only after the scope's finally ran).
3. Therefore **no fresh-read scope can be open at any checkpoint abort** — the scopes and the
   checkpoints are strictly interleaved, never nested. The non-reentrancy throw at `:3729-3733`
   independently guarantees a silent nesting cannot arise.
4. For exceptions raised INSIDE a scope (a read failure in toStream/enroll/promotion), the
   scope's finally (`:3742-3745`) nulls `freshCommittedReadOperation` and deactivates the
   dedicated operation before the exception reaches any commit finally; the promotion scope's
   failures are additionally absorbed by the promotion catch (`:3225-3244`, setInError — Step 1
   crash-safety-verified). The dedicated operation never registers in the freezer or the
   operations table (`startAtomicOperation`, `AtomicOperationsManager.java:106-127`, takes only
   a momentary `segmentLock.sharedLock` and builds a snapshot — no `startOperation`, no
   `tsMin`), so an abort unwind never owes it any freezer/table bookkeeping.
5. `EntityImpl.rePopulateSourceBytes` (`EntityImpl.java:3780-3785`) resolves through
   `getEffectiveReadAtomicOperation` — a read-path seam with no unwind obligations.

**Verdict: null — no defect.** The scopes are structurally disjoint from the abort points, the
cleanup is finally-owned at the scope granularity, and the scope's operation is freezer- and
table-invisible by construction.

---

## 3. Composition trace 3 — Step 2 reload guard vs Step 3 engage-order check vs Step 5 gate

Question: any interleaving where reload + freeze + mutex compose badly?

1. **Reload never touches the mutex or the freezer.** `SchemaShared.reload`
   (`SchemaShared.java:729-741`) holds the schema WL and runs `fromStream` inside
   `runReloadingCommittedSchema` (`DatabaseSessionEmbedded.java:3693-3700`); the membership seam
   (`IndexManagerEmbedded.java:251-263`) no-ops every ripple, so `ensureTxSchemaState` — the
   only mutex-engage site (`:3630`) — is never reached and the engage-order guard
   (`:3861-3872`) never fires. The reload transaction is read-only (`fromStream` reconstructs
   in place, nothing enrolls), so `doCommit` skips `internalCommit`
   (`isWriteTransaction` false) — the reload **never enters the freezer**, and therefore cannot
   park under any freeze while holding the schema WL. A reload under an active operator freeze
   completes normally (reads are not freezer-gated).
2. **Reload vs a concurrent schema commit**: the commit's `commitSchemaCarry` takes the schema
   WL first (`AbstractStorage.java:3286`), so reload and commit serialize on `SchemaShared.lock`
   with no second lock in either's hand that the other needs before it releases (reload's inner
   acquisitions — `segmentLock.sharedLock`, `stateLock.readLock` via record loads — sit strictly
   below the schema WL in the commit's own order, §5).
3. **Reload guard vs seed guard**: `reloadingSchema` and `seedingTxSchemaState` are both plain,
   session-thread-confined fields read only from the same seam
   (`IndexManagerEmbedded.java:239-263`); the reload-scope assert (`:3693`) pins non-nesting.
   A reload can run while another SESSION's seed is mid-flight — different session objects,
   different guard fields, serialized by the schema WL where they touch shared state.
4. **The one three-way shape**: operator freeze active + reload + schema commit. Reload
   completes (read-only); the schema commit fails fast at cp1 with zero locks; the mutex cycles
   through the abort unwind (§1). No wedge, no inversion.

**Verdict: null — no defect.**

---

## 4. The track invariants as implemented

### 4.1 I-handshake-1 — exactly one releaser, never wedges

All four release sites funnel through `releaseMetadataWriteMutexForTx`'s `getAndSet(0)` claim
(`DatabaseSessionEmbedded.java:3908-3928`): the tx-close finally
(`FrontendTransactionImpl.java:1076`), the widened `internalClose` outer finally
(`DatabaseSessionEmbedded.java:3430`), the engage-path Dekker self-release (`:3888`), and the
seed-failure catch (`:3656`). The mutex-side `(session, ordinal)` CAS
(`MetadataWriteMutex.java:190-218`) is the independent second belt; the winner nulls the holder
before `permit.release()`. The V2 ordering (`:3874-3882`: ordinal-store before mark-read) closes
the mid-flight engage window; the loop-top abort (`MetadataWriteMutex.java:151-156`, lock-free
per F6) closes the parked-waiter window; FM-A7 (`:132-140`) closes the same-session re-engage
self-deadlock. Wedge residuals are exactly the two accepted classes: a genuinely stranded owner
(YTDB-1114, loud via the 10s WARN + YTDB-550) and the CN34 neither-completes corner (recorded in
track-7.md as an accepted FM-A5-class residual). **Spot-check verdict: holds as composed.**

### 4.2 I-freezer-1 — a schema commit never parks under an operator freeze

The four checkpoints all read the single counter probe
(`AbstractStorage.java:6684-6687` → `OperationsFreezer.java:238-240`); the V1 arm ordering
(`OperationsFreezer.java:190-199`: kind-before-count, cut-after-both at `:226`) plus the V8
retract ordering (`:277-296`) give the one-sided guarantee; the park-decision re-check after the
enqueue (`:150-152`) closes the engage-during-enqueue race. The layered wake path is pinned by
`parkedSchemaCommitWakesAndThrowsWhenOperatorFreezeArrives` (`FreezerGateTest.java:400-459`).
Composition observation (feeds CN46): in the shipped production composition the invariant is
additionally protected by a STRUCTURAL fact — the only production OPERATOR arm site,
`AbstractStorage.freeze()` (`:5790-5850`), registers under `stateLock.readLock`, so an operator
freeze can never engage while an armed commit holds the write bit (cp2 through window exit);
readers back off behind the held bit, `freeze()` waits boundedly (the commit either completes
or parks only behind a TRANSIENT quiesce, which releases without needing `stateLock` —
`doSynch`'s release precedes its caller's readLock release, `AbstractStorage.java:5666`;
the two backup sites hold no `stateLock` at all, `DiskStorage.java:358-361/:1253-1256`).
Consequence: cp3/cp4 and the arm-cut wake are reachable in production ONLY as belts for the
manager-level seam — and, more importantly, the **unarmed** freezer entrants on the failure path
(§5.3) are operator-safe only because of this same unpinned premise. **Spot-check verdict: holds
as composed; the premise deserves a pin (CN46).**

### 4.3 I-C3 — owning-thread teardown

The skip protocol defers the COMMITTING case to the owner (§1.2); the completer runs on the
owning thread at the tx-close boundary; the sanctioned exception (pool shutdown of a
non-committing checked-out session) runs the owning session's own teardown cross-thread with the
claim + funnel containing overlap, and `assertOnOwningThread` explicitly exempts
`close()`/`rollbackInternal` (`FrontendTransactionImpl.java:171-181`). The foreign harvest heals
the permit (`foreignTeardownHarvestsEngagedPermit` pins it). **Spot-check verdict: holds.**

---

## 5. Lock-order sanity across all new/changed acquisition sites

Global order at HEAD: `MetadataWriteMutex` ≺ `SchemaShared.lock` ≺ index-manager lock ≺
`stateLock` ≺ freezer entry ≺ `segmentLock` ≺ component locks; leaf monitors/atomics below.

1. **cp2 `exclusiveLockWithAbort`** (`AbstractStorage.java:3294`) — same position as the plain
   write lock it replaced; phase-1 queuing holds nothing; abort/throw paths release before
   escaping (§1.1 premise 2). No new edge.
2. **`WaitingList.cutWaitingList` monitor** (`WaitingList.java:53`) — verified leaf: inside the
   monitor only `Thread.yield`, latch awaits, and plain stores; the latch waits are bounded by
   an in-flight enqueuer's two plain stores, and enqueuers (`addThreadInWaitingList`,
   `:12-28`) never take the monitor and hold no lock the cutter's callers need. Callers:
   operator arm (`OperationsFreezer.java:226`, under `freeze()`'s `stateLock.readLock`) and the
   1→0 release cut (`:309`, from transient-release finallys also under `stateLock.readLock` via
   `synch()`/`freeze()`) — both hold locks ABOVE the leaf monitor only. Unparks run outside the
   monitor (`cutAndUnparkWaiters`, `:307-315`). No cycle.
3. **Failure-path undo arms** — `revertCreatedCollectionStructure`
   (`AbstractStorage.java:3646-3663`), `revertCreatedIndexEngineStructure` (`:3990+`),
   `restoreReconciledDroppedIndexEngines` (`:3912-3921`) call `executeInsideAtomicOperation` →
   **unarmed** `startOperation` while holding the mutex + schema WL + IM lock +
   `stateLock.writeLock`. Freezer-inside-stateLock is order-consistent (freezer sits below
   `stateLock`), and the entrant can park only behind a TRANSIENT quiesce (bounded, the
   design-accepted class); the operator case is structurally unreachable in production (§4.2)
   — but only by the unpinned premise. The link-bag restore's snapshot-only operation
   (`:3557-3567`) and the fresh scopes take only momentary `segmentLock.sharedLock` — verified
   by the step-1-cs gate (G2), consistent order.
4. **The engage wait loop** (`MetadataWriteMutex.java:143-176`) — acquires nothing while
   waiting (lock-free `getStatus` probe per F6; `describeHolder` reads plain fields).
5. **`teardownClaim` / `engagedMutexOrdinal` / `operatorFreezeIds` (both the storage deque
   `AbstractStorage.java:312` and the freezer set `OperationsFreezer.java:48`)** — non-blocking
   atomics/concurrent structures, no ordering obligations.
6. **Fresh scopes inside the commit window** — reads self-route lock-free
   (`readRecordInternal`, `AbstractStorage.java:6496-6529`; `getCollectionNames`/
   `getCollectionIdByName` `:2308-2380`; `getPhysicalCollectionNameById` `:5672-5700`), so no
   read-lock re-entry under the held write bit.
7. **`internalClose` → `isClosed()`** (`DatabaseSessionEmbedded.java:3396` via `:750-752`) takes
   `stateLock.readLock` with only non-lock atomics held — bounded behind a commit window, no
   cycle.

**Verdict: null — no ordering violation found at any new or changed site.**

---

## 6. The four Track-6 IT expectation fixes

`StorageTestIT`, `TruncateOrphansAfterRecoveryIT`, `InvalidRemovedFileIdsIT`,
`StorageBackupMTRestoreIT`: pure test-lookup changes — each class's `.pcl`/`.cpm` file is now
resolved through its collection id via the open session instead of a class-name prefix match
(counter-only `c_<n>` names carry no class-name component). No production code touched, no
concurrency surface (the lookups run single-threaded before/after the storage operations under
test, and the resolution happens while the session is still open — before `close()` — so no new
lifecycle race is introduced). **Verdict: null.**

---

## 7. Findings

### CN46 — the "operator freezes always arm under `stateLock.readLock`" premise is load-bearing for two composed properties and pinned nowhere — **suggestion**

**Premises.** (1) The sole production OPERATOR registration site is `AbstractStorage.freeze()`
(`AbstractStorage.java:5810-5822`), which runs its `freezeWriteOperations(FreezeKind.OPERATOR, …)`
inside `stateLock.readLock()` (`:5793`). (2) An armed schema commit holds `stateLock.writeLock`
continuously from cp2 success to window exit (`:3294-3313`), and `ScalableRWLock` readers back
off while the write bit is held. (3) The failure-path undo arms
(`revertCreatedCollectionStructure` `:3646`, `revertCreatedIndexEngineStructure` `:3990+`,
`restoreReconciledDroppedIndexEngines` `:3912`) and the success-path snapshot cleanup enter the
freezer **unarmed** (`executeInsideAtomicOperation` → `startToApplyOperations(op)` →
`startOperation(false, null)`) while holding all four locks — they are the one commit-path
entrant family the four checkpoints deliberately do not cover.

**Composition consequence.** Today (1)+(2) make an operator freeze unable to engage while the
write bit is held, so (3) can park only behind a bounded TRANSIENT quiesce — the design-accepted
class. But nothing pins premise (1): no comment at `freeze()`, at
`OperationsFreezer.freezeOperations`, or at the undo helpers names it, and the manager-level seam
(`AtomicOperationsManager.freezeWriteOperations`) that every `FreezerGateTest` case uses is
exactly a registration path that skips `stateLock`. A future operator-freeze caller registering
without `stateLock.readLock` (an admin API refactor, a maintenance task riding the manager seam)
would silently create the counterexample: armed commit passes cp1-cp4, fails mid-apply (version
conflict), the operator freeze engages during the apply, and the undo's unarmed `startOperation`
**parks under the operator freeze holding the mutex + schema WL + IM lock + `stateLock.writeLock`**
— the exact I-freezer-1 outage, now on the failure path where no checkpoint exists, for the
freeze's whole unbounded duration (with a throw-mode operator freeze, the supplier's exception is
instead swallowed by the undo's log-and-continue catches, silently skipping registry restore
arms). The same premise is what makes cp3/cp4 and the arm-cut wake production-unreachable belts,
so a regression here would also not be caught by any existing production-shaped test.

**Fix (either suffices):** a comment pin at `AbstractStorage.freeze()` and
`OperationsFreezer.freezeOperations` stating that every production OPERATOR registration must
either arm under `stateLock.readLock` or thread the schema gate, PLUS a mirror note at the undo
helpers naming the premise they rest on; or thread the arm signal into the failure-path
`executeInsideAtomicOperation` calls (heavier, not recommended — the bounded transient park is
fine and the abort semantics on an undo path would need their own design).

**Alternative hypothesis considered:** that the undo park is reachable TODAY under a transient
freeze and already violates the invariant — rejected: the transient park is bounded by the quiesce
body and explicitly accepted by the design for schema commits ("may park behind it exactly like
data commits", `DiskStorage.java:358-359`, `AbstractStorage.java:5628-5631`), and the shape is
byte-identical at the parent commit (pre-Track-7 `revertCreatedCollectionStructure` used the same
wrapper), so Track 7 only changed the SURROUNDING guarantees, not this entrant.

### CN47 — pool `realClose` during a gate-aborted commit: the one Track-7-specific composition of the skip protocol has no regression net — **suggestion (test-only)**

**Premises.** `poolCloseDuringCommitDefersTeardownToOwner` (`MetadataWriteMutexTest.java:1041`)
pins skip + owner-completion for a commit that SUCCEEDS while the pool closes;
`pinBalanceAcrossCommitOutcomesOnRecycledPooledSessions` (`FreezerGateTest.java:753`) pins gate
aborts on pooled sessions WITHOUT a racing pool close. No test composes the two: a pool `close()`
whose `realClose` lands in the `COMMITTING` window of a commit that a freezer-gate checkpoint then
aborts, asserting the owner's completer performs the full teardown and the permit/session-count
end single.

**Why only a suggestion:** §1.2 traces the composition sound by construction — the skip keys
purely on the volatile tx status, which spans the checkpoint throws exactly as it spans the
tested success window, and the abort unwind reuses the identical
`rollbackInternal → close() → completer` funnel that four existing failure-path tests already
pin. There is no gate-abort-specific state the skip or the completer reads. The test would pin
the composition against future drift (e.g., someone moving the checkpoint throws outside the
`COMMITTING` span or short-circuiting the close funnel on `HighLevelException`).

**Sketch:** reuse the `poolCloseDuringCommitDefersTeardownToOwner` scaffolding; hold the commit
inside the window via the existing `commitWindowTestHook`, close the pool on a spawned thread
(the skip fires), then engage a manager-level operator freeze and release the hook so the commit
proceeds to cp3's loop-top throw; assert the gate exception surfaces, the completer closed the
session (status CLOSED, listener count 1), and the next borrower's engage acquires the permit.

---

## 8. Hypothesis log

| # | Hypothesis | Disposition |
|---|---|---|
| H1 | A checkpoint-3/4 abort leaves the atomic operation half-registered (table entry or WAL apply state) needing a rollback nothing performs | REJECTED — the throw precedes `atomicOperationsTable.startOperation` and `atomicOperation.startToApplyOperations` (`AtomicOperationsManager.java:141-165`); the op is torn down by `closeInternal`'s `deactivate()` like any never-committed tx (§1.1) |
| H2 | A checkpoint abort strands the freezer's `operationsCount` or depth | REJECTED — both gates throw with the count re-balanced (`OperationsFreezer.java:120/:125/:150`) and before the depth increment; no `endOperation` is owed and none is issued (no `endAtomicOperation` runs on this path) |
| H3 | The cp2 abort (or a predicate/interrupt throw) escapes with the write bit or a metadata lock held | REJECTED — abort branches unlock-then-return, the phase-2 catch-all unlocks before rethrow (`ScalableRWLock.java:717-767`); the cp2 throw sits above the stateLock try, and schema/IM locks unwind through `commitSchemaCarry`'s finallys (`AbstractStorage.java:3294-3320`) |
| H4 | The gate exception poisons the storage or is double-handled by `logAndPrepareForRethrow` | REJECTED — `HighLevelException` is exempted in the RuntimeException overload and in `moveToErrorStateIfNeeded` (`AbstractStorage.java:7991-8009/:5588-5594`) |
| H5 | A pool `realClose` in the gate-abort window defeats the skip or double-tears | REJECTED — the `COMMITTING` span covers all four throws; the ROLLBACKING fall-through is the accepted late-rollback class; both-act is claim-contained (§1.2) — residual test gap filed as CN47 |
| H6 | A fresh-read scope is open at a checkpoint abort and leaks its operation | REJECTED — scopes and checkpoints strictly interleave (§2); nesting throws; scope finally owns cleanup; the scope's op is freezer/table-invisible |
| H7 | Reload parks in the freezer (or engages the mutex) while holding the schema WL under a freeze | REJECTED — reload's tx is read-only (no `internalCommit`, no freezer entry); the guard suppresses the only mutex-engage seam (§3) |
| H8 | The undo arms' unarmed freezer entry inside the four-lock window re-creates the I-freezer-1 outage | PARTIALLY — unreachable today only via the unpinned `stateLock.readLock` premise; transient park bounded and accepted; premise pin filed as **CN46** |
| H9 | Production checkpoints 3/4 or the arm-cut wake protect a reachable shape (so a silent regression there matters more than "belt") | REJECTED as a defect — production `freeze()` under readLock makes them manager-seam belts; consistent with the design's "authoritative backstop" framing; folded into CN46's premise |
| H10 | The cp4 stray node's later unpark corrupts an unrelated park (mutex semaphore, later freezer park, latch) | REJECTED — all park consumers loop (AQS re-checks, freezer loop-top re-evaluates, latch awaits tolerate spurious permits); node drained at the next cut; thread-terminated unpark is a no-op |
| H11 | `freeze()` (readLock) vs armed commit (write bit) vs parked-reader data commit forms a cycle | REJECTED — the parked reader's transient freeze releases without needing `stateLock` (doSynch releases before its caller's readLock; backup sites hold none), unwinding the chain; enumerated in §4.2 |
| H12 | An armed entrant with pre-existing `operationDepth > 0` bypasses cp3/cp4 (`OperationsFreezer.java:114`) | REJECTED — the frontend schema commit is the only armed entrant and always enters at depth 0 (top-level `storage.commit`); the skip is byte-identical to the historical throw-supplier skip |
| H13 | The `synchronized` cut monitor deadlocks against an enqueuer or another lock | REJECTED — verified leaf; latch waits bounded by an enqueuer's two plain stores; enqueuers take no locks; unparks outside the monitor (§5.2) |
| H14 | The completer's plain `status`/`internalCloseInProgress` reads race a pool-thread write into a double full teardown | REJECTED — both-act is exactly what the `teardownClaim` CAS contains (F2-verified); the plain reads are documented as claim-backed (`DatabaseSessionEmbedded.java:330-356`) |
| H15 | Double `deactivate()` on the endTxCommit-failure unwind (endAtomicOperation then closeInternal) | REJECTED as a track matter — byte-identical pre-existing shape for every post-`endAtomicOperation` commit failure, data commits included |
| H16 | `makeStorageDirty` before cp3/cp4 flips the dirty flag under an engaged freeze | REJECTED — byte-identical to the pre-existing data-commit shape (dirty-then-park); cp1/cp2 abort armed commits before the flag on every production-reachable operator shape |
| H17 | The seed/copyForTx fresh scope under the committed schema WL inverts the lock order | REJECTED — mutex ≺ SchemaShared.WL ≺ {segmentLock.shared, stateLock.read} matches the commit's own order (§5); the engage-order guard enforces mutex-above-schema at runtime |
| H18 | The IT lookup fixes weaken an assertion or add a lifecycle race | REJECTED — resolution through the open session's collection id before close; write-cache presence still asserted loudly; no production change (§6) |

---

## 9. Null-verdict statement

Across the five charter dimensions, the composed Track 7 code is sound at HEAD `5808d9b11e`: the
gate-abort unwind funnels into the same claim-gated release/completer boundary every other commit
failure uses (exactly-one-release provable across the enumerated pool-race windows); fresh-read
scopes are structurally disjoint from every abort point and self-clean; reload, freeze, and mutex
compose without a new blocking edge; the three track invariants hold as implemented (I-freezer-1
with one unpinned structural premise, CN46); and no new or changed acquisition site violates the
global lock order. **No blocker, no should-fix.** Two suggestions: CN46 (pin the
operator-arms-under-readLock premise that both the checkpoint-belt topology and the failure-path
undo arms silently rest on) and CN47 (a regression net for the pool-close-during-gate-abort
composition).

---

## Findings

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CN46 | suggestion | `AbstractStorage.java:5793/:5810-5822` (freeze under readLock); `:3646/:3912/:3990` (unarmed undo entrants); `OperationsFreezer.java:105-160` | The premise "every production OPERATOR freeze arms under `stateLock.readLock`" is load-bearing twice — it is why cp3/cp4+arm-cut are production-unreachable belts AND why the failure-path undo arms' unarmed freezer entries (all four locks held) can never meet an operator freeze — but it is pinned nowhere | A future operator registration riding the manager seam (as every gate test does) engages mid-apply; the failed commit's undo parks unarmed under the operator freeze holding mutex+schema+IM+stateLock.writeLock — the I-freezer-1 outage on the failure path, where no checkpoint exists; throw-mode variant silently skips undo arms via the log-and-continue catches. Fix: comment pins at the arm site and undo helpers |
| CN47 | suggestion (test) | `MetadataWriteMutexTest.java:1041` + `FreezerGateTest.java:753` (gap between them) | No test composes the Q-A2 skip with a GATE-ABORTED commit — the one skip-protocol composition Track 7 itself created; traced sound (skip keys on the volatile COMMITTING span that covers all four checkpoint throws; the abort reuses the tested close funnel) but unpinned against drift | Move a checkpoint throw outside the COMMITTING span (or short-circuit the close funnel for `HighLevelException`) and every existing test stays green while the pool-close-during-gate-abort session leaks its teardown; sketch: window-hook + pool-close + operator freeze + release hook → assert completer teardown + single permit |

Null verdicts (explicit): gate-abort unwind → release funnel composition — sound; skip protocol +
owner-as-completer under gate aborts — sound (CN47 net only); fresh-read scope cleanup on abort
unwinds — structurally disjoint, sound; reload+freeze+mutex interleavings — sound; I-handshake-1,
I-freezer-1, I-C3 as composed — hold (CN46 premise pin); lock order at every new/changed site
incl. the cut monitor and teardownClaim — consistent; Track-6 IT expectation fixes — test-only,
sound.
