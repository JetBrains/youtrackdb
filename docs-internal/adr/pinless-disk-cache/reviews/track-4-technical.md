# Track 4: Optimistic Read Infrastructure — Technical Review

## Review Summary

**Track**: Track 4 — Optimistic Read Infrastructure
**Reviewer**: Technical review sub-agent
**Iteration**: 1
**Outcome**: PASS (no blockers after analysis)

## Findings

### Finding T1 [should-fix]
**Location**: Plan Phase 2f — `executeOptimisticStorageRead` method signature
**Issue**: The plan references `TxFunction<T>` and `TxConsumer` as functional interface
types for the optimistic/pinned lambdas. However, the actual `TxFunction<T>` interface
in the codebase has signature `accept(AtomicOperation atomicOperation) throws Exception`
— it takes an `AtomicOperation` parameter. The plan's pseudocode shows zero-argument
lambdas (`optimistic.apply()`, `pinned.apply()`), which don't match.
**Proposed fix**: Create new functional interfaces for optimistic reads:
- `OptimisticReadFunction<T>` with `T call() throws Exception`
- `OptimisticReadAction` with `void call() throws Exception`
Or use `Callable<T>` for the function variant. The new interfaces should be declared
in `DurableComponent.java` or a nearby package.

### Finding T2 [should-fix]
**Location**: Plan Phase 2c — `getPageFrameOptimistic()` and ReadCache interface
**Issue**: The plan adds `getPageFrameOptimistic()` to `LockFreeReadCache` but doesn't
explicitly mention adding it to the `ReadCache` interface. Since `DurableComponent`
holds a `ReadCache` reference (`protected final ReadCache readCache`), the method must
be added to the `ReadCache` interface for the call in `loadPageOptimistic()` to compile.
**Proposed fix**: Add `@Nullable PageFrame getPageFrameOptimistic(long fileId, long pageIndex)`
to the `ReadCache` interface. Only `LockFreeReadCache` provides a real implementation.

### Finding T3 [should-fix]
**Location**: Plan Phase 2e — `DurablePage` PageView constructor and null-safe accessors
**Issue**: `DurablePage.getPageIndex()` delegates to `cacheEntry.getPageIndex()` and will
NPE when `cacheEntry` is null (PageView constructor path). Similarly for `getLsn()`.
The plan doesn't address how subclass code calling inherited accessor methods will work
during optimistic reads.
**Proposed fix**: Store `pageIndex` and `fileId` as local fields in `DurablePage` when
constructed from `PageView`. Delegate to `cacheEntry` when non-null, fall back to local
fields otherwise. `getLsn()` should return null or a sentinel for optimistic reads
(LSN is write-path only).

### Finding T4 [suggestion]
**Location**: Plan Phase 2a — `OptimisticReadScope.reset()` nulling array elements
**Issue**: `reset()` nulls out all `frames[i]` references, which prevents GC pinning
of PageFrame objects between operations. This is correct behavior for long-lived
AtomicOperations. However, most AtomicOperations are short-lived (single transaction),
so the overhead is negligible either way.
**Proposed fix**: Keep nulling for correctness. Consider only nulling up to `count`
(already shown in plan) rather than the full array.

### Finding T5 [suggestion]
**Location**: Plan Phase 2c — `recordOptimisticAccess()` race with eviction
**Issue**: `recordOptimisticAccess()` re-does the CHM lookup after successful validation.
The entry may have been evicted between validation and the lookup. The plan acknowledges
this but doesn't specify behavior.
**Proposed fix**: Accept the race — frequency counting is approximate by nature. If
`data.get(pageKey)` returns null, skip the `afterRead()` call. Also record frequency
on fallback success (when the pinned path succeeds after optimistic failure) to reduce
undercount.

## Decisions

| ID | Decision | Rationale |
|---|---|---|
| T1 | Fix during implementation | Create new functional interfaces; straightforward |
| T2 | Fix during implementation | Add to ReadCache interface in the same step |
| T3 | Fix during implementation | Store pageIndex/fileId locally in DurablePage |
| T4 | Accept as-is | Current plan is correct |
| T5 | Fix during implementation | Skip null entry, record on fallback too |
