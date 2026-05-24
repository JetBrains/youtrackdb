# Track 2: Consolidate persist + apply into endAtomicOperation

## Purpose / Big Picture
After this track, every counter sync — main commit, `clearIndex` API, `IndexAbstract.buildHistogramAfterFill` — advances through `AtomicOperationsManager.endAtomicOperation` as the single lifecycle gate, under the per-index lock acquired at `lockIndexes` (AbstractStorage:2255). The lock-window race that today's manual apply at AbstractStorage:2365 leaves open (apply runs after `endAtomicOperation`'s inner-finally `releaseLocks` has released the per-index lock) is structurally closed.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Move `persistIndexCountDeltas` / `applyIndexCountDeltas` / `applyHistogramDeltas` into `AtomicOperationsManager.endAtomicOperation`. Persist runs before `commitChanges` with a persist-failure-to-rollback conversion (catches `RuntimeException | AssertionError`; `AbstractStorage.persistIndexCountDeltas` does not declare `IOException`). Apply runs after `commitChanges` but before the inner-finally `releaseLocks`, so the per-index lock acquired by `lockIndexes` at AbstractStorage:2255 is still held during apply; failures inside apply are logged and swallowed (cache-only contract). The manual calls at `AbstractStorage.commit` lines 2340, 2365, 2381 and their post-`endTxCommit` catches at lines 2366 and 2382 are deleted. After this track, Tracks 3 and 4 can land pure-delta encoding without re-introducing the race.

## Progress
- [x] 2026-05-23T10:44Z [ctx=info] Review + decomposition complete
- [ ] Step implementation
  - [x] 2026-05-23T13:38Z [ctx=safe] Step 1 complete (commit a10c7d9394)
  - [x] 2026-05-23T17:44Z [ctx=info] Step 2 complete (commit 20908b4a91)
  - [x] 2026-05-24T12:11Z [ctx=warning] Step 3 complete (commit 3ec8bffc19)
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

- `AbstractStorage.isInError()` is `protected`. Sibling-package Mockito tests (e.g., `paginated.atomicoperations`) cannot stub it; Track 3's hook-ordering test will need to either widen the method to package-private or use a partial Mockito spy if it needs to drive storage error-state independently. See Episodes §Step 2.
- "Hook A" / "Hook B" labels appear in production source comments (six pre-existing references at commit `46a8edfa06`, three more added at `20908b4a91`). Allowed under the ephemeral-identifier rule, but a rename to durable phrases (e.g., "lifecycle persist hook" / "lifecycle apply hook") may be worth a deferred plan correction before Phase 4's design-final aggregation. See Episodes §Step 2.
- `implementation-plan.md` lines 85, 87, 90 still describe Hook A's catch as `IOException | RuntimeException | AssertionError`; the as-implemented catch is `RuntimeException | AssertionError` because `AbstractStorage.persistIndexCountDeltas` does not declare `IOException`. `track-2.md` was narrowed in commit `986b49dda6` (M11); the implementation-plan drift is deferred to Phase C track review or Phase 4. See Episodes §Step 2.
- Mockito's `doCallRealMethod` on a bare mock of `AbstractStorage` leaves instance fields uninitialised; the `indexEngines` list is null and surfaces as `NullPointerException` at the first `indexEngines.size()` call inside the per-engine loop. `AbstractStorageApplyDeltaTest` uses this NPE as the simulated mid-loop throw rather than bringing up a real storage shell. Future test writers reaching for `doCallRealMethod` on `AbstractStorage` should expect the same uninitialised-field pattern. See Episodes §Step 3.
- Hook B's gate now matches Hook A's symmetry: `currentError == null && !operation.isRollbackInProgress()`. Tracks 3 and 4's pure-delta consumers (`clear()`, `buildHistogramAfterFill`) can assume apply runs only on the commit success path; if a future consumer wants to observe apply on a rolled-back operation, the gate symmetry must be revisited. See Episodes §Step 3.
- `AbstractStorage.applyIndexCountDeltas` / `applyHistogramDeltas` latch the holder up front (before the per-engine loop). Any future refactor splitting apply into a compute-deltas phase and a publish-deltas phase must keep the latch flip on the entry side or restore equivalent partial-loop safety. See Episodes §Step 3.
- `tryApply(Runnable, String)` helper inside `AtomicOperationsManager.endAtomicOperation` centralises the cache-only catch surface (`RuntimeException | AssertionError` log-and-swallow). Future apply branches added by downstream tracks should route through the same helper rather than re-implementing the catch shape locally. See Episodes §Step 3.

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

- [x] 2026-05-24T12:11Z [ctx=warning] S1 rerouting: the iter-1 suggestion to assert `holder.isApplied()` inside Hook B's body after each apply call was rerouted from a manager-site assert to a production-path test pin (`AbstractStorageApplyDeltaTest` via `doCallRealMethod`). Mock-storage stubs in `EndAtomicOperationHookOrderingTest` use `doNothing` / `doAnswer` that do not call `setApplied()`, so a hook-site assert would fail under tests even though it passes in production. The production-path test is a stronger guarantee than the manager-site assert would have been. See Episodes §Step 3.

## Outcomes & Retrospective

