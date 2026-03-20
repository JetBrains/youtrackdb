# Track 4: Optimistic Read Infrastructure

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/5 complete)
- [ ] Track-level code review

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

- [ ] Step 4: Add DurablePage PageView constructor + speculativeRead flag + guardSize
  - Add `private final boolean speculativeRead` field to `DurablePage` (false in existing
    constructor, true in new PageView constructor).
  - New constructor: `DurablePage(PageView pageView)` — sets `cacheEntry = null`,
    `changes = null`, `buffer = pageView.buffer()`, `speculativeRead = true`.
  - Store `pageIndex` locally (from `pageView.pageFrame().getPageIndex()`) for
    null-safe `getPageIndex()` (review finding T3). `getLsn()` returns null when
    `cacheEntry == null`.
  - Add `guardSize(long sizeInBytes)`: throws `OptimisticReadFailedException` if
    `sizeInBytes < 0 || sizeInBytes > buffer.capacity()`.
  - Add guards to: `getBinaryValue()`, `getIntArray()`, `getObjectSizeInDirectMemory()`
    when `speculativeRead == true` (review finding R1).
  Tests: PageView constructor sets fields correctly, guardSize passes for valid sizes,
  guardSize throws for negative/overflow/exceeds-page-size, speculativeRead=false
  skips guards, getPageIndex() works with both constructors.

- [ ] Step 5: Add DurableComponent helpers — loadPageOptimistic + executeOptimisticStorageRead
  - Add to `DurableComponent`:
    - `loadPageOptimistic(AtomicOperation, long fileId, long pageIndex)`: calls
      `readCache.getPageFrameOptimistic()`, takes stamp via `tryOptimisticRead()`,
      verifies coordinates match, records in scope, returns `PageView`. Throws
      `OptimisticReadFailedException` on cache miss, stamp=0, or coordinate mismatch.
    - `executeOptimisticStorageRead(AtomicOperation, optimistic, pinned)`: resets scope,
      runs optimistic lambda, validates scope, catches `OptimisticReadFailedException`
      → acquires shared lock → runs pinned lambda → releases shared lock. Both function
      and void variants. Comment: reentrancy safe per SharedResourceAbstract (R3).
  - Create functional interfaces (review finding T1): either inner interfaces in
    DurableComponent or `Callable<T>` + custom void variant. Must support checked
    exceptions (IOException).
  Tests: happy path (valid stamp → returns result), fallback on cache miss, fallback
  on stamp invalidation (exclusive lock from another thread), fallback on coordinate
  mismatch, scope reset between calls, void variant. Use a test subclass of
  DurableComponent with a mock ReadCache.
