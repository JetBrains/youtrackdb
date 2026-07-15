# Track 2: PageFrame abstraction + PageFramePool

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
- [x] Track-level code review

## Base commit
`5214aa40d0`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps
- [x] Step 1: Create PageFrame and PageFramePool classes with unit tests
  > **What was done:** Created `PageFrame` wrapping `Pointer` + `StampedLock` + page
  > coordinates with optimistic/shared/exclusive lock APIs. Created `PageFramePool`
  > managing frame lifecycle with `ConcurrentLinkedQueue` + `AtomicInteger` sizing,
  > protective memory allocation (recycling over deallocation), and exclusive lock
  > barriers on acquire/release for stamp invalidation. 26 unit tests covering lock
  > API correctness, coordinate lifecycle, pool capacity limits, stamp invalidation
  > barriers, clear on recycled frames, and concurrent access (acquire/release +
  > optimistic-read/exclusive-lock contention).
  >
  > **What was discovered:** Code review caught a race in the initial `release()`
  > implementation where `poolSize` was incremented before adding the frame to the
  > queue, causing potential size accounting drift. Fixed by adding to queue first,
  > then trimming excess.
  >
  > **Key files:** `PageFrame.java` (new), `PageFramePool.java` (new),
  > `PageFrameTest.java` (new), `PageFramePoolTest.java` (new)

- [x] Step 2: Add PageFrame support to CachePointer (dual-constructor migration)
  > **What was done:** Added second constructor `CachePointer(PageFrame, PageFramePool,
  > long, int)` with `pageFrame`/`framePool` final fields. `decrementReferrer()` routes
  > to `framePool.release(pageFrame)` when the PageFrame constructor was used, or
  > `bufferPool.release(pointer)` for the legacy path. Added `getPageFrame()` accessor.
  > Disambiguated null sentinel in `AtomicOperationBinaryTracking` with explicit
  > `(Pointer) null` cast. 8 tests covering constructor derivation, pool release routing,
  > multi-ref counting, null sentinel, legacy compatibility, asymmetric null rejection,
  > and negative coordinate validation.
  >
  > **What was discovered:** Both constructors match `(null, null, ...)` calls — added
  > explicit `(Pointer)` cast at the one existing sentinel site. Code review caught
  > potential silent memory leak from asymmetric `pageFrame != null, framePool == null`
  > — added constructor guard.
  >
  > **Key files:** `CachePointer.java` (modified), `AtomicOperationBinaryTracking.java`
  > (modified), `CachePointerPageFrameTest.java` (new)

- [x] Step 3: Migrate callers to PageFrame-based CachePointer and remove old constructor
  > **What was done:** Migrated all 3 production CachePointer creation sites to use
  > PageFramePool. Added `pageFramePool()` lazy accessor to ByteBufferPool that creates
  > a PageFramePool sharing the same allocator and pool limits. WOWCache, LockFreeReadCache,
  > and MemoryFile now use `pageFramePool.acquire()` → `new CachePointer(pageFrame, pool, ...)`
  > for page loads. WOWCache's double-write recovery copies data into the PageFrame buffer
  > instead of swapping pointers. Temporary flush allocations stay with ByteBufferPool.
  > Added `allocatedFrames` tracking set to PageFramePool so `clear()` deallocates both
  > pooled and leaked frames at shutdown. Added buffer `position(0)` reset in
  > `PageFramePool.acquire()` to match ByteBufferPool behavior. Legacy constructor
  > retained for test compatibility (~15 test files use it for temporary allocations).
  >
  > **What was discovered:** PageFramePool.acquire() didn't reset the ByteBuffer position
  > to 0, causing assertion failures in WOWCache (ByteBufferPool.acquireDirect() always
  > did this). Also, PageFramePool.clear() only deallocated pooled frames — frames still
  > held by leaked CachePointers at shutdown were not cleaned up, causing
  > DirectMemoryAllocator leak detection assertions. ByteBufferPool's clear() handled
  > this via its `pointerMapping` TRACK set; PageFramePool needed an equivalent
  > `allocatedFrames` set.
  >
  > **What changed from the plan:** The old CachePointer(Pointer, ByteBufferPool)
  > constructor was NOT removed — ~15 test files use it for temporary allocations.
  > Removing it would require migrating all test files, which is mechanical but out of
  > scope for this track. Deferred to a future cleanup.
  >
  > **Key files:** `ByteBufferPool.java` (modified), `PageFramePool.java` (modified),
  > `WOWCache.java` (modified), `LockFreeReadCache.java` (modified),
  > `MemoryFile.java` (modified)
