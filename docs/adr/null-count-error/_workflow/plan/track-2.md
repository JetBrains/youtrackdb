# Track 2: Consolidate persist + apply into endAtomicOperation

## Purpose / Big Picture
After this track, every counter sync — main commit, `clearIndex` API, `IndexAbstract.buildHistogramAfterFill` — advances through `AtomicOperationsManager.endAtomicOperation` as the single lifecycle gate, under the per-index lock acquired at `lockIndexes` (AbstractStorage:2233). The lock-window race that today's manual apply at AbstractStorage:2333 leaves open (apply runs after `endAtomicOperation`'s inner-finally `releaseLocks` has released the per-index lock) is structurally closed.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Move `persistIndexCountDeltas` / `applyIndexCountDeltas` / `applyHistogramDeltas` into `AtomicOperationsManager.endAtomicOperation`. Persist runs before `commitChanges` with a persist-failure-to-rollback conversion (catches `IOException | RuntimeException | AssertionError`). Apply runs after `commitChanges` but before the inner-finally `releaseLocks`, so the per-index lock acquired by `lockIndexes` at AbstractStorage:2233 is still held during apply; failures inside apply are logged and swallowed (cache-only contract). The manual calls at `AbstractStorage.commit` lines 2318, 2333, 2345 and their post-`endTxCommit` catches at lines 2334 and 2346 are deleted. After this track, Tracks 3 and 4 can land pure-delta encoding without re-introducing the race.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

`AtomicOperationsManager.endAtomicOperation` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java:231–269` already owns the commit lifecycle. The relevant shape today:

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
      releaseLocks(operation);                                    // line 263
      operation.deactivate();
    }
  } finally {
    writeOperationsFreezer.endOperation();
  }
}
```

Two methods on `AbstractStorage` resolve to one-liners that delegate here: `endTxCommit` at `AbstractStorage.java:4506` is `endAtomicOperation(op, null)`; `rollback(error, op)` at `AbstractStorage.java:3593` is `endAtomicOperation(op, error)`. Both the commit-path success branch and the rollback branch already converge on `endAtomicOperation` — this track makes that convergence the only place counter sync happens.

The lock-window race that motivates consolidation: `lockIndexes` at AbstractStorage:2233 calls `acquireExclusiveLockTillOperationComplete` per index (via `BTree.acquireAtomicExclusiveLock` at `BTree.java:1733`), which adds the component to `operation.lockedComponents()` (`AtomicOperationsManager.java:312–313`). `releaseLocks` at `AtomicOperationsManager.java:275–293` iterates `lockedComponents()` and releases each one. The inner-finally `releaseLocks` call at line 263 of `AtomicOperationsManager.endAtomicOperation` therefore releases the per-index lock *inside* the lifecycle method, before `endAtomicOperation` returns. The subsequent `ensureThatComponentsUnlocked` at AbstractStorage:2368 calls `releaseLocks` again; its idempotent `isExclusiveOwner()` check (commented at lines 277–279 of the manager) confirms this is a defensive double-call that finds nothing to do. So today's `applyIndexCountDeltas` at AbstractStorage:2333 runs *after* the per-index lock has been released, leaving a 30-line race window between lock release and the apply.

The manager has a `storage` field (`AtomicOperationsManager.java:55`, set in the ctor at line 71), so the manager can call back into `AbstractStorage`. `persistIndexCountDeltas` (`AbstractStorage.java:2447`), `applyIndexCountDeltas` (`AbstractStorage.java:2472`), and `applyHistogramDeltas` (`AbstractStorage.java:2501`) are currently `private`; visibility rises to package-private. The manager and storage live in different packages (`paginated.atomicoperations` and `impl.local`), so package-private requires either a small accessor surface on `AbstractStorage` or a JPMS-style same-module exposure. Decomposition picks the cleanest fit; the surface is three methods.

