# Track 4 Risk Review

## Finding R1 [resolved — same as T2]
SharedLinkBagBTree.get() has no external callers. New findVisibleEntry() method
will be added instead.

## Finding R2 [should-fix — same as T4]
Spliterator cache-filling needs SI visibility. Addressed in step decomposition.

## Finding R3 [should-fix]
**Location**: Self-read shortcut
**Issue**: Must use getCommitTsUnsafe() for the self-read comparison, matching
PaginatedCollectionV2 pattern.
**Decision**: Follow established pattern.

## Finding R4 [should-fix]
**Location**: Snapshot index bounds
**Issue**: Incorrect bounds would cause silent fallback failures.
**Decision**: Document exact key construction. Use Long.MIN_VALUE/Long.MAX_VALUE.

## Finding R5 [resolved]
**Location**: AtomicOperation threading through spliterators
**Issue**: Confirmed — both SpliteratorForward and SpliteratorBackward constructors
already accept and store AtomicOperation. No signature changes needed.

## Finding R6 [suggestion — deferred]
**Location**: Redundant tombstone filtering in IsolatedLinkBagBTreeImpl
**Issue**: After SI implementation, tombstone filtering at IsolatedLinkBagBTreeImpl
level becomes redundant but safe.
**Decision**: Keep as safety net. Do not remove in this track.

## Finding R7 [resolved — same as T2]
SharedLinkBagBTree.get() signature is not a concern — no external callers.

## Finding R8 [suggestion]
**Location**: componentId derivation via getFileId()
**Issue**: Low risk, fileId is stable in normal operation.
**Decision**: Document assumption in code comment.

## Finding R9 [should-fix]
**Location**: Concurrent iteration safety
**Issue**: Bounds must be tight per-logical-edge. Visibility checks atomic within
snapshot.
**Decision**: Address in spliterator implementation. Snapshot is immutable once
obtained, so visibility checks are inherently consistent.

## Finding R10 [suggestion]
**Location**: Performance under contention
**Issue**: Snapshot lookups per invisible entry add overhead.
**Decision**: Acceptable tradeoff. Document in code comments.

## Finding R11 [resolved]
**Location**: TransformingSpliterator
**Issue**: Entries from snapshot index use same RawPair<EdgeKey, LinkBagValue>
format as B-tree entries. Transformation is uniform — EdgeKey always has
targetCollection and targetPosition fields regardless of source.

## Finding R12 [should-fix]
**Location**: Test coverage
**Issue**: Tests must focus on visibility + snapshot fallback, not redundant
tombstone filtering.
**Decision**: Dedicated SI visibility test step covering concurrent scenarios.
