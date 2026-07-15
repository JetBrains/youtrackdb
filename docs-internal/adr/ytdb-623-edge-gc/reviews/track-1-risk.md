# Track 1 — Risk Review

## Findings

### Finding R1 [blocker]
**Location**: `put()` loop / `filterAndRebuildBucket()` — stale `insertionIndex`
**Issue**: Same as T1. After in-place bucket rebuild, `insertionIndex` is wrong. B-tree ordering corrupted.
**Proposed fix**: Re-derive `insertionIndex` via `bucket.find()` after rebuild.
**Decision**: ACCEPTED — duplicate of T1, addressed in decomposition.

### Finding R2 [should-fix] (downgraded from blocker)
**Location**: `isRemovableTombstone()` — `ts < LWM` comparison
**Issue**: Plan correctly specifies `ts < LWM` (strict), but an implementation off-by-one (`<=`) would be unsafe. Needs explicit assertion and comment.
**Proposed fix**: Assert strict `<` comparison, add comment explaining why `<=` is unsafe, add test with `tsMin == tombstone.ts`.
**Decision**: ACCEPTED as should-fix. The plan already specifies the correct comparison; risk is implementation error, mitigated by assertion and test.

### Finding R3 [should-fix]
**Location**: `shrink(0) + addAll()` mid-failure state
**Issue**: If exception during `addAll`, page is partially rebuilt. WAL rollback should handle this (same pattern as `splitRootBucket`).
**Proposed fix**: Add comment referencing existing `splitRootBucket` precedent for in-place rebuild under atomic operation.
**Decision**: ACCEPTED — add comment.

### Finding R4 [should-fix]
**Location**: `treeSize` accounting across GC+split scenarios
**Issue**: Four cases (GC+insert succeeds, GC+insert fails→split, no GC+insert succeeds, no GC) need correct treeSize delta.
**Proposed fix**: Enumerate all 4 cases in implementation comments. `filterAndRebuildBucket` returns count, caller does `updateSize(-count)` once, separate from the insert's `updateSize(sizeDiff)`.
**Decision**: ACCEPTED — incorporated into Step 2 and Step 3.

### Finding R5 [should-fix]
**Location**: `componentId` / `fileId` initialization safety
**Issue**: `fileId` could theoretically be 0 if tree not initialized.
**Proposed fix**: Assert `fileId != 0` in `filterAndRebuildBucket()`.
**Decision**: ACCEPTED.

### Finding R6 [should-fix]
**Location**: `computeGlobalLowWaterMark()` under exclusive lock
**Issue**: O(active thread count) under write lock. Acceptable, needs documentation.
**Proposed fix**: Add comment in implementation.
**Decision**: ACCEPTED.

### Finding R7 [should-fix]
**Location**: All-tombstone-bucket edge case
**Issue**: If all entries removed, insert must succeed (empty page always has room). Assertion makes this invariant explicit.
**Proposed fix**: Assert that if all entries were removed, the retry insert succeeds.
**Decision**: ACCEPTED — add assertion in GC integration code.

### Finding R8 [suggestion]
**Location**: Track 2 test spec — `TsMinHolder` manipulation claim
**Issue**: Existing SI tests don't manipulate `TsMinHolder` directly. LWM control is achieved by ensuring no concurrent transactions are open.
**Proposed fix**: Note for Track 2 decomposition — clarify LWM test strategy.
**Decision**: NOTED for Track 2.

### Finding R9 [suggestion]
**Location**: Snapshot entry visibility reasoning
**Issue**: Document why local+shared snapshot check is sufficient.
**Proposed fix**: Add comment in `hasEdgeSnapshotEntries()`.
**Decision**: ACCEPTED — duplicate of T8.

## Summary
One blocker (R1, duplicate of T1) addressed. R2 downgraded to should-fix. All should-fix items incorporated into implementation guidance.
