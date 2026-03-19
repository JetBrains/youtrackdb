# Track 3: CachePointer refactoring — delegate lock to PageFrame

## Progress
- [x] Review + decomposition
- [x] Step implementation (5/5 complete)
- [ ] Track-level code review (1/3 iterations)

## Base commit
`6e4ac067`

## Reviews completed
- [x] Technical — 2 blockers (null PageFrame handling, missing tryAcquireSharedLock), 2 should-fix, 2 suggestions. All accepted and addressed in step decomposition. See reviews/track-3-technical.md
- [x] Risk — 2 blockers (same as technical), 6 should-fix (documentation, reentrancy audit, test coverage), 2 suggestions. All accepted. See reviews/track-3-risk.md
- [x] Adversarial — 2 blockers rejected (stamp wrap-around — physically impossible, 2^62+ ops needed), 1 blocker accepted (same tryAcquireSharedLock gap). Dual API alternative rejected — clean break preferred. See reviews/track-3-adversarial.md

## Key review decisions
1. **tryAcquireSharedLock()** must be added to PageFrame (delegates to `stampedLock.tryReadLock()`). Gap in original plan — 4 WOWCache call sites depend on it.
2. **Null PageFrame handling**: Sentinel CachePointers (AtomicOperationBinaryTracking) and legacy test constructors have `pageFrame == null`. Lock methods must throw `IllegalStateException` for sentinels (invariant: sentinels are never locked). Legacy test constructor retains RRWL fallback.
3. **Stamp validation equivalence**: `StampedLock.validate(stamp)` IS semantically equivalent to version comparison for copy-then-verify. Both detect exclusive lock acquisition since capture. Document this.
4. **Stamp wrap-around**: NOT a concern. Requires 2^62+ exclusive lock ops on one PageFrame (~146 years at 1B ops/sec). Rejected as blocker.
5. **CacheEntry interface**: Clean signature change (void → long) preferred over dual API. Mechanical but must be atomic.
6. **Reentrancy**: StampedLock is non-reentrant. Must audit all code paths. Preliminary finding: page locks are acquired sequentially in try-with-resources, not nested. Must verify.

## Steps

- [x] Step 1: Add tryAcquireSharedLock and tryAcquireExclusiveLock to PageFrame
  > **What was done:** Added `tryAcquireSharedLock()` and `tryAcquireExclusiveLock()`
  > to PageFrame, delegating to `StampedLock.tryReadLock()` / `tryWriteLock()`.
  > Added 9 unit tests covering success/failure paths, contention, stamp validity,
  > shared lock coexistence, and optimistic stamp invalidation semantics.
  >
  > **Key files:** `PageFrame.java` (modified), `PageFrameTest.java` (modified)

- [x] Step 2: CachePointer — replace RRWL with PageFrame lock delegation + CacheEntry signature change
  > **What was done:** Removed RRWL from CachePointer, delegated all lock methods to
  > PageFrame's StampedLock. Changed CacheEntry interface: acquire methods return long
  > stamps, releaseSharedLock takes stamp parameter. CacheEntryImpl stores exclusive stamp
  > internally (single-writer safe). Propagated stamp-based API to all callers across 10 files
  > including WOWCache (5 tryAcquireSharedLock sites), LockFreeReadCache, DiskStorage,
  > DirectMemoryOnlyDiskCache, and WOWCacheTestIT.
  >
  > **What changed from the plan:** CacheEntry.releaseExclusiveLock() remains parameterless
  > (stamp stored internally in CacheEntryImpl) instead of taking a stamp parameter. This
  > avoids threading stamps across loadForWrite/releaseFromWrite boundaries in ReadCache
  > implementations. CacheEntry.releaseSharedLock(stamp) does take a stamp parameter because
  > multiple threads hold shared locks on the same CacheEntry. Also removed cache-level shared
  > lock from DirectMemoryOnlyDiskCache.loadForRead/releaseFromRead (aligned with
  > LockFreeReadCache where components manage their own locks). tryAcquireSharedLock NOT added
  > to CacheEntry interface (only used on CachePointer directly by WOWCache).
  >
  > **Key files:** `CachePointer.java` (modified), `CacheEntry.java` (modified),
  > `CacheEntryImpl.java` (modified), `CacheEntryChanges.java` (modified),
  > `LockFreeReadCache.java` (modified), `WOWCache.java` (modified),
  > `DiskStorage.java` (modified), `DirectMemoryOnlyDiskCache.java` (modified),
  > `AtomicOperationBinaryTracking.java` (modified), `WOWCacheTestIT.java` (modified)

- [x] Step 3: WOWCache version → stamp migration
  > **What was done:** Replaced CachePointer's version-based copy-then-verify with
  > StampedLock stamp validation. WritePageContainer now stores `pageStamp` (the shared
  > lock stamp from copy) instead of `pageVersion`. Three copy sites use the already-captured
  > `sharedStamp`. removeWrittenPagesFromCache validates via `pageFrame.validate(stamp)` —
  > no shared lock needed (just a volatile read). Removed `version` field and `getVersion()`
  > from CachePointer. Made PageFrame constructor public. Legacy CachePointer constructor now
  > creates standalone PageFrame for lock delegation (fixing test failures).
  >
  > **What was discovered:** Legacy `CachePointer(Pointer, ByteBufferPool)` constructor was
  > used by many tests and the LockFreeReadCacheBatchingTest mock WriteCache. These created
  > CachePointers with null pageFrame, causing IllegalStateException on lock calls. Fixed by
  > creating standalone (non-pooled) PageFrame in the legacy constructor when pointer is
  > non-null. tryAcquireSharedLock sites were already updated in Step 2.
  >
  > **Key files:** `CachePointer.java` (modified), `WOWCache.java` (modified),
  > `PageFrame.java` (modified — public constructor), `CachePointerPageFrameTest.java`
  > (modified)

- [x] Step 4: Eviction path — stamp invalidation in WTinyLFUPolicy
  > **What was done:** Added exclusive lock acquire+release cycle at all 3 eviction sites
  > (purgeEden victim, purgeEden candidate, onRemove) to bump StampedLock state and
  > invalidate outstanding optimistic stamps before decrementReadersReferrer(). Extracted
  > `invalidateStampsAndRelease()` helper method with full Javadoc to consolidate the
  > pattern and prevent future eviction paths from missing the stamp invalidation.
  >
  > **Key files:** `WTinyLFUPolicy.java` (modified)

- [x] Step 5: Reentrancy audit + test hardening + cleanup
  > **What was done:** Audited all 17 lock acquisition sites across 6 classes. Confirmed
  > no reentrancy risk — strict lock hierarchy (SharedResource → lockManager → PageFrame
  > StampedLock) is respected, each code path acquires at most one PageFrame lock. Added
  > CachePointer class Javadoc documenting lock ordering, non-reentrancy contract, and
  > lifetime invariant. Added 2 tests: eviction cycle with frame reassignment invalidates
  > optimistic stamp; shared lock stamp invalidated by exclusive lock (copy-then-verify
  > pattern). Verified WOWCacheTestIT (12 tests) passes with stamp-based locking.
  > `isLockAcquiredByCurrentThread()` already removed in Step 2. No stale RRWL imports.
  >
  > **Key files:** `CachePointer.java` (modified — Javadoc), `PageFrameTest.java` (modified)
