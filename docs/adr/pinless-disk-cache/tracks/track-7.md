# Track 7: Remove component-level read lock from happy path

## Progress
- [x] Review + decomposition
- [ ] Step implementation (4/5 complete)
- [ ] Track-level code review

## Base commit
`0e7138f2e0`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Replace StampedLock with ReentrantReadWriteLock in SharedResourceAbstract + widen optimistic catch clause
  > **What was done:** Replaced StampedLock with ReentrantReadWriteLock in
  > SharedResourceAbstract, eliminating manual exclusiveOwner/exclusiveHoldCount tracking.
  > Widened catch clause in DurableComponent.executeOptimisticStorageRead() from
  > OptimisticReadFailedException to RuntimeException. Simplified
  > AtomicOperationsManager.executeReadOperation/readUnderLock to use component's shared
  > lock directly (no more component-level optimistic protocol). Added lockShared()/
  > unlockShared() public delegates on DurableComponent for AtomicOperationsManager access.
  > Updated ExecuteReadOperationTest to use public API instead of removed stampedLock field.
  >
  > **Key files:** `SharedResourceAbstract.java` (modified), `DurableComponent.java`
  > (modified), `AtomicOperationsManager.java` (modified),
  > `ExecuteReadOperationTest.java` (modified)

- [x] Step 2: Unwrap BTree read operations from executeReadOperation
  > **What was done:** Removed all 12 executeReadOperation/readUnderLock wrappers from
  > BTree.java. `get()` and `size()` now call `executeOptimisticStorageRead` directly.
  > Non-migrated methods (firstKey, lastKey, keyStream, allEntries, iterateEntriesBetween,
  > iterateEntriesMinor, iterateEntriesMajor, assertFreePages) wrapped in
  > `executeOptimisticStorageRead` with pinned-only lambdas. Cursor fetch methods use
  > `acquireSharedLock()` directly instead of `executeOptimisticStorageRead` because they
  > mutate spliterator state. Made 6 set-once fields volatile.
  >
  > **What was discovered:** Code review caught that cursor fetch methods mutate
  > spliterator state (pageIndex, itemIndex, dataCache), so using identical lambdas for
  > both optimistic and pinned parameters in executeOptimisticStorageRead could corrupt
  > iterator state on retry. Fixed by using acquireSharedLock() directly for these methods.
  >
  > **Key files:** `BTree.java` (modified)

- [x] Step 3: Unwrap PaginatedCollectionV2 read operations from executeReadOperation
  > **What was done:** Removed all 14 `executeReadOperation`/`readUnderLock` wrappers from
  > PaginatedCollectionV2. `readRecord()` (already migrated) had its outer wrapper removed.
  > Non-migrated methods routed through `executeOptimisticStorageRead` with pinned-only
  > lambdas via extracted helpers: `doGetPhysicalPosition`, `doExists`, `doGetEntries`,
  > `doGetRecordStatus`, `doNextPage`. `generateCollectionConfig()` and `encryption()`
  > had shared locks removed — fields are now volatile. `exists(AtomicOperation)` simplified
  > to direct `isFileExists()` call (no page I/O). `synch()` uses explicit
  > `acquireSharedLock()` (no AtomicOperation available). `getFileName()` reads volatile
  > `fileId` directly. Made `fileId` and `recordConflictStrategy` volatile.
  >
  > **Key files:** `PaginatedCollectionV2.java` (modified)

- [x] Step 4: Unwrap SharedLinkBagBTree read operations from executeReadOperation
  > **What was done:** Removed all 9 `executeReadOperation`/`readUnderLock` wrappers from
  > SharedLinkBagBTree. `get()` (already migrated) had its outer wrapper removed.
  > `firstKey`/`lastKey` routed through `executeOptimisticStorageRead` with pinned-only
  > lambdas via `doFirstKey`/`doLastKey` helpers. Stream/spliterator creation methods
  > (`iterateEntriesMinor`, `iterateEntriesMajor`, `streamEntriesBetween`,
  > `spliteratorEntriesBetween`) wrapped in `executeOptimisticStorageRead` with pinned-only
  > lambdas. Cursor fetch methods (`fetchNextCachePortionForward`/`Backward`) use
  > `acquireSharedLock()` directly — same pattern as BTree Step 2 since they mutate
  > iterator state. Made `fileId` volatile in `FreeSpaceMap` and `CollectionPositionMapV2`.
  > `SharedLinkBagBTree.fileId` was already volatile.
  >
  > **Key files:** `SharedLinkBagBTree.java` (modified), `FreeSpaceMap.java` (modified),
  > `CollectionPositionMapV2.java` (modified)

- [ ] Step 5: Remove executeReadOperation/readUnderLock from AtomicOperationsManager + clean up tests
  > Delete `executeReadOperation()` and `readUnderLock()` from
  > `AtomicOperationsManager.java`. Remove `component.stampedLock` field references.
  > Update stale Javadoc/comments referencing `StampedLock` in
  > `acquireExclusiveLockTillOperationComplete()` and `releaseLocks()` (R8).
  > Delete or rewrite `ExecuteReadOperationTest.java` (1317 lines) — tests for removed
  > methods are no longer valid (T7/R3). Verify compilation succeeds (any missed
  > unwrap becomes a compile error).
  >
  > **Files:** `AtomicOperationsManager.java` (modified), `ExecuteReadOperationTest.java`
  > (deleted or rewritten)
