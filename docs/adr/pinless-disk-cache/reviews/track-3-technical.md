# Track 3 Technical Review

## Review scope
Track 3: CachePointer refactoring — delegate lock to PageFrame

## Findings

### Finding T1 [blocker]
**Location**: CachePointer lock delegation + sentinel/legacy CachePointer
**Issue**: `AtomicOperationBinaryTracking` creates sentinel CachePointers with
`new CachePointer((Pointer) null, null, fileId, filledUpTo)` — these have
`pageFrame == null`. When lock methods delegate to `pageFrame.acquireExclusiveLock()`,
they will NPE. Similarly, ~15 test files still use the legacy `(Pointer, ByteBufferPool)`
constructor which also has `pageFrame == null`.
**Proposed fix**: CachePointer lock delegation must handle null pageFrame. For the
legacy/sentinel path, keep the existing RRWL as fallback. Or ensure sentinels never
reach lock methods (verified: the assert at line 345 of AtomicOperationBinaryTracking
confirms sentinels should not be locked, but defensive null checks are safer).

**Decision**: ACCEPT. Add null-safe delegation in CachePointer — throw
`IllegalStateException` if lock methods are called on a sentinel (pageFrame == null),
since the existing assert already enforces this invariant. For legacy test constructors,
keep RRWL fallback until they are migrated.

### Finding T2 [should-fix]
**Location**: CacheEntry interface + all callers
**Issue**: Changing `acquireExclusiveLock()` from void to long, and
`releaseExclusiveLock()` to accept long, requires updating all callers simultaneously.
~10 files affected by the interface change.
**Proposed fix**: Document the full list of affected files and treat as one atomic step.

**Decision**: ACCEPT. The signature change is mechanical but must be atomic. One step
handles the interface change + all caller propagation.

### Finding T3 [blocker]
**Location**: CachePointer.tryAcquireSharedLock() + WOWCache (4 call sites)
**Issue**: The plan does not address `tryAcquireSharedLock()` which returns boolean
and is used 4 times in WOWCache (lines 2909, 3105, 3362, 3499). StampedLock has
`tryReadLock()` returning long (0 on failure), but PageFrame doesn't expose it.
**Proposed fix**: Add `tryAcquireSharedLock()` to PageFrame delegating to
`stampedLock.tryReadLock()`. Update CachePointer to delegate. Update WOWCache
call sites to use stamp-based API (0 = failure, non-zero = stamp for release).

**Decision**: ACCEPT. Add `tryAcquireSharedLock()` to PageFrame as a prerequisite step.

### Finding T4 [should-fix]
**Location**: WOWCache copy-then-verify protocol
**Issue**: The semantic equivalence between version comparison and
`StampedLock.validate(stamp)` should be documented. `validate()` returns false
if any exclusive lock was acquired since the stamp was issued — identical semantics
to version mismatch, since version only increments under exclusive lock.
**Proposed fix**: Add comments in WOWCache explaining the equivalence.

**Decision**: ACCEPT. Document in code comments during Step 3.

### Finding T5 [should-fix]
**Location**: Reentrancy audit
**Issue**: StampedLock is NOT reentrant. The plan mentions audit but provides no
concrete results. Must verify no code path acquires the same PageFrame lock twice.
**Proposed fix**: Add a concrete reentrancy audit step.

**Decision**: ACCEPT. Include audit in the reentrancy/cleanup step.

### Finding T6 [suggestion]
**Location**: Stamp flow through call chain
**Issue**: The plan doesn't show how stamps flow through CacheEntry → caller chains.
**Proposed fix**: Callers capture stamp from acquire, pass to release.

**Decision**: ACCEPT as documentation. The pattern is straightforward: capture stamp
at acquire, store in local variable, pass to release. No field storage needed.

### Finding T7 [suggestion]
**Location**: Eviction path stamp invalidation
**Issue**: The acquire+release exclusive lock pattern in eviction seems odd.
**Proposed fix**: Clarify that the purpose is solely to invalidate outstanding optimistic
stamps — the lock cycle bumps the StampedLock state.

**Decision**: ACCEPT. Document the intent in code comments during Step 4.

## Summary
- 2 blockers accepted: null PageFrame handling (T1), missing tryAcquireSharedLock (T3)
- 2 should-fix accepted: documentation (T4), reentrancy audit (T5)
- 2 suggestions accepted: stamp flow documentation (T6), eviction comment (T7)
- All findings addressed in step decomposition
