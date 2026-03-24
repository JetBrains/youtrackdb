# Track 1: Tombstone filtering during leaf page split

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Base commit
`6838a708f9`

## Reviews completed
- [x] Technical (track-1-technical.md — 2 blockers resolved, 4 should-fix, 2 suggestions)
- [x] Risk (track-1-risk.md — 1 blocker duplicate of T1, 5 should-fix, 2 suggestions)

## Steps

- [ ] Step 1: Add tombstone GC helper methods to SharedLinkBagBTree
  > Add three private helper methods to `SharedLinkBagBTree`:
  >
  > 1. **`hasEdgeSnapshotEntries(int componentId, long ridBagId, int targetCollection,
  >    long targetPosition, AtomicOperation atomicOp)`** — returns `boolean`.
  >    Constructs `EdgeSnapshotKey` range (lowerKey with `Long.MIN_VALUE`, upperKey
  >    with `Long.MAX_VALUE`) and calls
  >    `atomicOp.edgeSnapshotSubMapDescending(lower, upper).iterator().hasNext()`.
  >    Uses lazy `hasNext()` per T5. Add comment explaining why local+shared snapshot
  >    check is safe (T8/R9).
  >
  > 2. **`isRemovableTombstone(EdgeKey key, LinkBagValue value, long lwm,
  >    int componentId, AtomicOperation atomicOp)`** — returns `boolean`.
  >    Checks: (a) `value.isTombstone()`, (b) `key.ts < lwm` (strict `<`, not `<=`
  >    per R2 — add comment explaining why), (c) `!hasEdgeSnapshotEntries(...)`.
  >    All three conditions must be true.
  >
  > 3. **`filterAndRebuildBucket(Bucket keyBucket, long lwm, int componentId,
  >    AtomicOperation atomicOp)`** — returns `int` (count of removed tombstones).
  >    Iterates all entries in the bucket (index 0 to `size-1`), deserializes
  >    key+value, calls `isRemovableTombstone()`. Collects survivors as raw byte
  >    arrays (via `getRawEntry()`). If no tombstones found, returns 0 (no rebuild).
  >    Otherwise: `keyBucket.shrink(0, serializerFactory)`, assert `size() == 0` (T3),
  >    `keyBucket.addAll(survivors)`, return removed count.
  >    Assert `fileId != 0` at entry (R5). Add comment referencing `splitRootBucket`
  >    precedent for in-place rebuild under atomic operation (R3).
  >
  > **Tests**: Unit tests for `isRemovableTombstone` (tombstone below/above LWM,
  > with/without snapshot entries, non-tombstone entry). Test for
  > `hasEdgeSnapshotEntries` (empty index, populated index). Test for
  > `filterAndRebuildBucket` on a bucket with mixed entries (requires test
  > infrastructure from existing SI tests).
  >
  > **Key files**: `SharedLinkBagBTree.java`, new test class or additions to existing
  > test class.

- [ ] Step 2: Integrate GC into `put()` with filter-rebuild-retry
  > Modify the `put()` method's `while (!addLeafEntry)` loop (lines 442–464) to
  > attempt tombstone GC before splitting:
  >
  > 1. Add a `boolean gcAttempted = false` flag before the while loop.
  > 2. Inside the loop, before `splitBucket()`: if `!gcAttempted`, compute LWM via
  >    `storage.computeGlobalLowWaterMark()`, compute `componentId` via
  >    `AbstractWriteCache.extractFileId(getFileId())`, call
  >    `filterAndRebuildBucket(keyBucket, lwm, componentId, atomicOperation)`.
  >    Set `gcAttempted = true`.
  > 3. If `removedCount > 0`: call `updateSize(-removedCount, atomicOperation)` once
  >    (T6/R4). Re-derive `insertionIndex` via `keyBucket.find(key, serializerFactory)`
  >    (T1/R1 fix). `continue` to retry the loop (re-evaluates `addLeafEntry`).
  > 4. If `removedCount == 0`: fall through to existing `splitBucket()`.
  > 5. Add comment documenting the 4 treeSize accounting cases (R4).
  > 6. Add comment that LWM is computed once per GC attempt under exclusive lock (R6).
  >
  > **Tests**: Basic test forcing a split on a bucket containing removable tombstones,
  > verifying: (a) tombstones removed, (b) insert succeeds without split when GC frees
  > enough space, (c) tree size correct, (d) B-tree ordering preserved.
  >
  > **Key files**: `SharedLinkBagBTree.java`, test file.

- [ ] Step 3: Integrate GC into `remove()` cross-tx tombstone insertion path
  > Apply the same filter-rebuild-retry pattern to the `remove()` method's cross-tx
  > tombstone insertion `while (!addLeafEntry)` loop (lines 1148–1169):
  >
  > 1. Add `boolean gcAttempted = false` flag before the loop.
  > 2. Inside the loop, before `splitBucket()`: same GC logic as `put()` — compute
  >    LWM, componentId, call `filterAndRebuildBucket()`, set `gcAttempted = true`.
  > 3. If `removedCount > 0`: `updateSize(-removedCount, atomicOperation)`,
  >    re-derive `insertionIndex` via `keyBucket.find()`, `continue`.
  > 4. If `removedCount == 0`: fall through to `splitBucket()`.
  >
  > **Tests**: Test that cross-tx tombstone insertion into a full bucket with
  > removable tombstones avoids split. Verify tree size and B-tree ordering.
  >
  > **Key files**: `SharedLinkBagBTree.java`, test file.
