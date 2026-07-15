# Track 4 Technical Review

## Finding T1 [resolved]
**Location**: Track 4 scope vs. Track 3 Step 4
**Issue**: Track 3 Step 4 already implemented tombstone filtering in
IsolatedLinkBagBTreeImpl read-path methods. This was originally Track 4 scope.
**Resolution**: Addressed in strategy refresh. Track 4 scope reduced accordingly.

## Finding T2 [should-fix]
**Location**: SharedLinkBagBTree.get() (line 205-227)
**Issue**: No external callers exist — IsolatedLinkBagBTreeImpl.get() uses
findCurrentEntry() instead. The plan describes SI for get(), but the method is
unused externally.
**Decision**: Add a new `findVisibleEntry()` method to SharedLinkBagBTree that
encapsulates prefix lookup + visibility check + snapshot fallback. Leave the
existing get() as internal exact-key lookup (no SI). Update
IsolatedLinkBagBTreeImpl.get() to use findVisibleEntry().

## Finding T3 [should-fix]
**Location**: Visibility check implementation pattern
**Issue**: Self-read shortcut must use `getCommitTsUnsafe()` (not `getCommitTs()`)
matching PaginatedCollectionV2 line 1151 pattern.
**Decision**: Follow PaginatedCollectionV2.isRecordVersionVisible() pattern exactly.

## Finding T4 [should-fix]
**Location**: readKeysFromBucketsForward (line 1231-1288) and
readKeysFromBucketsBackward (line 1361-1414)
**Issue**: No visibility filtering — raw entries added to cache.
**Decision**: Add SI visibility check per entry with snapshot index fallback.

## Finding T5 [suggestion]
**Location**: Snapshot index range key construction
**Issue**: Bounds for edgeSnapshotSubMapDescending lookups need precise construction.
**Decision**: Use EdgeSnapshotKey(componentId, ridBagId, tc, tp, Long.MIN_VALUE)
as lower and EdgeSnapshotKey(..., Long.MAX_VALUE) as upper.

## Finding T6 [suggestion]
**Location**: Spliterator SI helper method
**Issue**: Helper method recommended to encapsulate visibility + snapshot fallback.
**Decision**: Add `resolveVisibleEntry()` helper in SharedLinkBagBTree reusable by
both get and spliterator paths.
