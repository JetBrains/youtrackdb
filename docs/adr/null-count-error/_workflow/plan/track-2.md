# Track 2: Consolidate persist + apply into endAtomicOperation

## Purpose / Big Picture
After this track, every counter sync — main commit, `clearIndex` API, `IndexAbstract.buildHistogramAfterFill` — advances through `AtomicOperationsManager.endAtomicOperation` as the single lifecycle gate, under the per-index lock acquired at `lockIndexes` (AbstractStorage:2255). The lock-window race that today's manual apply at AbstractStorage:2365 leaves open (apply runs after `endAtomicOperation`'s inner-finally `releaseLocks` has released the per-index lock) is structurally closed.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Move `persistIndexCountDeltas` / `applyIndexCountDeltas` / `applyHistogramDeltas` into `AtomicOperationsManager.endAtomicOperation`. Persist runs before `commitChanges` with a persist-failure-to-rollback conversion (catches `IOException | RuntimeException | AssertionError`). Apply runs after `commitChanges` but before the inner-finally `releaseLocks`, so the per-index lock acquired by `lockIndexes` at AbstractStorage:2255 is still held during apply; failures inside apply are logged and swallowed (cache-only contract). The manual calls at `AbstractStorage.commit` lines 2340, 2365, 2381 and their post-`endTxCommit` catches at lines 2366 and 2382 are deleted. After this track, Tracks 3 and 4 can land pure-delta encoding without re-introducing the race.

