# Track 7 Technical Review

## Summary

Track 7 proposes replacing `StampedLock` with `ReentrantReadWriteLock` in `SharedResourceAbstract`, making set-once fields volatile, removing `executeReadOperation`/`readUnderLock` wrappers, and having reads go through `executeOptimisticStorageRead` directly. The overall approach is sound and the component map is mostly accurate.

## Findings

### Finding T1 [should-fix]
**Location**: Phase 5 Migration section, `DurableComponent.executeOptimisticStorageRead()` (DurableComponent.java lines 319-336)
**Issue**: Currently, the outer `AtomicOperationsManager.executeReadOperation()` acts as a safety net that catches arbitrary RuntimeExceptions from speculative data on the optimistic path. After Track 7 removes the outer wrapper, `executeOptimisticStorageRead` only catches `OptimisticReadFailedException`, so arbitrary RuntimeExceptions from speculative data would propagate as real errors.
**Proposed fix**: Widen the catch clause in `DurableComponent.executeOptimisticStorageRead()` to also catch `RuntimeException` (and optionally `AssertionError`) on the optimistic path and fall back to the pinned path.

### Finding T2 [should-fix]
**Location**: BTree.java and SharedLinkBagBTree.java Spliterator fetch methods, PaginatedCollectionV2.java non-migrated read methods
**Issue**: Many read methods have NOT been migrated to `executeOptimisticStorageRead` in Track 5 (e.g., BTree `firstKey()`, `lastKey()`, `fetchNextForwardCachePortion()`, `fetchNextBackwardCachePortion()`; SharedLinkBagBTree spliterator fetch methods; many PaginatedCollectionV2 methods). The plan does not specify what happens to these non-migrated methods when `executeReadOperation`/`readUnderLock` are removed.
**Proposed fix**: For non-migrated methods, wrap in `executeOptimisticStorageRead` with the same pinned-path lambda for both optimistic and fallback parameters (effectively always pinned), or call `acquireSharedLock()`/`releaseSharedLock()` directly.

### Finding T3 [should-fix]
**Location**: Phase 5 volatile field table, BTree.java line 101
**Issue**: The plan's volatile field table for BTree lists `fileId`, `nullBucketFileId`, `keySerializer`, `keyTypes`, `keySize`, but misses `maxKeySize`. Set during `create()`/`load()` under exclusive lock, read during `put()`.
**Proposed fix**: Add `maxKeySize` to the volatile field list for BTree.

### Finding T4 [suggestion]
**Location**: Phase 5 Migration, plan line 1652
**Issue**: Plan references `executeStorageRead()` and `readStorageUnderLock()` but actual method names are `executeReadOperation()` and `readUnderLock()`.
**Proposed fix**: Use correct method names during implementation.

### Finding T5 [suggestion]
**Location**: Phase 5, plan lines 1637-1641 (PaginatedCollectionV2 line references)
**Issue**: Specific line numbers for `acquireSharedLock()` call sites are stale.
**Proposed fix**: Use method names rather than line numbers.

### Finding T6 [suggestion]
**Location**: Phase 5 Files to modify
**Issue**: FreeSpaceMap and CollectionPositionMapV2 don't use `executeReadOperation` — they only need `fileId` made volatile.
**Proposed fix**: Clarify in step decomposition.

### Finding T7 [suggestion]
**Location**: `ExecuteReadOperationTest.java`
**Issue**: Dedicated test class for `executeReadOperation()`/`readUnderLock()` will break when those methods are removed.
**Proposed fix**: Delete or rewrite this test class as part of the migration.

### Finding T8 [suggestion]
**Location**: Phase 5 RRWL migration, SharedResourceAbstract.java
**Issue**: Write operations hitting fallback path will now perform actual `readLock().lock()` + `readLock().unlock()` CAS pair, whereas currently these are no-ops when exclusive owner.
**Proposed fix**: Consider keeping short-circuit in `acquireSharedLock()`/`releaseSharedLock()` for write-lock holders using `isWriteLockedByCurrentThread()`.

## Decisions

- **T1**: ACCEPT — widen catch to RuntimeException in executeOptimisticStorageRead
- **T2**: ACCEPT — enumerate and handle all non-migrated methods explicitly
- **T3**: ACCEPT — add maxKeySize to volatile fields
- **T4**: NOTED — will use correct names during implementation
- **T5**: NOTED — will use method names instead of line numbers
- **T6**: NOTED — will clarify in step descriptions
- **T7**: ACCEPT — include ExecuteReadOperationTest in scope
- **T8**: ACCEPT — keep short-circuit for write-lock holders