- [x] 2026-05-23T10:44Z [ctx=info] Technical: PASS at iteration 2 (11 findings; 7 accepted and verified — T1, T2, T3, T4, T5, T6, T10; 4 deferred/informational — T7–T9, T11).
- [x] 2026-05-23T10:44Z [ctx=info] Risk: PASS at iteration 2 (9 findings; 3 accepted and verified — R2, R3 by alternative Q2 (a), R8; 6 deferred to Phase B or moot per T3 — R1, R4–R7, R9).
- [x] 2026-05-23T10:44Z [ctx=info] Adversarial: PASS at iteration 2 (13 findings; 6 accepted and verified — A1, A2 with corrected setInError trace, A3, A5(i), A10, A13 by alternative Q2 (a); 7 deferred to Phase B / Track 1 carry-forward / moot / rejected — A4, A6–A9, A11, A12).
- [x] 2026-05-23T10:44Z [ctx=info] Design decisions resolved in chat (Q1, Q2). Q1: Hook A wraps as StorageException with explicit `moveToErrorStateIfNeeded(persistFailure)` and typed re-raise (`RuntimeException` short-circuit, else `StorageException` wrap). Q2: visibility raise to `public` on `AbstractStorage`.

## Context and Orientation

`AtomicOperationsManager.endAtomicOperation` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java:257–295` already owns the commit lifecycle. The relevant shape today:

```
public void endAtomicOperation(AtomicOperation operation, Throwable error) throws IOException {
  try {
    storage.moveToErrorStateIfNeeded(error);
    if (error != null) operation.rollbackInProgress();
    try {
      if (!operation.isRollbackInProgress()) {
        lsn = operation.commitChanges(commitTs, writeAheadLog);   // WAL flush
      }
      // ... atomicOperationsTable.{commit,rollback}Operation ...
    } finally {
      releaseLocks(operation);                                    // line 289
      operation.deactivate();
    }
  } finally {
    writeOperationsFreezer.endOperation();
  }
}
```

Two methods on `AbstractStorage` resolve to one-liners that delegate here: `endTxCommit` at `AbstractStorage.java:4545` is `endAtomicOperation(op, null)`; `rollback(error, op)` at `AbstractStorage.java:3632` is `endAtomicOperation(op, error)`. Both the commit-path success branch and the rollback branch already converge on `endAtomicOperation` — this track makes that convergence the only place counter sync happens.

The lock-window race that motivates consolidation: `lockIndexes` at AbstractStorage:2255 calls `acquireExclusiveLockTillOperationComplete` per index (via `BTree.acquireAtomicExclusiveLock` at `BTree.java:1732`), which adds the component to `operation.lockedComponents()` (`AtomicOperationsManager.java:339`). `releaseLocks` at `AtomicOperationsManager.java:301–321` iterates `lockedComponents()` and releases each one. The inner-finally `releaseLocks` call at line 289 of `AtomicOperationsManager.endAtomicOperation` therefore releases the per-index lock *inside* the lifecycle method, before `endAtomicOperation` returns. The subsequent `ensureThatComponentsUnlocked` at AbstractStorage:2407 calls `releaseLocks` again; its idempotent `isExclusiveOwner()` check (commented at lines 303–305 of the manager) confirms this is a defensive double-call that finds nothing to do. So today's `applyIndexCountDeltas` at AbstractStorage:2365 runs *after* the per-index lock has been released, leaving a ~40-line race window between lock release and the apply.

The manager has a `storage` field (`AtomicOperationsManager.java:55`, set in the ctor at line 71), so the manager can call back into `AbstractStorage`. `persistIndexCountDeltas` (`AbstractStorage.java:2486`), `applyIndexCountDeltas` (`AbstractStorage.java:2511`), and `applyHistogramDeltas` (`AbstractStorage.java:2540`) are currently `private`; visibility rises to `public` on `AbstractStorage`. The manager and storage live in different packages (`paginated.atomicoperations` and `impl.local`), so plain package-private would not cross. `public` is consistent with the existing manager-callback surface on the same class (`moveToErrorStateIfNeeded` at line 3637, `getName`, `checkErrorState`, `getSnapshotIndexSize` are all `public` despite being internal-only); the `internal/` package marker conveys the intent that the class is implementation detail. The surface is three methods.

`IndexCountDelta` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDelta.java` keeps its existing fields `totalDelta` and `nullDelta`. No flags are added: under the single-lifecycle-gate design each hook runs at most once per atomic op, so idempotency is structural.

`AtomicOperation.getOrCreateIndexCountDeltas()` / `getIndexCountDeltas()` (referenced at `AbstractStorage.java:2487`, `:2512` and in `IndexCountDelta.accumulate` at line 60) return the per-tx holder. `AtomicOperation.getHistogramDeltas()` at `AbstractStorage.java:2541` is the histogram parallel.

Recovery-time `executeInsideAtomicOperation` call sites at `AbstractStorage.open` lines 766, 779, 794, 797, 799, 800, 809, 811, 860 all run before `status = STATUS.OPEN` at line 861. Research confirmed none accumulate `IndexCountDelta` today, so the lifecycle hook is a no-op via the existing `if (holder == null) return;` early-exit in the three target methods. No state gate is wired: skipping the hooks on non-OPEN states would drop counter syncs at close (`STATUS.CLOSING`) and re-create divergence for any future recovery-time accumulator that requires both sides to move.

Hook A's `persistCountDelta` writes go through the atomic op's `pageChangesMap`, not directly to disk. They become durable only after `commitChanges` completes the WAL flush and cache application. If `commitChanges` throws, the persist's effects are discarded along with the rest of the atomic op's writes. The conversion catch in Hook A is therefore only required for failures *during* `persistCountDelta` itself (BTree underflow, IO at page allocation), not for failures during `commitChanges`.