## Progress
- [x] 2026-05-23T10:44Z [ctx=info] Review + decomposition complete
- [ ] Step implementation
  - [x] 2026-05-23T13:38Z [ctx=safe] Step 1 complete (commit a10c7d9394)
  - **PAUSED 2026-05-23 at Step 2 dim-review iter-1 complete pending FIX_REVIEW_FINDINGS respawn**
    - Handoff: docs/adr/null-count-error/_workflow/handoff-track-2-phaseB.md
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

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
       } catch (IOException | RuntimeException | AssertionError persistFailure) {
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

   After the inner-finally `releaseLocks`, the captured `error` is re-raised with the same exception-type contract today's line 2341 catch maintains — `RuntimeException` short-circuits as-is; `IOException` and `AssertionError` wrap as `StorageException`:

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
2. Add Hook A (persist) inside `AtomicOperationsManager.endAtomicOperation` before `commitChanges`: capture `IOException | RuntimeException | AssertionError`, call `storage.moveToErrorStateIfNeeded(persistFailure)`, set `error = persistFailure`, call `operation.rollbackInProgress()`; after the inner-finally `releaseLocks`, typed re-raise (`RuntimeException` short-circuits as-is, `IOException` and `AssertionError` wrap as `StorageException` via `BaseException.wrapException`). Add inline comments above Hook A noting the bounded catch (`LinkageError | OutOfMemoryError | StackOverflowError | InternalError` escape) and the `moveToErrorStateIfNeeded`-via-`setInError` line 1769–1771 guard for `AssertionError` — risk: high (crash-safety: durability ordering at the WAL commit boundary; load-bearing for the corrected-trace `setInError` contract on persist failure)  [ ]
3. Add Hook B (apply + histogram-apply parallel) inside `AtomicOperationsManager.endAtomicOperation` after `commitChanges` and **before the inner-finally `releaseLocks`**, with `RuntimeException | AssertionError` log-and-swallow for each apply call. Add an inline source-comment block above Hook B citing (a) the per-index lock acquired at `lockIndexes` (`AbstractStorage.java:2255`), (b) `releaseLocks` as the lock-release site, (c) Tracks 3 and 4 as dependents, (d) `EndAtomicOperationHookOrderingTest` as the regression guard. Add `EndAtomicOperationHookOrderingTest` under `core/src/test/.../paginated/atomicoperations/`: assert Hook A runs before `commitChanges`; injected `IOException` converts to rollback (skip `commitChanges`); Hook B runs after `commitChanges` and before `releaseLocks` with the per-index lock still held; Hook B failure is swallowed; nested-op lock release assertion (A4 — outer op's `lockedComponents()` set is unchanged after Hook B returns) — risk: high (concurrency: lock-scope invariant load-bearing; Hook B placement governs whether the per-index lock is held during apply)  [ ]
4. Delete the manual calls in `AbstractStorage.commit` at lines 2340 (`persistIndexCountDeltas`), 2365 (`applyIndexCountDeltas`), 2381 (`applyHistogramDeltas`) and their post-`endTxCommit` catches at lines 2366 and 2382; the pre-`endTxCommit` catch at line 2341 stays (Track 1's broadened version). Add `MainCommitCounterSyncTest` under `core/src/test/.../storage/impl/local/`: nominal-commit path asserts persisted EP page and in-memory counters land where expected; commitIndexes-IOException path asserts rollback runs, counters stay at pre-tx values, no `AssertionError` escapes. Add `ClearIndexApiRollbackTest` under the same directory, `@Ignore`-annotated with a pointer comment to Track 3's regression-test step. Sweep doc-comments in tests / main that name the manual call sites (12 files per A11) to track the deleted call sites. Optional in this step at decomposer's call: A5(ii) interim reflective IndexCountDelta injection for the `clearIndex` Hook A/B coverage gap; A8 broadening `cleanupSnapshotIndex` catch from `RuntimeException` to `RuntimeException | AssertionError` for symmetry with Track 1's pattern — risk: medium (deletion of error-handling code in `AbstractStorage.commit` plus new test infrastructure; the dangerous wiring already landed in steps 2 and 3 under HIGH-tag review)  [ ]

## Episodes

### Step 1 — commit a10c7d9394, 2026-05-23T13:38Z [ctx=safe]
**What was done:** Raised `persistIndexCountDeltas`, `applyIndexCountDeltas`, and `applyHistogramDeltas` on `AbstractStorage` from `private` to `public`. The change is a pure surface widening: no method body, Javadoc, or invocation site was touched. The new visibility matches the existing manager-callback surface on the same class (`moveToErrorStateIfNeeded`, `getName`, `checkErrorState`, `getSnapshotIndexSize`); `AtomicOperationsManager` sits in a sibling package, so package-private would not have crossed.

**What was discovered:** PSI find-usages on the three methods confirmed the references match the design.md inventory exactly. `applyIndexCountDeltas` has two references (call site at `AbstractStorage.java:2365` and a Javadoc `{@link}` from `persistIndexCountDeltas` at `:2482`); the other two methods have one reference each (their respective call sites). No external caller exists yet — the expected state before Steps 2 and 3 wire the manager-side hooks. The Javadoc `{@link}` resolution is unaffected by the visibility raise.

**What changed from the plan:** none.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (modified)

## Validation and Acceptance

After Track 2 lands:

- `endAtomicOperation` invokes Hook A (persist) before `commitChanges` and Hook B (apply + histogram-apply) after `commitChanges` but before the inner-finally `releaseLocks`. Each hook runs at most once per atomic op; no idempotency flags are needed.
- A persist failure in Hook A (any of `IOException | RuntimeException | AssertionError`) is captured into `error`, routed through `storage.moveToErrorStateIfNeeded(persistFailure)` to preserve today's error-mode contract, then converted to a rollback signal via `operation.rollbackInProgress()` so the subsequent `commitChanges` is skipped. After the inner-finally `releaseLocks`, the captured `error` is re-raised with the same exception-type contract today's line 2341 catch maintains: `RuntimeException` short-circuits as-is; `IOException` and `AssertionError` wrap as `StorageException`. The outer `catch (Error)` cascade cannot be reached via this path.
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
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (Hook A persist + conversion catch + explicit `moveToErrorStateIfNeeded(persistFailure)` before `commitChanges`; Hook B apply + histogram-apply parallel after `commitChanges` and before the inner-finally `releaseLocks`; typed re-raise block added after `releaseLocks` — `RuntimeException` short-circuits as-is, `IOException` and `AssertionError` wrap as `StorageException`)
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
