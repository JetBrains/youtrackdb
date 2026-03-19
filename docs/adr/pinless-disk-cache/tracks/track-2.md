# Track 2: PageFrame abstraction + PageFramePool

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/3 complete)
- [ ] Track-level code review

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