The deliverable: hook points in `endAtomicOperation` (persist before `commitChanges`; apply + histogram-apply after `commitChanges`, before `releaseLocks`); persist-failure-to-rollback conversion with explicit `moveToErrorStateIfNeeded(persistFailure)` and typed re-raise; deletion of the three manual calls and their two surrounding catches in `AbstractStorage.commit`; visibility raise on the three methods to `public`; integration tests covering both the main-commit and standalone paths under rollback.

### Clarifications

Captured at Track Pre-Flight after Track 1 completion. Cross-track signals from Track 1's episode that inform Track 2 implementation:

- **Engine-mutator surface is package-private.** Track 1 relaxed `addToApproximate{Entries,Null}Count` on both `BTreeMultiValueIndexEngine` and `BTreeSingleValueIndexEngine` from `private` to package-private (under `internal/`, `final` classes); `AbstractStorage.applyIndexCountDeltas` at lines 2528–2529 is the sole production caller (PSI-confirmed). Hook B's broadened catch (`RuntimeException | AssertionError`) absorbs failures Track 1 converted from `assert updated >= 0` into `reportAndClampUnderflow` clamp-and-error.
- **`IndexCountDeltaHolder` engine-id keying.** Holder keys on the internal engine id (low 27 bits of `IndexAbstract.getIndexId`). Tests that inject or read a delta for a named index must mask the external id with `0x7FFFFFF`. Material for `EndAtomicOperationHookOrderingTest`.
- **Engine-class FQN orientation.** `com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine` and `com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine` for the two `addToApproximate{Entries,Null}Count` surfaces; `com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager` (no `v1`) for histogram apply.
- **`BTreeEngineTestFixtures.captureSevereOn` is public.** Track 1 Step 6 widened the JUL-capture helper to `public` for cross-package consumers; available for Track 2's apply-failure log-and-swallow assertions if useful.
- **`logAndPrepareForRethrow` overload asymmetry.** Only the `Error` overload (line 5827) and `Throwable` overload (line 5846) call `setInError`; the `RuntimeException` overload (line 5803) does not. Affects how a Hook A persist-failure `AssertionError` (re-raised after `releaseLocks`) interacts with any test that seeds in-error state via `logAndPrepareForRethrow`.
- **`RecordSerializationContext.executeOperations` as wrapper-bypassing injection point** (FQN: `com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationContext`)**.** Track 1 Step 2 broadened the four `AtomicOperationsManager` wrapper catches; every production path inside `AbstractStorage.commit`'s inner try now wraps an `AssertionError` as `RuntimeException` before reaching the pre-`endTxCommit` catch at line 2341. `RecordSerializationContext.executeOperations` pushes a throwable through a custom `RecordSerializationOperation` that bypasses the wrappers — reusable for any test that needs to verify Hook A's persist-failure-to-rollback conversion survives an `AssertionError` escaping the wrappers.

## Plan of Work

Four logical edits. Order matters because Hook A must land with its conversion catch before the manual call is deleted.

1. **Raise visibility** of `persistIndexCountDeltas`, `applyIndexCountDeltas`, `applyHistogramDeltas` on `AbstractStorage` from `private` to `public`. Matches the existing manager-callback surface on the same class (`moveToErrorStateIfNeeded`, `getName`, `checkErrorState`, `getSnapshotIndexSize` are all `public` despite being internal-only). The early-exit `if (holder == null) return;` already covers the recovery-time atomic-op cases at `AbstractStorage.java:766–860` where the holder is never accumulated; no status gate is needed and no new asserts are added. A `status == STATUS.OPEN` assert here would fire false-positives during `STATUS.MIGRATION` (open-time site at AbstractStorage:860) and `STATUS.CLOSING`.

2. **Add Hook A (persist) in `endAtomicOperation`** before `commitChanges`, with persist-failure-to-rollback conversion plus explicit `moveToErrorStateIfNeeded` to preserve today's error-mode contract:

   ```java
   if (error == null && !operation.isRollbackInProgress()) {
     var holder = operation.getIndexCountDeltas();
     if (holder != null) {
       try {
         storage.persistIndexCountDeltas(operation);
       } catch (RuntimeException | AssertionError persistFailure) {
         error = persistFailure;
         storage.moveToErrorStateIfNeeded(persistFailure);
         operation.rollbackInProgress();
       }
     }
     // (no histogram persist hook today — IndexHistogramManager writes lazily;
     //  reserved naming for a future persist parallel)
   }
   ```

   The inner try/catch converts the throw into a rollback signal so the subsequent `commitChanges` is skipped and `rollbackOperation` runs. The explicit `moveToErrorStateIfNeeded(persistFailure)` preserves today's contract: today's persist-failure path at AbstractStorage:2340 routes through `finally → rollback → endAtomicOperation → moveToErrorStateIfNeeded` and lands `setInError` for `IOException` and `RuntimeException`; `AssertionError` is skipped at `setInError`'s line 1769–1771 guard (Track 1's defense-in-depth). Hook A's explicit call reproduces that effect because Hook A runs after `endAtomicOperation`'s entry-level `moveToErrorStateIfNeeded(error=null)` no-op call, so the captured `persistFailure` would otherwise never reach `setInError`.

   After the inner-finally `releaseLocks`, the captured `error` is re-raised with a typed dispatch — `RuntimeException` short-circuits as-is; `AssertionError` wraps as `StorageException` via `BaseException.wrapException`. `IOException` is not in the dispatch because the inner catch cannot see it (`AbstractStorage.persistIndexCountDeltas` does not declare `IOException`); today's line 2341 catch reaches `IOException` because it surrounds `commitChanges`, not Hook A.

   ```java
   if (error != null) {
     if (error instanceof RuntimeException re) {
       throw re;
     }
     throw BaseException.wrapException(
         new StorageException(name, "Error during transaction commit"), error, name);
   }
   ```

   The wrap is structurally required: `endAtomicOperation` declares only `throws IOException`, so a raw `throw error;` on `Throwable` would not compile. Routing the re-raise through `StorageException` (a `RuntimeException` via `BaseException`) lands at `AbstractStorage.commit`'s outer `catch (RuntimeException)` at line 2425 → `logAndPrepareForRethrow(RuntimeException)` → no second `setInError` (the storage is already in error mode from the explicit call in the catch, except for `AssertionError` per the line 1769–1771 guard). Without the wrap, an `IOException` re-raise would land at `catch (Throwable)` at line 2429 → `logAndPrepareForRethrow(Throwable)` → `setInError` again — idempotent but ugly, and noisy in the JUL log.

   Bounded catch: `LinkageError`, `OutOfMemoryError`, `StackOverflowError`, and `InternalError` are deliberately not caught by Hook A. They escape and reach `commit`'s `catch (Error)` at line 2427 → `logAndPrepareForRethrow(Error)` → `setInError` — genuine VM errors still poison the storage. Matches Track 1's wrapper-catch precedent (`AtomicOperationsManager.executeInsideAtomicOperation` etc.) where the same triple is caught and other `Error` subclasses escape.