`IndexCountDelta` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDelta.java` keeps its existing fields `totalDelta` and `nullDelta`. No flags are added: under the single-lifecycle-gate design each hook runs at most once per atomic op, so idempotency is structural.

`AtomicOperation.getOrCreateIndexCountDeltas()` / `getIndexCountDeltas()` (referenced at `AbstractStorage.java:2448`, `:2473` and in `IndexCountDelta.accumulate` at line 63) return the per-tx holder. `AtomicOperation.getHistogramDeltas()` at `AbstractStorage.java:2502` is the histogram parallel.

Recovery-time `executeInsideAtomicOperation` call sites at `AbstractStorage.open` lines 766, 779, 794, 797, 799, 800, 809, 811, 860 all run before `status = STATUS.OPEN` at line 861. Research confirmed none accumulate `IndexCountDelta` today, so the lifecycle hook is a no-op via the existing `if (holder == null) return;` early-exit in the three target methods. No state gate is wired: skipping the hooks on non-OPEN states would drop counter syncs at close (`STATUS.CLOSING`) and re-create divergence for any future recovery-time accumulator that requires both sides to move.

The deliverable: hook points in `endAtomicOperation` (persist before `commitChanges`; apply + histogram-apply after `commitChanges`, before `releaseLocks`); persist-failure-to-rollback conversion; deletion of the three manual calls and their two surrounding catches in `AbstractStorage.commit`; visibility raise on the three private methods; integration tests covering both the main-commit and standalone paths under rollback.

## Plan of Work

Four logical edits. Order matters because Hook A must land with its conversion catch before the manual call is deleted.

1. **Raise visibility** of `persistIndexCountDeltas`, `applyIndexCountDeltas`, `applyHistogramDeltas` on `AbstractStorage` from `private` to package-private (or expose via a tight accessor surface the manager can call). Add a debug-time `assert status == STATUS.OPEN : "<method> reached with non-null holder while status=" + status` inside each of the three methods, immediately after the existing `if (holder == null) return;` early-exit. The assert lives inside `AbstractStorage`, so no accessor on the surface is needed; the `status` field is reached directly inside the class that owns it. Production behavior is unchanged whether asserts are enabled or not. No runtime gate is introduced — when asserts are disabled or pass, the hook proceeds normally so a legitimate future recovery-time accumulator advances both sides.

2. **Add Hook A (persist) in `endAtomicOperation`** before `commitChanges`, with persist-failure-to-rollback conversion:

   ```java
   if (error == null && !operation.isRollbackInProgress()) {
     var holder = operation.getIndexCountDeltas();
     if (holder != null) {
       try {
         storage.persistIndexCountDeltas(operation);
       } catch (IOException | RuntimeException | AssertionError persistFailure) {
         error = persistFailure;
         operation.rollbackInProgress();
       }
     }
     // (no histogram persist hook today — IndexHistogramManager writes lazily;
     //  reserved naming for a future persist parallel)
   }
   ```

   The inner try/catch converts the throw into a rollback signal so the subsequent `commitChanges` is skipped and `rollbackOperation` runs. The throw is re-raised at `if (error != null) throw error;` added after the inner-finally `releaseLocks`. Without this conversion the throw would escape `endAtomicOperation` and trip the outer `catch (Error)` cascade — D2's "Bug B for a different trigger".

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

4. **Delete the manual calls in `AbstractStorage.commit`**: line 2318 (`persistIndexCountDeltas(atomicOperation)` inside the inner try, before the `} catch (IOException | RuntimeException e)` at line 2319), lines 2332–2343 (the `try { applyIndexCountDeltas(atomicOperation); } catch (RuntimeException e) { warn }` block), lines 2344–2354 (the `try { applyHistogramDeltas(atomicOperation); } catch (RuntimeException e) { warn }` block). The cleanupSnapshotIndex try/catch at lines 2355–2364 is untouched. The pre-`endTxCommit` catch at line 2319 stays — Track 1 broadens it to `AssertionError` for `commitIndexes` defense.

   Then **add integration tests** under `core/src/test/.../storage/impl/local/`:
   - `MainCommitCounterSyncTest` — exercise the consolidated path: commit a tx with index puts under nominal conditions; assert persisted EP page and in-memory counters land where the worked example predicts. Then commit a tx that fails at `commitIndexes` (forced IOException); assert rollback runs, counters stay at pre-tx values, no AssertionError escapes.
   - `ClearIndexApiRollbackTest` — invoke the `clearIndex` API, force a rollback at WAL flush, assert counters stay at pre-clear values. This test passes only after Track 3 lands (pure-delta encoding in `clear()`); the test starts as `@Ignore` with a comment pointing at Track 3 and flips to active in Track 3's regression test step.
   - `EndAtomicOperationHookOrderingTest` — pure unit test on the lifecycle ordering: Hook A runs before `commitChanges`; persist failure (injected IOException) converts to rollback, so `commitChanges` is skipped; Hook B runs after `commitChanges` and before `releaseLocks` (assert lock is still held during apply by recording lock state from a custom test component); Hook B failure is swallowed.

Ordering constraint: Step 1 (visibility raise + `isOpen` accessor) before Step 2 and 3 (the hooks need the surface). Step 2 (Hook A persist) and Step 3 (Hook B apply) before Step 4 (deletion of the manual calls), because deleting the manual calls before the hooks exist leaves the main commit path with no persist/apply at all.

Invariants to preserve: persisted side and in-memory side advance in lockstep at the WAL commit boundary on every path. The pre-`endTxCommit` catch at line 2319 (Track 1's broadened version) still owns `commitIndexes` failures. Recovery-time atomic ops remain no-ops via the existing `if (holder == null) return;` early-exit in the three target methods.

## Concrete Steps

## Episodes

## Validation and Acceptance

After Track 2 lands:

- `endAtomicOperation` invokes Hook A (persist) before `commitChanges` and Hook B (apply + histogram-apply) after `commitChanges` but before the inner-finally `releaseLocks`. Each hook runs at most once per atomic op; no idempotency flags are needed.
- A persist failure in Hook A (any of `IOException | RuntimeException | AssertionError`) converts to a rollback signal so the subsequent `commitChanges` is skipped; the failure is re-raised at `throw error` added after `releaseLocks`. The outer `catch (Error)` cascade cannot be reached via this path.
- An apply failure in Hook B (`RuntimeException | AssertionError`) is logged as a warn and swallowed (cache-only contract).
- The manual calls at `AbstractStorage.commit` lines 2318, 2333, 2345 are deleted along with their post-`endTxCommit` catches at 2334 and 2346. The cleanupSnapshotIndex try/catch at 2355–2364 is untouched. The pre-`endTxCommit` catch at line 2319 (Track 1's broadened version) is the only catch around the inner try.
- The per-index lock acquired by `lockIndexes` at AbstractStorage:2233 is held when Hook B (apply) runs; the test `EndAtomicOperationHookOrderingTest` records the lock state during apply and asserts it.
- The integration tests pass on the main-commit path; `ClearIndexApiRollbackTest` is staged for Track 3.
- `persistIndexCountDeltas`, `applyIndexCountDeltas`, and `applyHistogramDeltas` each carry a debug-time `assert status == STATUS.OPEN` immediately after the `if (holder == null) return;` early-exit. The assert identifies the offending method in its message; production behavior is unchanged whether asserts are enabled or not.

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files**:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (visibility raise on three private methods; `isOpen()` accessor; deletion of the three manual calls at lines 2318, 2333, 2345 and the surrounding catches at 2334 and 2346)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (Hook A persist + conversion catch before `commitChanges`; Hook B apply + histogram-apply parallel after `commitChanges` and before the inner-finally `releaseLocks`; `throw error` added after `releaseLocks` to re-raise persist failures)
- New integration tests under `core/src/test/.../storage/impl/local/`

**Out of scope**:
- `IndexCountDelta` flag fields — under consolidation no flags are needed.
- `clear()` and `buildInitialHistogram()` pure-delta conversion (Tracks 3 and 4).
- Histogram serialization changes; the persist-side histogram parallel is naming reserved only — `IndexHistogramManager` writes lazily today.

**Inter-track dependencies**:
- **Depends on Track 1**: Hook B's `catch (RuntimeException | AssertionError applyFailure)` swallows the four engine-mutator failures Track 1 converts from `assert updated >= 0` into clamp+error. Without Track 1, an `AssertionError` raised inside Hook B's apply (e.g., from the pre-clamp engine code) would still need to be caught here, but the diagnostic quality drops.
- **Blocks Tracks 3 and 4**: the `clearIndex` API and `buildHistogramAfterFill` paths cannot adopt pure-delta encoding until the hooks exist to consume the accumulated deltas. After this track, the standalone-atomic-op paths route through the same single gate as the main commit.

**Library/function signatures relevant to this track**:
- `AtomicOperationsManager.endAtomicOperation(AtomicOperation operation, Throwable error)` — the lifecycle method; gains Hook A, Hook B, and the post-`releaseLocks` `throw error` re-raise.
- `AbstractStorage.persistIndexCountDeltas(AtomicOperation)` / `applyIndexCountDeltas(AtomicOperation)` / `applyHistogramDeltas(AtomicOperation)` — visibility rises from `private` to package-private (or a single package-private accessor surface).
- `AtomicOperation.getIndexCountDeltas()` / `getHistogramDeltas()` — read-only access to the per-tx holders.
- `LogManager.instance().warn(this, String, Throwable)` — the warn helper for apply failures.
