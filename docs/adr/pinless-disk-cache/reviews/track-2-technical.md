# Track 2 Technical Review

## Track: PageFrame abstraction + PageFramePool (Phase 0)

## Findings

### Finding T1 [suggestion]
**Location**: Phase 0 - PageFramePool deallocation safety, plan lines 548-555
**Issue**: The plan's safety argument for deallocated frames relies on `validate()` being
called BEFORE any buffer read. This ordering guarantee is enforced by the caller pattern
in Phase 2 (`DurableComponent.loadPageOptimistic()`), not by Track 2 itself. Track 2
should document this as a contract for future tracks.
**Proposed fix**: Add javadoc to `PageFrame.tryOptimisticRead()` documenting: "Callers
MUST call `validate(stamp)` before any read from `getBuffer()`. This is the safety
contract that prevents reads from deallocated native memory."

### Finding T2 [should-fix]
**Location**: CachePointer constructor, `AtomicOperationBinaryTracking`
**Issue**: `AtomicOperationBinaryTracking` creates `new CachePointer(null, null, fileId,
filledUpTo)` — a sentinel CachePointer with no actual memory. Track 2 changes the
constructor to take `(PageFrame, PageFramePool, ...)`. The null sentinel case must be
handled.
**Proposed fix**: Allow nullable `PageFrame` in CachePointer constructor. Ensure
`decrementReferrer()` checks `pageFrame != null` before calling `framePool.release()`.

### Finding T3 [not-applicable]
**Location**: CachePointer lock signature changes (void → long stamp)
**Issue**: Reviewer flagged lock signature blast radius as a blocker. However, this is
explicitly Track 3 (Phase 1) work. Track 2 keeps CachePointer's existing RRWL locking
unchanged — it only replaces `Pointer` + `ByteBufferPool` with `PageFrame` + `PageFramePool`.
**Resolution**: Not applicable to Track 2. Tracked in Track 3.

### Finding T4 [should-fix]
**Location**: `PageFramePool.acquire()`, `DirectMemoryAllocator.Intention` enum
**Issue**: Plan uses `Intention.ADD_NEW_PAGE` which doesn't exist. Current intentions
include `ADD_NEW_PAGE_IN_DISK_CACHE`, `ADD_NEW_PAGE_IN_MEMORY_STORAGE`,
`LOAD_PAGE_FROM_DISK`, etc.
**Proposed fix**: Pass `Intention` as a parameter to `PageFramePool.acquire()` so callers
can specify the appropriate intention (disk cache vs memory storage vs load).

### Finding T5 [should-fix]
**Location**: WOWCache temporary allocations (flush copies)
**Issue**: WOWCache has 3+ call sites that allocate temporary buffers for flush operations
(`COPY_PAGE_DURING_FLUSH`, `COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH`, `FILE_FLUSH`). These
are short-lived copies, not page frames. They should NOT go through PageFramePool.
**Proposed fix**: ByteBufferPool continues to exist for temporary allocations. Only
page-frame allocations (those that become CachePointer-held) go through PageFramePool.
Document this distinction clearly.

### Finding T6 [suggestion]
**Location**: CachePointer constructor signature change
**Issue**: Breaking constructor change requires updating all 4 call sites simultaneously.
**Resolution**: Acceptable for Track 2 — all callers are identified and updated together.

### Finding T7 [suggestion]
**Location**: Track description clarity
**Issue**: Track 2 = Phase 0 only, but the plan's "Implementation Phases" section shows
both Phase 0 and Phase 1 together.
**Resolution**: Track schedule is already clear (Track 2 vs Track 3). No change needed.

## Summary

- **Blockers**: 0
- **Should-fix**: 3 (T2: null sentinel, T4: Intention parameter, T5: temp buffer separation)
- **Suggestions**: 3 (T1: javadoc contract, T6: breaking change accepted, T7: docs clear)
- **Not applicable**: 1 (T3: Track 3 scope)

All should-fix items are addressable during step implementation without plan changes.
