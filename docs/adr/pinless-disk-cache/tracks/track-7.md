# Track 7: Remove component-level read lock from happy path

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/5 complete)
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

- [ ] Step 3: Unwrap PaginatedCollectionV2 read operations from executeReadOperation
  > Remove `atomicOperationsManager.executeReadOperation`/`readUnderLock` wrappers from
  > all 14 call sites in `PaginatedCollectionV2.java`. For migrated methods
  > (`readRecord`): remove the outer wrapper. For non-migrated methods
  > (`getPhysicalPosition`, `exists`, `getEntries`, `getFirstPosition`,
  > `getLastPosition`, `higherPositions`, `ceilingPositions`, `lowerPositions`,
  > `floorPositions`, `nextPage`, etc.): wrap in `executeOptimisticStorageRead` with
  > pinned-only lambdas. Remove direct `acquireSharedLock()` calls in
  > `generateCollectionConfig()`, `encryption()`, `getRecordStatus()` — route through
  > `executeOptimisticStorageRead()` or make field reads volatile-safe. Make set-once
  > fields volatile: `fileId`, `recordConflictStrategy`.
  >
  > **Files:** `PaginatedCollectionV2.java` (modified)

- [ ] Step 4: Unwrap SharedLinkBagBTree read operations from executeReadOperation
  > Remove `atomicOperationsManager.executeReadOperation`/`readUnderLock` wrappers from
  > all 9 call sites in `SharedLinkBagBTree.java`. For migrated methods (`get`): remove
  > outer wrapper. For non-migrated methods (`firstKey`, `lastKey`, spliterator methods,
  > cursor fetch methods, `streamEntriesBetween`, `iterateEntriesMajor`,
  > `iterateEntriesMinor`): wrap in `executeOptimisticStorageRead` with pinned-only
  > lambdas. Make `fileId` volatile if not already. Also make `fileId` volatile in
  > `FreeSpaceMap.java` and `CollectionPositionMapV2.java` (no wrapper removal needed
  > for these — they don't use `executeReadOperation`).
  >
  > **Files:** `SharedLinkBagBTree.java` (modified), `FreeSpaceMap.java` (modified),
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
