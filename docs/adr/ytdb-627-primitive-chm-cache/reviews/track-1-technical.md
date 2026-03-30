# Track 1 Technical Review

## Findings

### Finding T1 [suggestion]
**Location**: Track 1, `compute()` API / Track 2 integration
**Issue**: `doLoad`/`silentLoadForRead` lambdas use `page.fileId()` from the compute callback. The new `LongIntKeyValueFunction` provides these as parameters, so the translation is natural.
**Proposed fix**: No Track 1 change. Track 2 integration note.

### Finding T2 [blocker]
**Location**: Track 1, `compute()` null-return semantics
**Issue**: `silentLoadForRead()` uses `compute()` where the remapping function always returns null on the absent-key branch (lock-only pattern). `ConcurrentHashMap.compute()` contract: absent key + null return = no-op; present key + null return = removal. Track 1 description does not explicitly call this out.
**Proposed fix**: Add explicit constraint and unit tests for both null-return cases.
**Decision**: ACCEPTED — add as constraint in Track 1.

### Finding T3 [should-fix]
**Location**: Track 1, `removeByFileId` tombstone cleanup
**Issue**: Scattered non-contiguous tombstones after bulk removal may not be cleaned by backward sweep, keeping `usedBuckets` high and potentially triggering unnecessary resize.
**Proposed fix**: After sweep, if tombstones exceed threshold, do same-capacity rehash (compaction). Add unit test.
**Decision**: ACCEPTED.

### Finding T4 [suggestion]
**Location**: Track 1, `hashForFrequencySketch` static method
**Issue**: Hash values will differ from `PageKey.hashCode()`. Track 2 must update mock expectations.
**Proposed fix**: Move `hashForFrequencySketch` to Track 2 scope entirely.
**Decision**: ACCEPTED — moved to Track 2.

### Finding T5 [should-fix]
**Location**: Track 1, `values()` iteration API
**Issue**: `clear()` and `assertConsistency()` use `data.values()`. Track 1 has `forEach(LongIntObjConsumer)` but not value-only iteration.
**Proposed fix**: Add `forEachValue(Consumer<V>)` convenience method.
**Decision**: ACCEPTED.

### Finding T6 [suggestion]
**Location**: Track 1, `Section extends StampedLock`
**Issue**: Inheritance exposes all StampedLock methods. Codebase favors composition.
**Proposed fix**: Use composition (has-a StampedLock field). 16 extra objects = negligible.
**Decision**: ACCEPTED as suggestion — use composition.

### Finding T7 [blocker]
**Location**: Track 1, conditional `remove(fileId, pageIndex, expected)` semantics
**Issue**: Must specify reference equality (`==`) vs `equals()` for value comparison. Current usage passes the exact same object reference.
**Proposed fix**: Use reference equality (`==`). Add test verifying `.equals()` but `!=` does NOT remove.
**Decision**: ACCEPTED.

### Finding T8 [suggestion]
**Location**: Track 1, target package organization
**Issue**: Three functional interfaces could clutter `collection/` package.
**Proposed fix**: Nest functional interfaces inside `ConcurrentLongIntHashMap`.
**Decision**: ACCEPTED.

### Finding T9 [should-fix]
**Location**: Track 1, `compute()` side effects under lock
**Issue**: Remapping function executes under segment write lock. Disk I/O blocks all segment access.
**Proposed fix**: Add doc comment on `compute()` warning about lock-held execution.
**Decision**: ACCEPTED.

### Finding T10 [suggestion]
**Location**: Track 1, `get()` used in `assertConsistency()` with optimistic reads
**Issue**: Optimistic read fallback during assertion is fine — not a hot path.
**Proposed fix**: No change needed.
**Decision**: No action.
