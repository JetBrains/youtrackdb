# Track 1 — Technical Review

## Findings

### Finding T1 [blocker]
**Location**: Plan "D2: Filter-rebuild-retry" / `put()` lines 420–464 in `SharedLinkBagBTree.java`
**Issue**: After `filterAndRebuildBucket()` rebuilds the bucket via `shrink(0) + addAll(survivors)`, the `insertionIndex` variable is stale. Tombstone removal shifts surviving entries to lower indices, so the pre-computed `insertionIndex` points to the wrong position, corrupting B-tree sort order.
**Proposed fix**: After rebuild, re-derive `insertionIndex` by calling `keyBucket.find(key, serializerFactory)` and converting to insertion index.
**Decision**: ACCEPTED — incorporated into step decomposition.

### Finding T2 [blocker]
**Location**: `remove()` lines 1148–1169 in `SharedLinkBagBTree.java`
**Issue**: The `remove()` method's cross-tx tombstone insertion has an identical `while (!addLeafEntry) { splitBucket }` loop not mentioned in the plan. Tombstone pages that receive cross-tx deletions still split unnecessarily without GC.
**Proposed fix**: Apply the same `filterAndRebuildBucket()` logic to `remove()`'s tombstone insertion loop.
**Decision**: ACCEPTED — added as Step 3 in decomposition.

### Finding T3 [should-fix]
**Location**: `Bucket.addAll()` / `filterAndRebuildBucket()`
**Issue**: `addAll()` doesn't check overflow. After `shrink(0)` survivors always fit, but the precondition isn't enforced.
**Proposed fix**: Assert `keyBucket.size() == 0` after `shrink(0)` before `addAll(survivors)`.
**Decision**: ACCEPTED — add assertion in `filterAndRebuildBucket()`.

### Finding T4 [should-fix]
**Location**: Component map narrative in plan file
**Issue**: Component map says filtering happens "inside the existing split method" but checklist correctly says it happens before `splitBucket()` is called. Misleading.
**Proposed fix**: Noted for implementation — `splitBucket()` is not modified.
**Decision**: ACCEPTED — informational; implementation follows the checklist, not the narrative.

### Finding T5 [should-fix]
**Location**: `hasEdgeSnapshotEntries()` helper
**Issue**: Only needs presence check, not full iteration. Must use `iterator().hasNext()` on the lazy iterable.
**Proposed fix**: Implement as `edgeSnapshotSubMapDescending(...).iterator().hasNext()`.
**Decision**: ACCEPTED — incorporated into Step 1.

### Finding T6 [should-fix]
**Location**: `updateSize()` call pattern
**Issue**: Calling `updateSize(-1)` per tombstone causes N WAL records. Should accumulate count and call once.
**Proposed fix**: `filterAndRebuildBucket()` returns removed count; caller calls `updateSize(-removedCount)` once.
**Decision**: ACCEPTED — plan already describes this; made explicit in decomposition.

### Finding T7 [suggestion]
**Location**: `computeGlobalLowWaterMark()` call site
**Issue**: LWM should be computed in caller and passed as parameter to enforce "once per GC attempt" contract.
**Proposed fix**: `filterAndRebuildBucket(long lwm, ...)` signature.
**Decision**: ACCEPTED.

### Finding T8 [suggestion]
**Location**: `hasEdgeSnapshotEntries()` safety reasoning
**Issue**: Document why checking local+shared snapshot buffer is safe for cross-tx tombstones.
**Proposed fix**: Add explanatory comment.
**Decision**: ACCEPTED.

## Summary
Two blockers (T1, T2) addressed in decomposition. All should-fix and suggestions incorporated.
