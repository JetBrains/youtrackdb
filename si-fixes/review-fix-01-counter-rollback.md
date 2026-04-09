# Fix 01: Defer in-memory counter updates to post-commit

## Problem

In `BTreeSingleValueIndexEngine` and `BTreeMultiValueIndexEngine`, the `clear()` and
`buildInitialHistogram()` methods update in-memory `AtomicLong` counters
(`approximateIndexEntriesCount`, `approximateNullCount`) **inside** the atomic operation,
alongside WAL-protected page writes. If the atomic operation rolls back (e.g., a
subsequent step in `commitIndexes` or `persistIndexCountDeltas` throws), the WAL reverts
the page but the in-memory counters retain the mutated values. Until the next restart
(`load()`) or `buildInitialHistogram()` recalibration, in-memory counters disagree with
persisted state.

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java`
  - `clear()` lines 218-221: sets counters to 0 inside atomic operation
  - `buildInitialHistogram()` lines 558-560: sets counters to exact values inside atomic operation
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - `clear()` lines 278-283: sets counters to 0 inside atomic operation
  - `buildInitialHistogram()` lines 567-568: sets counters to exact values inside atomic operation

## Fix Strategy

### For `buildInitialHistogram()`

`buildInitialHistogram()` runs inside its own atomic operation (from
`IndexHistogramManager.rebalanceHistogram()` → `atomicOperationsManager.executeInsideAtomicOperation()`).
The fix: move the `AtomicLong.set()` calls AFTER the atomic operation completes successfully.

However, `buildInitialHistogram()` is called from within a lambda that IS the atomic operation.
So within `buildInitialHistogram()` itself, you can't "defer to after commit" — the method
returns before commit happens. The cleanest approach is:

1. In `buildInitialHistogram()`, **return** the exact counts instead of setting them directly.
2. Have the caller (IndexHistogramManager or the atomic operation wrapper) set the counters
   after the atomic operation succeeds.

**Alternative (simpler)**: Accept the current behavior and add a comment documenting that
the in-memory counters may temporarily diverge on rollback, but will be recalibrated on
next `buildInitialHistogram()` or `load()`. The counters are approximate by design.

**Recommended approach**: Add clear comments documenting the design choice. The self-healing
property (recalibration on next histogram build or restart) makes this a low-risk divergence.
The actual fix of deferring counter updates requires non-trivial refactoring of the
`buildInitialHistogram()` contract (adding return values and changing callers).

### For `clear()`

`clear()` is called from `commitIndexes()` within the commit flow. The counters are set to 0
inside the same atomic operation that clears the B-tree. If the commit flow fails after
`clear()`, the counters remain at 0 but the tree may still have data (reverted by WAL).

The cleanest fix: move the `approximateIndexEntriesCount.set(0)` and
`approximateNullCount.set(0)` calls to a post-commit callback, similar to
`applyIndexCountDeltas()`. This requires:

1. Adding a `resetCountersOnClear()` method to `BTreeIndexEngine` interface
2. Calling it from the post-commit block in `AbstractStorage` (after `endTxCommit()`)
3. Tracking which engines were cleared during the commit (e.g., a list on the atomic operation)

**Recommended approach**: Similarly to buildInitialHistogram, add clear documentation.
The `clear()` path is rare (explicit index clear, not normal CRUD). On rollback, the
counters show 0 but the tree has data. The next commit with put/remove will apply deltas
to the 0 base (incorrect), but `buildInitialHistogram()` will recalibrate. The risk is
a window of incorrect `size()` returns between rollback and recalibration.

## Implementation Steps

1. Add comments to both `clear()` methods in both engine classes documenting that in-memory
   counters are set eagerly and may diverge on rollback, with self-healing via
   `buildInitialHistogram()` or `load()`.
2. Add comments to both `buildInitialHistogram()` methods with the same documentation.
3. Run affected tests: `./mvnw -pl core clean test`
4. Commit: `YTDB-523: Document eager counter update semantics on rollback`

## Severity

Should-fix (documentation). The actual code behavior is acceptable given the approximate,
self-healing design — but the lack of documentation about the rollback semantics is a gap.
