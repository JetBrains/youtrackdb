# Track 5: Migrate DurableComponent Read Operations

## Progress
- [x] Review + decomposition
- [ ] Step implementation (4/5 complete)
- [ ] Track-level code review

## Base commit
`23cb2bec4b`

## Reviews completed
- [x] Technical (iteration 1 — PASS, 5 findings: 1 blocker, 2 should-fix, 2 suggestion)
- [x] Risk (iteration 1 — PASS, 8 findings: 0 blocker after triage, 3 should-fix, 3 suggestion, 2 rejected)

## Steps

- [x] Step 1: Add hasChangesForPage guard to loadPageOptimistic (subsumes Track 6)
  > **What was done:** Added `hasChangesForPage(long fileId, long pageIndex)` to
  > `AtomicOperation` interface and implemented in `AtomicOperationBinaryTracking`.
  > The check handles four cases: deleted files (return true), truncated files
  > (return true), new files (check `maxNewPageIndex`), and existing files (check
  > `pageChangesMap`). Guard placed at the start of `loadPageOptimistic()` before
  > the cache lookup, so no frame is fetched when we know the page has local changes.
  >
  > **What was discovered:** Code review identified that truncated and deleted files
  > needed explicit handling — without them, optimistic reads could serve stale
  > pre-truncation data or access logically-deleted files.
  >
  > **Key files:** `AtomicOperation.java` (modified), `AtomicOperationBinaryTracking.java`
  > (modified), `DurableComponent.java` (modified),
  > `DurableComponentOptimisticReadTest.java` (modified — 3 new tests),
  > `AtomicOperationSnapshotProxyTest.java` (modified — 7 new tests)

- [x] Step 2: Add PageView constructors to DurablePage subclasses used in read paths
  > **What was done:** Added PageView constructors to 9 DurablePage subclasses:
  > CellBTreeSingleValueBucketV3, CellBTreeSingleValueV3NullBucket,
  > CellBTreeSingleValueEntryPointV3, CollectionPage, MapEntryPoint,
  > CollectionPositionMapBucket, FreeSpaceMapPage, Bucket, and EntryPoint
  > (ridbagbtree). Each delegates to `super(pageView)`.
  >
  > **What was discovered:** Code review identified a missed class —
  > `ridbagbtree.EntryPoint` — needed for SharedLinkBagBTree read path migration
  > in Step 5. Added in the review fix commit.
  >
  > **Key files:** 9 DurablePage subclass files (modified),
  > `DurablePageSubclassPageViewTest.java` (new — 7 tests for public subclasses)

- [x] Step 3: Migrate B-tree single-key lookup and tree traversal to optimistic reads
  > **What was done:** Migrated `BTree.get()` (the class is named `BTree`, not
  > `CellBTreeSingleValueV3`) to `executeOptimisticStorageRead()` with an optimistic
  > lambda `getOptimistic()` that traverses using `loadPageOptimistic()` +
  > `CellBTreeSingleValueBucketV3(PageView)` constructors, calling
  > `scope.validateLastOrThrow()` after each internal node descent. Pinned fallback
  > reuses `findBucketSerialized()`. Null-key path similarly migrated. Also migrated
  > `size()` to optimistic single-page read of the entry point.
  >
  > **What changed from the plan:** `firstKey()` and `lastKey()` were NOT migrated
  > because they delegate to `firstItem()`/`lastItem()` which use deep path-tracking
  > traversals — same as planned. The plan referenced `CellBTreeSingleValueV3` but
  > the implementation class is `BTree`.
  >
  > **Key files:** `BTree.java` (modified — new methods: `getOptimistic`,
  > `getPinned`, `getNullKeyOptimistic`, `getNullKeyPinned`)

- [x] Step 4: Migrate collection position map and collection record reads to optimistic reads
  > **What was done:** Added `getWithStatusOptimistic()` and `getLastPageOptimistic()`
  > to `CollectionPositionMapV2` for pinless position map lookups. Added
  > `doReadRecordOptimistic()` to `PaginatedCollectionV2` that reads single-page,
  > visible, non-tombstone records optimistically. Falls back to pinned path for:
  > multi-page records, tombstones (need history lookup), invisible versions, and
  > any speculative garbage from concurrent page reuse.
  >
  > **What was discovered:** Code review identified that speculative data from live
  > PageView buffers can produce arbitrary RuntimeExceptions (AIOOBE, NPE) that
  > would bypass the optimistic fallback. Fixed with a RuntimeException catch wrapper,
  > minimum record size guard, null content check, and explicit FILLED status guard.
  >
  > **Key files:** `CollectionPositionMapV2.java` (modified),
  > `PaginatedCollectionV2.java` (modified)

- [ ] Step 5: Migrate FreeSpaceMap and SharedLinkBagBTree reads to optimistic reads
  Migrate `FreeSpaceMap.findFreePage()` to use `executeOptimisticStorageRead()`:
  the optimistic lambda performs the two-level segment tree lookup via
  `loadPageOptimistic()` with per-level validation.
  Migrate `SharedLinkBagBTree.get()` to use `executeOptimisticStorageRead()`: the
  optimistic lambda traverses the link bag B-tree via `loadPageOptimistic()` with
  per-level `validateLastOrThrow()`, similar to the CellBTreeSingleValueV3 pattern.
  **NOT migrated** (kept on pinned path): SharedLinkBagBTree.firstItem/lastItem
  (deep traversals with path tracking, low frequency).
  Tests: existing FreeSpaceMap and SharedLinkBagBTree tests must pass. Add targeted
  tests for optimistic path behavior.
  Files: `FreeSpaceMap.java` (modified), `SharedLinkBagBTree.java` (modified),
  test files (modified).
