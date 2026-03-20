# Track 5: Migrate DurableComponent Read Operations

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/5 complete)
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

- [ ] Step 2: Add PageView constructors to DurablePage subclasses used in read paths
  Add `public <ClassName>(PageView pageView) { super(pageView); }` constructors to
  all 8 DurablePage subclasses used in read operations:
  1. `CellBTreeSingleValueBucketV3<K>` — B-tree node pages
  2. `CellBTreeSingleValueV3NullBucket` — B-tree null bucket
  3. `CellBTreeSingleValueEntryPointV3<K>` — B-tree entry point (size/metadata)
  4. `CollectionPage` — record storage pages
  5. `MapEntryPoint` (in CollectionPositionMapV2) — position map entry point
  6. `CollectionPositionMapBucket` — position map bucket pages
  7. `FreeSpaceMapPage` — free space map pages
  8. `Bucket` (in SharedLinkBagBTree) — link bag B-tree pages
  These are mechanical additions — each constructor delegates to `super(pageView)`.
  The base `DurablePage(PageView)` constructor already sets `speculativeRead = true`
  and `changes = null`, so all existing `getIntValue()`/`getLongValue()` etc. fast
  paths work unchanged.
  Tests: basic instantiation tests verifying each subclass can be constructed from
  a PageView without error.
  Files: 8 DurablePage subclass files (modified), test class (new or modified).

- [ ] Step 3: Migrate B-tree single-key lookup and tree traversal to optimistic reads
  Migrate `CellBTreeSingleValueV3.get()` to use `executeOptimisticStorageRead()`:
  the optimistic lambda traverses the tree via `loadPageOptimistic()` with per-level
  `validateLastOrThrow()`, and the pinned lambda calls the existing CAS-pinned get.
  Refactor `findBucketSerialized()` (the core tree traversal used by get) into an
  optimistic variant that uses PageView-based bucket constructors and validates after
  each level descent. Also migrate `firstKey()`, `lastKey()` (single-path traversals),
  and `size()` (single entry-point page load, cached field).
  **NOT migrated** (kept on pinned path): Stream/Spliterator-based iteration methods
  (allEntries, keyStream, iterateEntriesMajor/Minor/Between) — these maintain
  cross-call state and are complex to migrate. B-tree single-key lookup (get) is the
  highest-impact optimization.
  Tests: existing BTree tests must pass unchanged. Add targeted tests for optimistic
  path verification (e.g., test that get succeeds via optimistic path, test fallback
  when stamp is invalidated).
  Files: `CellBTreeSingleValueV3.java` (modified), test files (modified).

- [ ] Step 4: Migrate collection position map and collection record reads to optimistic reads
  Migrate `CollectionPositionMapV2.get()` and `getWithStatus()` to optimistic variants
  that use `loadPageOptimistic()` instead of `loadPageForRead()`. These are plain
  methods (no executeOptimisticStorageRead wrapper) — they add stamps to the outer
  scope owned by PaginatedCollectionV2.
  Migrate `PaginatedCollectionV2.readRecord()` to use `executeOptimisticStorageRead()`:
  the optimistic lambda calls the optimistic CollectionPositionMapV2 methods, then
  loads the collection data page optimistically. For single-page records, read data
  and return. For multi-page records (nextPagePointer >= 0), throw
  `OptimisticReadFailedException` to fall back to the pinned path — multi-page
  chaining has high retry probability and is rare.
  Tests: existing PaginatedCollectionV2 and CollectionPositionMapV2 tests must pass.
  Add targeted tests for single-page optimistic reads and multi-page fallback.
  Files: `CollectionPositionMapV2.java` (modified), `PaginatedCollectionV2.java` (modified),
  test files (modified).

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
