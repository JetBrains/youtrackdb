# Fix 05: Initialize approximate count from TREE_SIZE on upgrade

## Problem

The new `APPROXIMATE_ENTRIES_COUNT` field on the BTree entry point page (offset 49)
was not present in the previous format. On existing databases, the bytes at that
offset read as 0 (zero-filled pages). This causes `approximateIndexEntriesCount`
to be 0 in `load()`, making `size()` report 0 entries until `buildInitialHistogram()`
recalibrates.

During this window, the query optimizer sees all indexes as empty, potentially
choosing full scans over index scans.

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java`
  - `load()` lines 144-162
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - `load()` lines 180-203
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTree.java`
  - `getApproximateEntriesCount()` and `getTreeSize()` accessors

## Fix Strategy

In `load()`, after reading the persisted approximate count, if it is 0 but
`TREE_SIZE` (the existing exact B-tree entry count) is > 0, use TREE_SIZE as
the initial estimate.

`TREE_SIZE` counts ALL B-tree entries including tombstones and snapshot markers,
so it will overcount. But it's a much better starting point than 0 for query
optimization. The next `buildInitialHistogram()` will recalibrate to the exact
visible count.

### For BTreeSingleValueIndexEngine.load():

```java
long count = sbTree.getApproximateEntriesCount(atomicOperation);
if (count == 0) {
  // Upgrade path: APPROXIMATE_ENTRIES_COUNT was not present in prior format.
  // Use TREE_SIZE as initial estimate — overcounts (includes tombstones/markers)
  // but prevents the optimizer from seeing empty indexes until recalibration.
  count = sbTree.getTreeSize(atomicOperation);
}
approximateIndexEntriesCount.set(count);
```

### For BTreeMultiValueIndexEngine.load():

Same pattern for both `svTree` and `nullTree`.

### BTree API:

Verify `BTree.getTreeSize(AtomicOperation)` is accessible (it reads
`CellBTreeSingleValueEntryPointV3.getTreeSize()`). If not exposed via
`CellBTreeSingleValue` interface, add it.

## Implementation Steps

1. Add `getTreeSize(AtomicOperation)` to `CellBTreeSingleValue` interface if not
   already present.
2. Update `BTreeSingleValueIndexEngine.load()` to fall back to tree size.
3. Update `BTreeMultiValueIndexEngine.load()` to fall back to tree size for both trees.
4. Add a test verifying upgrade behavior: create index, close without writing
   APPROXIMATE_ENTRIES_COUNT, reopen, verify size() returns non-zero.
5. Run `./mvnw -pl core spotless:apply`
6. Run `./mvnw -pl core clean test`
7. Commit: `YTDB-523: Initialize approximate count from TREE_SIZE on upgrade`

## Severity

Suggestion. Affects upgrade experience — transient query plan degradation.
