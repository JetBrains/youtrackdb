# Track 2 Risk Review

## Track: PageFrame abstraction + PageFramePool (Phase 0)

## Findings

### Finding R1 [not-applicable]
**Location**: PageFramePool.acquire() memory ordering
**Issue**: Reviewer flagged memory ordering hazard on pool acquire. However, the frame is
not accessible to optimistic readers during pool acquire — it only becomes visible after
being placed in the cache (via CachePointer). The exclusive lock on acquire invalidates
stale stamps from previous use, which is sufficient.
**Resolution**: Not a real risk for Track 2. Optimistic read ordering is a Track 4 concern.

### Finding R2 [not-applicable]
**Location**: CachePointer lock signature changes
**Resolution**: Track 3 scope, not Track 2.

### Finding R3 [not-applicable]
**Location**: StampedLock reentrancy
**Resolution**: Track 3 scope. Track 2 keeps CachePointer's existing RRWL.

### Finding R4 [not-applicable]
**Location**: OptimisticReadScope capacity
**Resolution**: Track 4 scope.

### Finding R5 [suggestion]
**Location**: SoftReference in Pointer after deallocation
**Issue**: If PageFramePool deallocates native memory and GC clears the SoftReference in
Pointer, `getNativeByteBuffer()` could return null.
**Proposed fix**: Minor — Pointer already handles SoftReference re-creation. The concern
is valid but low-likelihood. Add a null check in `PageFrame.getBuffer()` that throws
`IllegalStateException` if the underlying Pointer has been deallocated.

### Finding R6-R10 [not-applicable]
**Resolution**: All apply to Track 3+ (copy-verify protocol, OptimisticReadScope,
coordinate checks, fallback strategy).

## Track 2 Specific Risks

### Finding R11 [should-fix]
**Location**: CachePointer migration — existing RRWL preservation
**Issue**: Track 2 must change CachePointer to hold PageFrame while keeping the existing
`ReentrantReadWriteLock`. The RRWL currently lives in CachePointer and is used for all
page read/write locking. PageFrame introduces its own StampedLock, but Track 2 must NOT
use it yet — Track 3 handles the lock delegation.
**Likelihood**: Medium (tempting to start using PageFrame's StampedLock early)
**Impact**: Correctness — premature lock delegation could break existing locking
**Proposed fix**: During Track 2 implementation, CachePointer keeps its RRWL entirely
unchanged. PageFrame's StampedLock is created but unused until Track 3. Add a comment:
"StampedLock is present for future optimistic read support (Track 3). Currently unused."

### Finding R12 [should-fix]
**Location**: ByteBufferPool partial replacement
**Issue**: ByteBufferPool must continue to exist for temporary allocations (flush copies,
checksum verification, etc.). If Track 2 removes ByteBufferPool entirely, these callers
break. If Track 2 keeps ByteBufferPool as a thin wrapper over PageFramePool, the
temporary allocations get unnecessary StampedLock overhead.
**Likelihood**: High (design decision needed during implementation)
**Impact**: Performance — unnecessary lock overhead on temp buffers; or Correctness —
broken callers if ByteBufferPool removed
**Proposed fix**: Keep ByteBufferPool for temporary direct memory allocations (no
StampedLock, no pooling of frames). PageFramePool is exclusively for page-sized frames
that will live in cache. The two pools share the same DirectMemoryAllocator but are
otherwise independent.

## Summary

- **Blockers**: 0
- **Should-fix**: 2 (R11: preserve RRWL, R12: dual pool strategy)
- **Suggestions**: 1 (R5: SoftReference safety)
- **Not applicable**: 8 (R1-R4, R6-R10 — all Track 3+ scope)

Track 2 is low-risk as a standalone change. The main risks are integration-related:
ensuring CachePointer's existing locking is preserved and ByteBufferPool coexists with
PageFramePool for different allocation patterns.
