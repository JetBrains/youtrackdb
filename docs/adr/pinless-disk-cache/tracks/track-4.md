# Track 4: Optimistic Read Infrastructure

## Progress
- [x] Review + decomposition
- [x] Step implementation (5/5 complete)
- [x] Track-level code review (2/3 iterations — PASS)

## Base commit
`ba2c806616`

## Reviews completed
- [x] Technical (iteration 1 — PASS, 5 findings: 3 should-fix, 2 suggestion, 0 blocker)
- [x] Risk (iteration 1 — PASS, 4 findings: 1 should-fix, 3 suggestion, 0 blocker)

## Steps

- [x] Step 1: Create OptimisticReadScope, OptimisticReadFailedException, and PageView
  > **What was done:** Created three new classes in the cache package:
  > `OptimisticReadScope` (growable array tracker for PageFrame+stamp pairs with
  > record/validate/validateLast/reset), `OptimisticReadFailedException` (singleton,
  > no stack trace, `super(null, null, true, false)`), and `PageView` (record with
  > compact constructor assertions). 18 unit tests across 3 test classes covering
  > happy path, stamp invalidation, cross-thread invalidation, growth, reset/reuse,
  > partial invalidation ordering.
  >
  > **Key files:** `OptimisticReadScope.java` (new), `OptimisticReadFailedException.java`
  > (new), `PageView.java` (new), `OptimisticReadScopeTest.java` (new),
  > `OptimisticReadFailedExceptionTest.java` (new), `PageViewTest.java` (new)

- [x] Step 2: Integrate OptimisticReadScope into AtomicOperation
  > **What was done:** Added `getOptimisticReadScope()` default method to
  > `AtomicOperation` interface (throws UnsupportedOperationException). Overrode in
  > `AtomicOperationBinaryTracking` with eagerly allocated `OptimisticReadScope` field.
  > Added 3 tests: scope accessibility, same-instance reuse, default method guard.
  >
  > **Key files:** `AtomicOperation.java` (modified), `AtomicOperationBinaryTracking.java`
  > (modified), `AtomicOperationSnapshotProxyTest.java` (modified)

- [x] Step 3: Add optimistic lookup to ReadCache / LockFreeReadCache
  > **What was done:** Added `getPageFrameOptimistic()` and `recordOptimisticAccess()`
  > to `ReadCache` interface. Implemented in `LockFreeReadCache` (CHM lookup, isAlive
  > check, CachePointer→PageFrame dereference, no CAS). `DirectMemoryOnlyDiskCache`
  > returns null (always falls back). Created `PageFrameWriteCache` test mock producing
  > PageFrame-backed CachePointers. 8 tests covering hit/miss/eviction/stamp/identity.
  >
  > **What was discovered:** `DirectMemoryOnlyDiskCache` also implements `ReadCache` —
  > needed stub methods there. Code review noted `CacheEntryImpl.dataPointer` is not
  > volatile, creating a theoretical TOCTOU window between isAlive() and getCachePointer()
  > during eviction. Stamp validation is the primary defense; making `dataPointer`
  > volatile would be belt-and-suspenders but is out of scope for this step.
  >
  > **Key files:** `ReadCache.java` (modified), `LockFreeReadCache.java` (modified),
  > `DirectMemoryOnlyDiskCache.java` (modified),
  > `LockFreeReadCacheOptimisticTest.java` (new)

- [x] Step 4: Add DurablePage PageView constructor + speculativeRead flag + guardSize
  > **What was done:** Added `DurablePage(PageView)` constructor setting
  > `cacheEntry=null`, `changes=null`, `speculativeRead=true`. Stored `pageIndex`
  > locally for null-safe `getPageIndex()`. `getLsn()` returns null in speculative
  > mode. Added `guardSize()` (throws OptimisticReadFailedException for negative/overflow)
  > to `getBinaryValue`, `getIntArray`, `getObjectSizeInDirectMemory`. Added
  > `assertNotSpeculative()` to all 9 setter/mutator methods (review finding).
  > 8 unit tests covering constructor, guard boundaries, page index, LSN null.
  >
  > **Key files:** `DurablePage.java` (modified),
  > `DurablePageSpeculativeReadTest.java` (new)

- [x] Step 5: Add DurableComponent helpers — loadPageOptimistic + executeOptimisticStorageRead
  > **What was done:** Added `loadPageOptimistic()` (optimistic CHM lookup, stamp
  > acquisition, coordinate verification, scope recording) and
  > `executeOptimisticStorageRead()` (function + void variants: try optimistic, validate
  > scope, record accesses, fallback to shared lock + pinned path). Created 4
  > `@FunctionalInterface` types (OptimisticReadFunction, OptimisticReadAction,
  > PinnedReadFunction, PinnedReadAction). Added `getFrame()` to OptimisticReadScope.
  > 8 tests using TestDurableComponent subclass with mock ReadCache: happy path, cache
  > miss, stamp invalidation (real cross-thread test), coordinate mismatch, scope reset,
  > void variant + fallback.
  >
  > **What was discovered:** Post-validation `recordOptimisticAccesses()` reads frame
  > coordinates without re-validation — benign TOCTOU that may cause a frequency bump
  > for the wrong page. Documented in code as harmless eviction heuristic skew.
  >
  > **Key files:** `DurableComponent.java` (modified), `OptimisticReadScope.java`
  > (modified), `DurableComponentOptimisticReadTest.java` (new)
