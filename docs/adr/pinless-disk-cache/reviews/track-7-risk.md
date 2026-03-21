# Track 7 Risk Review

## Summary

Track 7's approach is well-analyzed. Main risks center around RRWL migration consistency, handling non-migrated read methods, and the scale of mechanical unwrapping (35 call sites).

## Findings

### Finding R7 [blocker]
**Location**: `SharedResourceAbstract.acquireSharedLock()`/`releaseSharedLock()` reentrancy check
**Issue**: RRWL migration requires paired updates to both methods. If one keeps the `exclusiveOwner` check and the other doesn't, you get lock leaks or `IllegalMonitorStateException`. The plan's proposed code handles this correctly but implementation mistake risk is high.
**Proposed fix**: Add explicit warning in step description. Both methods must be updated atomically. Add test that verifies write-lock holder can acquire and release shared lock.

### Finding R3 [should-fix]
**Location**: `ExecuteReadOperationTest.java` (1317 lines)
**Issue**: This test class tests `executeReadOperation()`/`readUnderLock()`. Removing those methods requires deleting or substantially rewriting all tests. Unscoped work.
**Proposed fix**: Plan for test class deletion/rewrite as explicit step work.

### Finding R2 [should-fix]
**Location**: BTree `firstKey()`, `lastKey()`, Spliterator fetch methods
**Issue**: Methods that remain on pinned-only path need explicit guidance. After removing `executeReadOperation`, they still need either `executeOptimisticStorageRead` wrapping or direct `acquireSharedLock()` calls.
**Proposed fix**: Enumerate all pinned-only methods and specify their migration strategy.

### Finding R5 [should-fix]
**Location**: PaginatedCollectionV2 — 14 methods still using `executeReadOperation`
**Issue**: Many collection read methods not yet on optimistic path need explicit migration strategy.
**Proposed fix**: Same as R2 — enumerate and specify strategy.

### Finding R10 [should-fix]
**Location**: 35 call sites across BTree (12), PaginatedCollectionV2 (14), SharedLinkBagBTree (9)
**Issue**: Large mechanical unwrapping scope. Error-prone at scale.
**Proposed fix**: Structure steps as: (1) RRWL + volatile infrastructure, (2) Per-component unwrap with tests between each, (3) Remove `executeReadOperation`/`readUnderLock` last.

### Finding R8 [should-fix]
**Location**: `AtomicOperationsManager.releaseLocks()`, `acquireExclusiveLockTillOperationComplete()`
**Issue**: Javadoc/comments reference `StampedLock` — will be stale after migration.
**Proposed fix**: Update comments as part of implementation.

### Finding R1 [suggestion]
**Location**: FreeSpaceMap, CollectionPositionMapV2
**Issue**: These components don't use `executeReadOperation` — only need volatile `fileId`.
**Proposed fix**: Clarify in scope.

### Finding R4 [suggestion]
**Location**: Track 7 scope estimate (~4-6 steps)
**Issue**: Given 35 unwrap sites + RRWL migration + test updates, scope is likely at the upper end.
**Proposed fix**: Plan for 5-6 steps.

### Finding R6 [suggestion]
**Location**: RRWL vs StampedLock memory overhead
**Issue**: ~64 bytes vs ~16 bytes per instance, but only dozens of instances. Negligible.
**Proposed fix**: No action.

### Finding R9 [suggestion]
**Location**: Lambda overhead savings claim
**Issue**: Savings description is imprecise but accurate in direction.
**Proposed fix**: No code impact.

## Decisions

- **R7**: ACCEPT (downgrade from blocker to should-fix — the plan's code is correct, need careful implementation + test)
- **R3**: ACCEPT — include test handling in scope
- **R2, R5**: ACCEPT — enumerate non-migrated methods and specify strategy
- **R10**: ACCEPT — structure steps for incremental migration
- **R8**: ACCEPT — update stale comments
- **R1, R4, R6, R9**: NOTED
