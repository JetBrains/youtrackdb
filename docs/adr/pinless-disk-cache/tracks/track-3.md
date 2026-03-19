# Track 3: CachePointer refactoring — delegate lock to PageFrame

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/5 complete)
- [ ] Track-level code review

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

- [ ] Step 3: WOWCache version → stamp migration
  > Replace the version-based copy-then-verify protocol with stamp-based validation.
  >
  > **Changes:**
  > - Remove `version` field from `CachePointer` (and `getVersion()`/increment logic)
  > - Update copy phase (3 sites: ~lines 2900, 3354, 3499): capture stamp from
  >   `acquireSharedLock()`, store in `WritePageContainer` instead of version
  > - Update `WritePageContainer` record: `long pageVersion` → `long pageStamp`
  > - Update remove phase (`removeWrittenPagesFromCache`, ~line 3110): replace
  >   `version == pointer.getVersion()` with `pageFrame.validate(chunkPage.pageStamp)`
  > - Update `tryAcquireSharedLock` call sites in WOWCache (4 sites): change from
  >   boolean-based `if (pointer.tryAcquireSharedLock())` to stamp-based
  >   `long stamp = pointer.tryAcquireSharedLock(); if (stamp != 0)`
  > - Add documentation comments explaining stamp validation equivalence:
  >   "validate(stamp) returns false if any exclusive lock was acquired since stamp
  >   was issued — identical to version mismatch, since version only increments under
  >   exclusive lock"
  >
  > **Files**: `CachePointer.java` (modified — remove version), `WOWCache.java` (modified)

- [ ] Step 4: Eviction path — stamp invalidation in WTinyLFUPolicy
  > Add exclusive lock cycle to eviction path to invalidate outstanding optimistic stamps
  > before the ReadCache drops its CachePointer reference.
  >
  > **Changes in WTinyLFUPolicy:**
  > - `purgeEden()`: after `victim.freeze()` + CHM removal + `victim.makeDead()`,
  >   acquire+release exclusive lock on CachePointer before `decrementReadersReferrer()`
  > - `onRemove()`: same pattern for direct eviction
  > - Add comments explaining: "The exclusive lock cycle bumps the StampedLock state,
  >   invalidating all outstanding optimistic stamps. This is the first invalidation barrier.
  >   PageFramePool.release() provides a second barrier when referrer count reaches 0."
  >
  > **Files**: `WTinyLFUPolicy.java` (modified)

- [ ] Step 5: Reentrancy audit + test hardening + cleanup
  > **Reentrancy audit:**
  > - Trace all code paths that acquire PageFrame locks (via CachePointer/CacheEntry)
  > - Verify no path acquires the same PageFrame lock twice (StampedLock deadlock)
  > - Document lock ordering: component-level SharedResource lock → page-level PageFrame lock
  > - Verify DiskStorage, WOWCache flush, B-tree split paths are safe
  >
  > **Test hardening:**
  > - Add tests for stamp invalidation on eviction (acquire stamp → evict page →
  >   validate returns false)
  > - Add tests for WOWCache copy-then-verify with stamp validation (copy under shared lock →
  >   modify page under exclusive lock → validate returns false)
  > - Add tests for tryAcquireSharedLock under contention
  > - Verify existing WOWCacheTestIT passes with stamp-based locking
  >
  > **Cleanup:**
  > - Remove `isLockAcquiredByCurrentThread()` from CacheEntry if still present
  > - Remove any unused RRWL-related imports
  > - Document CachePointer-PageFrame lifetime invariant: one active CachePointer
  >   per PageFrame at a time
  >
  > **Files**: `WTinyLFUPolicy.java` or test files (modified/new),
  > `CachePointer.java` (cleanup), documentation