3. **Add Hook B (apply) in `endAtomicOperation`** after `commitChanges` succeeds, **before the inner-finally `releaseLocks`**, with the histogram parallel:

   ```java
   try {
     if (!operation.isRollbackInProgress()) {
       lsn = operation.commitChanges(commitTs, writeAheadLog);
     } else {
       lsn = null;
     }
     // ... atomicOperationsTable.{commit,rollback}Operation ...

     if (error == null) {
       var indexHolder = operation.getIndexCountDeltas();
       if (indexHolder != null) {
         try {
           storage.applyIndexCountDeltas(operation);
         } catch (RuntimeException | AssertionError applyFailure) {
           LogManager.instance().warn(this, "...", applyFailure);
         }
       }
       var histogramHolder = operation.getHistogramDeltas();
       if (histogramHolder != null) {
         try {
           storage.applyHistogramDeltas(operation);
         } catch (RuntimeException | AssertionError applyFailure) {
           LogManager.instance().warn(this, "...", applyFailure);
         }
       }
     }
   } finally {
     releaseLocks(operation);              // line 263 — still last
     operation.deactivate();
   }
   ```

   Cache-only contract: apply failure must not mask a successful commit. The catch covers `RuntimeException | AssertionError` (broadening matches Track 1's clamp+error path for the in-mem mutators). Placement before `releaseLocks` is load-bearing: per the lock-window analysis, the per-index lock is in `operation.lockedComponents()` and only `releaseLocks` releases it, so apply running before `releaseLocks` keeps the lock held.

4. **Delete the manual calls in `AbstractStorage.commit`**: line 2340 (`persistIndexCountDeltas(atomicOperation)` inside the inner try, before the `} catch (IOException | RuntimeException | AssertionError e)` at line 2341), lines 2364–2379 (the `try { applyIndexCountDeltas(atomicOperation); } catch (RuntimeException | AssertionError e) { warn }` block), lines 2380–2393 (the `try { applyHistogramDeltas(atomicOperation); } catch (RuntimeException | AssertionError e) { warn }` block). The cleanupSnapshotIndex try/catch at lines 2394–2403 is untouched. The pre-`endTxCommit` catch at line 2341 stays — Track 1 has already broadened it to `IOException | RuntimeException | AssertionError` for `commitIndexes` defense.

   Then **add integration tests** under `core/src/test/.../storage/impl/local/`:
   - `MainCommitCounterSyncTest` — exercise the consolidated path: commit a tx with index puts under nominal conditions; assert persisted EP page and in-memory counters land where the worked example predicts. Then commit a tx that fails at `commitIndexes` (forced IOException); assert rollback runs, counters stay at pre-tx values, no AssertionError escapes.
   - `ClearIndexApiRollbackTest` — invoke the `clearIndex` API, force a rollback at WAL flush, assert counters stay at pre-clear values. This test passes only after Track 3 lands (pure-delta encoding in `clear()`); the test starts as `@Ignore` with a comment pointing at Track 3 and flips to active in Track 3's regression test step.
   - `EndAtomicOperationHookOrderingTest` — pure unit test on the lifecycle ordering: Hook A runs before `commitChanges`; persist failure (injected IOException) converts to rollback, so `commitChanges` is skipped; Hook B runs after `commitChanges` and before `releaseLocks` (assert lock is still held during apply by recording lock state from a custom test component); Hook B failure is swallowed.

Ordering constraint: Step 1 (visibility raise) before Step 2 and 3 (the hooks need the surface). Step 2 (Hook A persist) and Step 3 (Hook B apply) before Step 4 (deletion of the manual calls), because deleting the manual calls before the hooks exist leaves the main commit path with no persist/apply at all.

Invariants to preserve: persisted side and in-memory side advance in lockstep at the WAL commit boundary on every path. The pre-`endTxCommit` catch at line 2341 (Track 1's broadened version) still owns `commitIndexes` failures. Recovery-time atomic ops remain no-ops via the existing `if (holder == null) return;` early-exit in the three target methods.

## Concrete Steps

1. Raise `persistIndexCountDeltas`, `applyIndexCountDeltas`, `applyHistogramDeltas` on `AbstractStorage` from `private` to `public` — risk: medium (default: visibility change on three storage-lifecycle methods; touches `AbstractStorage` only; no new callers wired yet)  [x]  commit: a10c7d9394
2. Add Hook A (persist) inside `AtomicOperationsManager.endAtomicOperation` before `commitChanges`: capture `RuntimeException | AssertionError` (`AbstractStorage.persistIndexCountDeltas` does not declare `IOException`, so the inner catch cannot reach it), call `storage.moveToErrorStateIfNeeded(persistFailure)`, set `error = persistFailure`, call `operation.rollbackInProgress()`; after the inner-finally `releaseLocks`, typed re-raise (`RuntimeException` short-circuits as-is, `AssertionError` wraps as `StorageException` via `BaseException.wrapException`). Add inline comments above Hook A noting the bounded catch (`LinkageError | OutOfMemoryError | StackOverflowError | InternalError` escape) and the `moveToErrorStateIfNeeded`-via-`setInError` line 1769–1771 guard for `AssertionError` — risk: high (crash-safety: durability ordering at the WAL commit boundary; load-bearing for the corrected-trace `setInError` contract on persist failure)  [x]  commit: 20908b4a91
3. Add Hook B (apply + histogram-apply parallel) inside `AtomicOperationsManager.endAtomicOperation` after `commitChanges` and **before the inner-finally `releaseLocks`**, with `RuntimeException | AssertionError` log-and-swallow for each apply call. Add an inline source-comment block above Hook B citing (a) the per-index lock acquired at `lockIndexes` (`AbstractStorage.java:2255`), (b) `releaseLocks` as the lock-release site, (c) Tracks 3 and 4 as dependents, (d) `EndAtomicOperationHookOrderingTest` as the regression guard. Add `EndAtomicOperationHookOrderingTest` under `core/src/test/.../paginated/atomicoperations/`: assert Hook A runs before `commitChanges`; injected `IOException` converts to rollback (skip `commitChanges`); Hook B runs after `commitChanges` and before `releaseLocks` with the per-index lock still held; Hook B failure is swallowed; nested-op lock release assertion (A4 — outer op's `lockedComponents()` set is unchanged after Hook B returns) — risk: high (concurrency: lock-scope invariant load-bearing; Hook B placement governs whether the per-index lock is held during apply)  [x]  commit: 3ec8bffc19
4. Delete the manual calls in `AbstractStorage.commit` at lines 2340 (`persistIndexCountDeltas`), 2365 (`applyIndexCountDeltas`), 2381 (`applyHistogramDeltas`) and their post-`endTxCommit` catches at lines 2366 and 2382; the pre-`endTxCommit` catch at line 2341 stays (Track 1's broadened version). Add `MainCommitCounterSyncTest` under `core/src/test/.../storage/impl/local/`: nominal-commit path asserts persisted EP page and in-memory counters land where expected; commitIndexes-IOException path asserts rollback runs, counters stay at pre-tx values, no `AssertionError` escapes. Add `ClearIndexApiRollbackTest` under the same directory, `@Ignore`-annotated with a pointer comment to Track 3's regression-test step. Sweep doc-comments in tests / main that name the manual call sites (12 files per A11) to track the deleted call sites. Optional in this step at decomposer's call: A5(ii) interim reflective IndexCountDelta injection for the `clearIndex` Hook A/B coverage gap; A8 broadening `cleanupSnapshotIndex` catch from `RuntimeException` to `RuntimeException | AssertionError` for symmetry with Track 1's pattern — risk: medium (deletion of error-handling code in `AbstractStorage.commit` plus new test infrastructure; the dangerous wiring already landed in steps 2 and 3 under HIGH-tag review)  [ ]

## Episodes

### Step 1 — commit a10c7d9394, 2026-05-23T13:38Z [ctx=safe]
**What was done:** Raised `persistIndexCountDeltas`, `applyIndexCountDeltas`, and `applyHistogramDeltas` on `AbstractStorage` from `private` to `public`. The change is a pure surface widening: no method body, Javadoc, or invocation site was touched. The new visibility matches the existing manager-callback surface on the same class (`moveToErrorStateIfNeeded`, `getName`, `checkErrorState`, `getSnapshotIndexSize`); `AtomicOperationsManager` sits in a sibling package, so package-private would not have crossed.

**What was discovered:** PSI find-usages on the three methods confirmed the references match the design.md inventory exactly. `applyIndexCountDeltas` has two references (call site at `AbstractStorage.java:2365` and a Javadoc `{@link}` from `persistIndexCountDeltas` at `:2482`); the other two methods have one reference each (their respective call sites). No external caller exists yet — the expected state before Steps 2 and 3 wire the manager-side hooks. The Javadoc `{@link}` resolution is unaffected by the visibility raise.

**What changed from the plan:** none.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (modified)

### Step 2 — commit 20908b4a91, 2026-05-23T17:44Z [ctx=info]
**What was done:** Added Hook A (persist hook + typed re-raise) to `AtomicOperationsManager.endAtomicOperation` before `commitChanges`: capture `RuntimeException | AssertionError`, route through `storage.moveToErrorStateIfNeeded(persistFailure)`, set the local `currentError` slot, call `operation.rollbackInProgress()` so the subsequent `commitChanges` is skipped, then after the inner-finally `releaseLocks` re-raise as `RuntimeException` (short-circuit) or wrap `AssertionError` as `StorageException` via `BaseException.wrapException`. Added a persisted-state latch (`IndexCountDeltaHolder.persisted`) and wired `AbstractStorage.persistIndexCountDeltas` to set the latch at end of work, closing the dual-invocation window until Step 4 removes the inline call. Added `EndAtomicOperationPersistHookTest` with 15 Mockito tests covering the persist path (entry-level + thrown-error `moveToErrorStateIfNeeded` order, locks-released-before-throw ordering on the AssertionError path, wrap-as-`StorageException` `dbName` and message pins, holder-null short-circuit, holder-already-persisted short-circuit, isInError-not-consulted contract, non-empty holder forwarding without latch flip) plus 4 sibling VM-error tests routing through a shared `assertVmErrorEscapesUnconverted` helper (`OutOfMemoryError`, `LinkageError`, `StackOverflowError`, `InternalError`). Extracted `EndAtomicOperationHookTestSupport` (package-private) carrying `primeFreezer`, `mockOperation`, `mockStorage`, and the `DEFAULT_COMMIT_TS` constant so Step 3 (Hook B) can reuse the same test fixtures without copy-paste. Iteration 1 dim-review (9 agents in parallel) surfaced 22 findings; the orchestrator applied the M11 plan-file fix directly (commit `986b49dda6`) and the iteration-2 `Review fix:` commit `20908b4a91` resolved the remaining M1–M10 must-fix and S1–S10 suggestions in a single respawn. Gate-check iteration 2 across 6 dimensions returned `PASS` on every dimension with no new findings.

**What was discovered:** `AbstractStorage.isInError()` is `protected`, not package-private or public. Mockito tests in the sibling `paginated.atomicoperations` package cannot stub it with `when(storage.isInError()).thenReturn(true)` — the compiler rejects the call with an access-violation. M6's test (`persistHookDoesNotConsultIsInErrorOnNullEntryPath`) now pins the contract by inspection of Hook A's gate (no `isInError` call site) plus a call-shape assertion on the null-error entry path. Material for Track 3: the hook-ordering test will hit the same constraint if it needs to drive storage error-state independently; resolutions are (a) widen `isInError` to package-private, or (b) use a partial Mockito spy delegating `isInError` to the real `AbstractStorage` instance.

The implementation-plan.md narrative at lines 85, 87, and 90 still describes Hook A's catch as `IOException | RuntimeException | AssertionError` (the original three-element design before the IOException-not-declared observation landed). M11 narrowed the five `track-2.md` citations to match as-implemented; the equivalent drift in `implementation-plan.md` is left for Phase C track review or Phase 4 design-final to sweep.

The labels "Hook A" and "Hook B" appear in production source comments — six pre-existing references at commit `46a8edfa06`, three more added by `20908b4a91` (inside the new assert message, the `currentError != error` clarifier, and the test class Javadoc). Per the ephemeral-identifier rule's Allowed list, "Hook A" is not a Track-N / Step-N / finding-ID label and stays. Track 3 and Track 4 will likely keep the naming for symmetry; the orchestrator may surface a deferred plan correction renaming to durable phrases ("lifecycle persist hook" / "lifecycle apply hook") before Phase 4 builds `design-final.md`. See Surprises & Discoveries.

**What changed from the plan:** Hook A's actual catch is `RuntimeException | AssertionError`, not the `IOException | RuntimeException | AssertionError` originally written. `AbstractStorage.persistIndexCountDeltas` does not declare `IOException`, so the inner catch cannot reach `IOException`. Track-2.md narrative, Concrete Steps Step 2 row, Validation and Acceptance, and Interfaces and Dependencies were narrowed to match in commit `986b49dda6`. M6's test was implemented as contract-inspection-plus-call-shape pin rather than the originally-suggested `when(isInError).thenReturn(true)` stub because `isInError` is `protected`; the contract under test (Hook A does not consult `isInError`) is still pinned, with the test prose naming the access-level limitation explicitly. No deviation affects Step 3 or Step 4 beyond the cross-track hints above.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDeltaHolder.java` (modified)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (modified)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookTestSupport.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationPersistHookTest.java` (new)

### Step 3 — commit 3ec8bffc19, 2026-05-24T12:11Z [ctx=warning]
**What was done:** Added Hook B (apply + histogram-apply parallel) to `AtomicOperationsManager.endAtomicOperation` after `commitChanges` and before the inner-finally `releaseLocks`, with `RuntimeException | AssertionError` log-and-swallow for each apply call. Extracted `tryApply(Runnable, String)` helper inside the manager so both apply branches route through one place with a shared catch surface. Hook B's gate is `currentError == null && !operation.isRollbackInProgress()` matching Hook A's symmetry; the `!operation.isRollbackInProgress()` clause is structurally redundant on this path today (the only failure source between the persist gate and the apply gate is `commitChanges`, whose throw bypasses Hook B via the inner-finally) but is a cheap symmetry pin against future intermediate mutators that might flip the rollback state in place. Hoisted `holder.setApplied()` to the top of `AbstractStorage.applyIndexCountDeltas` / `applyHistogramDeltas` (after null + already-applied guards, before the per-engine loop) so a partial-loop throw caught by Hook B's swallow still latches the holder, eliminating the double-apply risk from the legacy inline call at `AbstractStorage.commit:2365 / 2381`. Added `EndAtomicOperationHookOrderingTest` (under `paginated/atomicoperations/`) with Mockito tests covering Hook A → `commitChanges` → Hook B ordering, persist-failure rollback (skip-commit + skip-Hook-B), Hook B running with the per-index lock held during apply (size + ownership snapshots + `InOrder` pin), Hook B failure swallow with `releaseLocks` still running, four VM-error escape tests on Hook B mirroring Hook A's, mixed-null holder cases, nested-op outer-applied-latch isolation, and the apply ordering `applyIndexCountDeltas` then `applyHistogramDeltas` then `releaseLocks`. Added `AbstractStorageApplyDeltaTest` (storage package) with 6 tests pinning the storage-side `isApplied()` short-circuit gate and the partial-loop latch-up-front contract on the real production methods via `doCallRealMethod`. Added `appliedLatchIsIdempotent` plus `appliedAndPersistedLatchesAreIndependent` to `IndexCountDeltaHolderTest` and `HistogramDeltaHolderTest` mirroring Step 2's `persistedLatchIsIdempotent` pattern.

Recovery: this step landed across two commits. Step 3's initial implementer commit (`72e7e5a107`) reached HEAD via a prior session's `RESULT_MISSING` commit-as-is path and had not yet been dim-reviewed. This session's dim-review iter-1 fanned out 9 agents in parallel, synthesised 12 must-fix items (M1–M12) plus 1 suggestion (S1), respawned a `FIX_REVIEW_FINDINGS` implementer that exhausted its sub-agent message budget on the coverage build's wait (`RESULT_MISSING` #2), then re-spawned with a hardened pacing addendum that produced the clean `Review fix:` commit `3ec8bffc19`. Iter-2 gate-check across 8 dimensions (crash-safety, bugs-concurrency, code-quality, test-crash-safety, test-behavior, test-completeness, test-structure, test-concurrency) returned PASS on every dimension with no new findings. Coverage on the changed lines reached 97.8 % line (87/89) and 90.7 % branch (49/54), well above the 85 / 70 gate.

**What was discovered:** Mockito's `doCallRealMethod` on a bare mock of `AbstractStorage` leaves instance fields uninitialised; the `indexEngines` list is null, which surfaces as `NullPointerException` at the first `indexEngines.size()` call inside the per-engine loop. The `AbstractStorageApplyDeltaTest` partial-loop tests use this NPE as the simulated mid-loop throw rather than bringing up a real storage shell; the signature matches the diagnostic shape M1's latch-up-front contract defends against.

Cross-track impact for Tracks 3 and 4: Hook B's gate now matches Hook A's symmetry, so downstream pure-delta consumers (`clear()` and `buildHistogramAfterFill`) can assume apply runs only on the commit success path. If a future consumer wants to observe apply on a rolled-back operation, the gate symmetry must be revisited. `AbstractStorage.applyIndexCountDeltas` / `applyHistogramDeltas` now latch the holder up front; any future refactor splitting apply into compute-deltas and publish-deltas phases must keep the latch flip on the entry side or restore equivalent partial-loop safety. The `tryApply` helper inside `AtomicOperationsManager` centralises the cache-only catch surface; future apply branches added by downstream tracks should route through the same helper rather than re-implementing the catch shape locally. See Surprises & Discoveries for the durable forms of these notes.

S1 suggestion handling: the iter-1 suggestion (assert `holder.isApplied()` inside Hook B's body after each apply call) was incompatible with the existing mock-storage test boundary. Hook tests in `EndAtomicOperationHookOrderingTest` stub `storage.applyIndexCountDeltas` and `applyHistogramDeltas` with `doNothing` or `doAnswer` that do not call `setApplied()`, so a hook-site assert would fire under tests even though it passes in production. The fix uses `AbstractStorageApplyDeltaTest` to pin the production-path latch contract directly on the real methods via `doCallRealMethod`, which is a stronger guarantee than the manager-site assert would have been. See Decision Log.

**What changed from the plan:** S1 (dim-review iter-1 suggestion) was rerouted from a manager-site assert to a production-path test pin (`AbstractStorageApplyDeltaTest`) because the manager-site assert is incompatible with the mock-storage test boundary in `EndAtomicOperationHookOrderingTest`. The contract under test (apply methods latch the holder before any throw can escape) is pinned more strongly by the production-path test than it would have been by the hook-site assert. No change to the plan's track decomposition or to Step 3's roster description; the rerouting is recorded in the Decision Log so future reviewers proposing the same belt-and-suspenders are pointed at this episode.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (modified)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/HistogramDeltaHolderTest.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDeltaHolderTest.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageApplyDeltaTest.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookOrderingTest.java` (new)

**Critical context:** The Hook B `!operation.isRollbackInProgress()` clause is redundant on the current code path but rides along as a symmetry pin with Hook A. If a future intermediate mutator between Hook A and Hook B can flip `isRollbackInProgress` in place, the clause becomes load-bearing. The `setApplied()` latch-up-front contract is the boundary between Hook B's bounded catch and the legacy inline call at `AbstractStorage.commit:2365 / 2381`: until Step 4 removes the inline call, the latch is what prevents the swallow from triggering a double-apply on re-entry. Step 4 reviewers should re-verify this when the inline call is deleted (the latch then becomes optional defense-in-depth rather than a correctness requirement).

## Validation and Acceptance

After Track 2 lands:

- `endAtomicOperation` invokes Hook A (persist) before `commitChanges` and Hook B (apply + histogram-apply) after `commitChanges` but before the inner-finally `releaseLocks`. Each hook runs at most once per atomic op; no idempotency flags are needed.
- A persist failure in Hook A (`RuntimeException | AssertionError`; the inner catch cannot see `IOException` because `AbstractStorage.persistIndexCountDeltas` does not declare it) is captured into `error`, routed through `storage.moveToErrorStateIfNeeded(persistFailure)` to preserve today's error-mode contract, then converted to a rollback signal via `operation.rollbackInProgress()` so the subsequent `commitChanges` is skipped. After the inner-finally `releaseLocks`, the captured `error` is re-raised with a typed dispatch: `RuntimeException` short-circuits as-is; `AssertionError` wraps as `StorageException`. The outer `catch (Error)` cascade cannot be reached via this path.
- An apply failure in Hook B (`RuntimeException | AssertionError`) is logged as a warn and swallowed (cache-only contract).
- The rollback path skips both hooks. When `endAtomicOperation` is entered with a non-null `error` (rollback path from `AbstractStorage.rollback`), the entry-level `moveToErrorStateIfNeeded(error)` lands `setInError` (subject to the `AssertionError` line 1769–1771 guard) and `operation.rollbackInProgress()` is called. Hook A's `error == null && !operation.isRollbackInProgress()` gate skips persist; Hook B's `error == null` gate skips apply. The deltas are discarded with the rest of the atomic op's writes.
- The manual calls at `AbstractStorage.commit` lines 2340, 2365, 2381 are deleted along with their post-`endTxCommit` catches at 2366 and 2382. The cleanupSnapshotIndex try/catch at 2394–2403 is untouched. The pre-`endTxCommit` catch at line 2341 (Track 1's broadened version) is the only catch around the inner try.
- The per-index lock acquired by `lockIndexes` at AbstractStorage:2255 is held when Hook B (apply) runs; the test `EndAtomicOperationHookOrderingTest` records the lock state during apply and asserts it.
- The integration tests pass on the main-commit path; `ClearIndexApiRollbackTest` is staged for Track 3.
- Between Track 2 land and Track 3 land, Hook A/B coverage on the `clearIndex` API path is structurally zero. Today's `clear()` writes the in-memory `AtomicLong` directly without accumulating an `IndexCountDelta`, so Hook B's `if (indexHolder != null)` early-exits. `ClearIndexApiRollbackTest` carries an `@Ignore` annotation with a comment pointing at Track 3; Track 3's regression-test step un-`@Ignore`s it.

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files**:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (visibility raise on three private methods; deletion of the three manual calls at lines 2340, 2365, 2381 and the surrounding catches at 2366 and 2382)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (Hook A persist + conversion catch on `RuntimeException | AssertionError` + explicit `moveToErrorStateIfNeeded(persistFailure)` before `commitChanges`; Hook B apply + histogram-apply parallel after `commitChanges` and before the inner-finally `releaseLocks`; typed re-raise block added after `releaseLocks` — `RuntimeException` short-circuits as-is, `AssertionError` wraps as `StorageException`)
- New integration tests under `core/src/test/.../storage/impl/local/`

**Out of scope**:
- `IndexCountDelta` flag fields — under consolidation no flags are needed.
- `clear()` and `buildInitialHistogram()` pure-delta conversion (Tracks 3 and 4).
- Histogram serialization changes; the persist-side histogram parallel is naming reserved only — `IndexHistogramManager` writes lazily today.

**Inter-track dependencies**:
- **Depends on Track 1**: Hook B's `catch (RuntimeException | AssertionError applyFailure)` swallows the four engine-mutator failures Track 1 converts from `assert updated >= 0` into clamp+error. Without Track 1, an `AssertionError` raised inside Hook B's apply (e.g., from the pre-clamp engine code) would still need to be caught here, but the diagnostic quality drops.
- **Blocks Tracks 3 and 4**: the `clearIndex` API and `buildHistogramAfterFill` paths cannot adopt pure-delta encoding until the hooks exist to consume the accumulated deltas. After this track, the standalone-atomic-op paths route through the same single gate as the main commit.

**Library/function signatures relevant to this track**:
- `AtomicOperationsManager.endAtomicOperation(AtomicOperation operation, Throwable error)` — the lifecycle method; gains Hook A, Hook B, and the post-`releaseLocks` typed re-raise block.
- `AbstractStorage.persistIndexCountDeltas(AtomicOperation)` / `applyIndexCountDeltas(AtomicOperation)` / `applyHistogramDeltas(AtomicOperation)` — visibility rises from `private` to `public` on `AbstractStorage`, matching the existing manager-callback surface (`moveToErrorStateIfNeeded`, `getName`, `checkErrorState`, `getSnapshotIndexSize`).
- `AbstractStorage.moveToErrorStateIfNeeded(Throwable)` — existing public manager-callback; Hook A's catch calls this explicitly for persist failures to preserve today's `setInError` contract (with `AssertionError` skipped at `setInError`'s line 1769–1771 guard).
- `AtomicOperation.getIndexCountDeltas()` / `getHistogramDeltas()` — read-only access to the per-tx holders.
- `LogManager.instance().warn(this, String, Throwable)` — the warn helper for apply failures.

## Base commit

54ffb540687ed0a78622bb96e86123c5e58af082
