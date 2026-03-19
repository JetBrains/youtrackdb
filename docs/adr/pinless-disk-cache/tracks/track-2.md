# Track 2: PageFrame abstraction + PageFramePool

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/3 complete)
- [ ] Track-level code review

## Reviews completed
- [x] Technical
- [x] Risk

## Steps
- [ ] Step 1: Create PageFrame and PageFramePool classes with unit tests
  > Introduce the new abstraction layer in `com.jetbrains.youtrackdb.internal.common.directmemory`.
  >
  > **PageFrame** wraps an existing `Pointer` (native direct memory) plus a `StampedLock`
  > for future optimistic read support (Track 3+), plus page coordinates (`fileId`,
  > `pageIndex`) set under exclusive lock for frame reuse detection. Exposes:
  > - Optimistic read API: `tryOptimisticRead()`, `validate(stamp)`
  > - Exclusive lock API: `acquireExclusiveLock()`, `releaseExclusiveLock(stamp)`
  > - Shared lock API: `acquireSharedLock()`, `releaseSharedLock(stamp)`
  > - Page identity: `setPageCoordinates(fileId, pageIndex)`, getters
  > - Memory access: `getBuffer()` (delegates to Pointer), `getPointer()`, `clear()`
  >
  > **PageFramePool** manages frame lifecycle. `acquire(clear, intention)` pulls from a
  > `ConcurrentLinkedQueue` or allocates new via `DirectMemoryAllocator`. On acquire from
  > pool, acquires+releases exclusive lock to invalidate stale stamps. `release(frame)`
  > acquires exclusive lock (stamp invalidation barrier), clears page coordinates, returns
  > to pool if under capacity, otherwise deallocates via allocator. `Intention` is passed
  > through to the allocator for profiling.
  >
  > Unit tests cover: lock API correctness, page coordinate lifecycle, pool acquire/release
  > cycling, pool capacity limits with deallocation, concurrent acquire/release.
  >
  > **Key files:** `PageFrame.java` (new), `PageFramePool.java` (new),
  > `PageFrameTest.java` (new), `PageFramePoolTest.java` (new)

- [ ] Step 2: Add PageFrame support to CachePointer (dual-constructor migration)
  > Add a second constructor to `CachePointer` accepting `PageFrame` + `PageFramePool`
  > alongside the existing `Pointer` + `ByteBufferPool` constructor. This enables
  > incremental caller migration without breaking existing code.
  >
  > New constructor: `CachePointer(PageFrame, PageFramePool, long fileId, int pageIndex)`
  > - Stores `pageFrame` and `framePool` fields
  > - Derives `pointer` from `pageFrame.getPointer()` (existing code still works via pointer)
  > - `getPageFrame()` accessor for future Track 3 use
  > - `getBuffer()` delegates through `pageFrame.getBuffer()` when pageFrame is present
  >
  > Update `decrementReferrer()`: when `referrersCount` reaches 0 and `pageFrame != null`,
  > call `framePool.release(pageFrame)` instead of `bufferPool.release(pointer)`.
  >
  > Handle null sentinel: `CachePointer(null, null, fileId, pageIndex)` still works (used
  > by `AtomicOperationBinaryTracking` for metadata-only entries).
  >
  > CachePointer's `ReentrantReadWriteLock` and `version` field remain **unchanged** —
  > lock delegation to PageFrame's StampedLock is deferred to Track 3.
  >
  > Tests: existing CachePointer tests pass unchanged. New tests verify PageFrame-based
  > constructor, release lifecycle, null sentinel case.
  >
  > **Key files:** `CachePointer.java` (modified), CachePointer test (modified/new)

- [ ] Step 3: Migrate callers to PageFrame-based CachePointer and remove old constructor
  > Update all CachePointer creation sites to use `PageFramePool` → `PageFrame` → new
  > `CachePointer` constructor:
  >
  > - **WOWCache.java** (page load path only): `bufferPool.acquireDirect()` → create
  >   PageFrame from acquired Pointer, pass to new CachePointer constructor. Temporary
  >   allocations (flush copies: `COPY_PAGE_DURING_FLUSH`, `FILE_FLUSH`, etc.) continue
  >   using `ByteBufferPool` directly — these are short-lived buffers, not page frames.
  > - **LockFreeReadCache.java** (`addNewPagePointerToTheCache`): use PageFramePool.
  > - **MemoryFile.java** (`addNewPage`): use PageFramePool.
  >
  > After all callers are migrated, remove the old `CachePointer(Pointer, ByteBufferPool,
  > ...)` constructor. `ByteBufferPool` continues to exist for temporary allocations.
  >
  > WOWCache and LockFreeReadCache need `PageFramePool` injected (constructor parameter
  > or via the existing `ByteBufferPool` instance — decide during implementation based
  > on how `ByteBufferPool.instance()` is accessed).
  >
  > Run full `core` module test suite to verify no regressions.
  >
  > **Key files:** `WOWCache.java` (modified), `LockFreeReadCache.java` (modified),
  > `MemoryFile.java` (modified), `CachePointer.java` (modified — remove old constructor)
