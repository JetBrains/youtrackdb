# Track 3 Risk Review

## Review scope
Track 3: CachePointer refactoring — delegate lock to PageFrame

## Findings

### Finding R1 [blocker]
**Location**: CacheEntry signature migration — all implementations and call sites
**Issue**: Lock signature change from void to long propagates through CacheEntry,
CacheEntryImpl, CacheEntryChanges, and ~8 caller files. Must happen atomically.
High likelihood of compilation failures if any site is missed.
**Proposed fix**: Audit all CacheEntry implementations and call sites upfront.
Treat as single atomic step guided by compiler errors.

**Decision**: ACCEPT. Same as T2. One atomic step for signature change.

### Finding R2 [blocker]
**Location**: tryAcquireSharedLock() — 4 WOWCache call sites
**Issue**: Same as T3. `tryAcquireSharedLock()` not in plan. StampedLock's
`tryReadLock()` returns long stamp (0 = failed). WOWCache patterns must change
from boolean to stamp-based.
**Proposed fix**: Add to PageFrame, update CachePointer and WOWCache.

**Decision**: ACCEPT. Same as T3.

### Finding R3 [should-fix]
**Location**: WOWCache copy-then-verify — stamp validation semantics
**Issue**: `StampedLock.validate(stamp)` after releasing a read lock: returns false
if any exclusive lock was acquired since the stamp was issued. This IS semantically
equivalent to version comparison for copy-then-verify. But the equivalence is subtle.
**Proposed fix**: Document explicitly in code and review file.

**Decision**: ACCEPT. The key insight: stamps from `readLock()` capture the write
sequence number. After `unlockRead()`, `validate(stamp)` still checks if the write
sequence changed — same as version comparison. Document this.

### Finding R4 [should-fix]
**Location**: Eviction double invalidation (eviction + pool release)
**Issue**: Exclusive lock is acquired during eviction AND in PageFramePool.release()
when referrer count reaches 0. This double invalidation is safe but wasteful.
**Proposed fix**: Document the intentional double invalidation as belt-and-suspenders.

**Decision**: ACCEPT. The first invalidation (eviction) handles the common case.
The second (pool release) handles edge cases. Keep both, document.

### Finding R5 [should-fix]
**Location**: Legacy Pointer constructor — null PageFrame path
**Issue**: Same as T1. CachePointer with legacy constructor has pageFrame == null.
Lock delegation will fail.
**Proposed fix**: Same as T1.

**Decision**: ACCEPT. Same as T1.

### Finding R6 [should-fix]
**Location**: Nested lock detection — StampedLock non-reentrancy
**Issue**: Same as T5. Must audit all paths for nested PageFrame lock acquisition.
**Proposed fix**: Concrete audit step with documented findings.

**Decision**: ACCEPT. Key finding from preliminary analysis: CacheEntry locks
(page-level) are acquired in try-with-resources blocks — pages are accessed
sequentially, not nested. But this must be verified, especially for DiskStorage
export path and WOWCache flush path.

### Finding R7 [suggestion]
**Location**: WTinyLFUPolicy eviction integration point
**Issue**: The plan doesn't specify exactly where exclusive lock is acquired in
the eviction code path.
**Proposed fix**: Specify: in WTinyLFUPolicy.purgeEden() and onRemove(), after
freeze() and before decrementReadersReferrer().

**Decision**: ACCEPT. Addressed in Step 4.

### Finding R8 [should-fix]
**Location**: WritePageContainer stamp lifetime
**Issue**: Stamps are volatile in-memory state. Plan should clarify they are never
serialized or persisted.
**Proposed fix**: Add field comment in WritePageContainer.

**Decision**: ACCEPT. Minor — add during Step 3.

### Finding R9 [should-fix]
**Location**: Test coverage for stamp-based locking
**Issue**: No test specifications for eviction+stamp interaction, concurrent
reads during eviction, or WOWCache copy-then-verify with stamps.
**Proposed fix**: Add test cases in reentrancy/testing step.

**Decision**: ACCEPT. Addressed in Step 5.

### Finding R10 [suggestion]
**Location**: Blast radius quantification
**Issue**: Plan says "large blast radius" but doesn't enumerate.
**Proposed fix**: Enumerate in step decomposition.

**Decision**: ACCEPT. Step 2 description will list all affected files.

## Summary
- 2 blockers accepted (R1, R2) — same as T2/T3
- 6 should-fix accepted — documentation, reentrancy audit, test coverage
- 2 suggestions accepted
- All findings addressed in step decomposition
