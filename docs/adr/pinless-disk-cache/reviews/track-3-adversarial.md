# Track 3 Adversarial Review

## Review scope
Track 3: CachePointer refactoring — delegate lock to PageFrame

## Findings

### Finding A1 [blocker]
**Target**: Decision — missing tryAcquireSharedLock() in PageFrame
**Challenge**: Same as T3/R2. Plan omits non-blocking shared lock.
**Evidence**: WOWCache lines 2909, 3105, 3362, 3499 all use
`pointer.tryAcquireSharedLock()` with boolean semantics.
**Proposed fix**: Add to PageFrame.

**Decision**: ACCEPT. Addressed in Step 1.

### Finding A2 [blocker → REJECTED]
**Target**: Assumption — stamp validation equivalence to version comparison
**Challenge**: Reviewer argues StampedLock stamps are not monotonic and could
wrap around, making validate() unreliable vs version comparison.
**Evidence**: StampedLock uses internal state, not a simple counter.

**Decision**: REJECT as blocker. `StampedLock.validate(stamp)` checks whether
the **write sequence number** changed since the stamp was issued — this is
exactly what version comparison does. The internal representation is irrelevant.
Wrap-around requires 2^62+ exclusive lock operations on a single PageFrame —
physically impossible in any reasonable application lifetime (even at 1 billion
ops/sec, this takes ~146 years). The semantics ARE equivalent for copy-then-verify.

### Finding A3 [should-fix]
**Target**: Decision — missing tryAcquireSharedLock in PageFrame
**Challenge**: Same as A1/T3/R2.
**Proposed fix**: Same.

**Decision**: ACCEPT. Duplicate of A1.

### Finding A4 [should-fix]
**Target**: Invariant — nested lock scenario
**Challenge**: Same as T5/R6. Reentrancy risk with StampedLock.
**Evidence**: SharedResourceAbstract has thread-owner tracking for reentrancy.
DiskStorage and WOWCache have multi-lock paths.
**Proposed fix**: Audit + document lock ordering.

**Decision**: ACCEPT. Addressed in Step 5.

### Finding A5 [should-fix]
**Target**: Assumption — CachePointer-PageFrame 1:1 relationship
**Challenge**: Reviewer questions whether multiple CachePointers could share
the same PageFrame, breaking lock delegation coherence.
**Evidence**: PageFramePool.acquire() returns frames to a single caller.
CachePointer constructor takes ownership.

**Decision**: ACCEPT as documentation. The invariant is: one active CachePointer
per PageFrame at a time. PageFramePool enforces this (acquire removes from pool,
release returns to pool). Document this invariant.

### Finding A6 [blocker → REJECTED]
**Target**: Decision — stamp wrap-around in copy-then-verify
**Challenge**: Same as A2. Stamp could theoretically wrap.

**Decision**: REJECT. Same reasoning as A2. 2^62+ ops required.

### Finding A7 [should-fix → PARTIALLY REJECTED]
**Target**: Scope — 5-7 steps underestimated, should be 12-15
**Challenge**: Reviewer argues blast radius requires more steps.
**Evidence**: 10+ files modified.

**Decision**: PARTIALLY REJECT. The signature change is mechanical (compiler-guided)
and fits in one step despite touching many files. All changes are the same kind
(type signature propagation). 5 steps is achievable with clear scoping. The
reviewer conflates file count with step count — a single semantic change across
10 files is still one step.

### Finding A8 [suggestion]
**Target**: Decision — eviction race window
**Challenge**: Window between exclusive lock release and decrementReadersReferrer
allows stamp re-validation.
**Evidence**: This is safe — page is removed from CHM before lock cycle, so
optimistic readers can't find it anyway.

**Decision**: ACCEPT as documentation. The CHM removal (step 3 in eviction flow)
prevents new optimistic lookups. The lock cycle invalidates existing stamps.
Document this ordering.

### Finding A9 [suggestion → REJECTED]
**Target**: Scope — keep CacheEntry interface unchanged, add new stamp methods
**Challenge**: Add new `acquireExclusiveLockWithStamp()` alongside old void methods.
**Evidence**: Reduces blast radius.

**Decision**: REJECT. Dual API increases maintenance burden and confusion. Clean
interface change is better for long-term health. The blast radius is one-time
mechanical work.

### Finding A10 [suggestion]
**Target**: Scope — missing test coverage planning
**Challenge**: No test specifications for new locking semantics.
**Proposed fix**: Add test step.

**Decision**: ACCEPT. Addressed in Step 5.

## Summary
- 2 blockers rejected (A2, A6): stamp semantics are correct, wrap-around impossible
- 1 blocker accepted (A1): missing tryAcquireSharedLock — same as T3
- 3 should-fix accepted (A4, A5, A7 partial)
- 1 suggestion rejected (A9): dual API worse than clean break
- 2 suggestions accepted (A8, A10)
