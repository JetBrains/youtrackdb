# Track 5 Technical Review

## Iteration 1 — PASS (5 findings: 1 blocker, 2 should-fix, 2 suggestion)

### Finding T1 [should-fix]
**Location**: Track 5 scope (PaginatedCollectionV2 migration)
**Issue**: The notation "single-page" for readRecord is misleading. `internalReadRecord()` handles multi-page records via a do-while loop following nextPagePointer chains. The plan correctly excludes multi-page from optimistic path but doesn't clarify the boundary: the position map lookup and first-page access are optimistic, multi-page chaining falls back.
**Proposed fix**: In step decomposition, clarify: migrate CollectionPositionMapV2.get/getWithStatus and single-page readRecord to optimistic; multi-page records throw OptimisticReadFailedException and use pinned fallback.

### Finding T2 [blocker]
**Location**: DurablePage subclass constructors
**Issue**: Eight DurablePage subclasses used in read paths need PageView constructors:
1. CellBTreeSingleValueBucketV3
2. CellBTreeSingleValueV3NullBucket
3. CellBTreeSingleValueEntryPointV3
4. CollectionPage
5. MapEntryPoint (CollectionPositionMapV2, NOT "PositionMapPage" as plan states)
6. CollectionPositionMapBucket
7. FreeSpaceMapPage
8. Bucket (SharedLinkBagBTree)
These constructors must exist before any optimistic read migration.
**Proposed fix**: Add PageView constructors as a dedicated step before component migrations.

### Finding T3 [should-fix]
**Location**: BTree migration scope — plan references "BTreeCursorV3"
**Issue**: BTreeCursorV3 does not exist. BTree uses SpliteratorForward/SpliteratorBackward for stream-based iteration (allEntries, keyStream, iterateEntriesMajor/Minor/Between). These maintain cross-call state and are complex to migrate.
**Proposed fix**: Keep Spliterator/Stream iteration on pinned path. Focus optimistic migration on single-key lookup (get), tree traversal (findBucketSerialized), firstKey/lastKey, and size.

### Finding T4 [suggestion]
**Location**: CollectionPositionMapV2 — get() vs getWithStatus()
**Issue**: PaginatedCollectionV2.doReadRecord() calls getWithStatus(), not get(). Both have identical structure. get() is less frequently used.
**Proposed fix**: Migrate both together in the same step since they share the same pattern.

### Finding T5 [suggestion]
**Location**: Track 5/Track 6 dependency for WAL changes guard
**Issue**: loadPageOptimistic() does not check for local WAL changes in the current AtomicOperation. Pages with WAL changes would return raw buffer data (committed state) without the overlay, which is a correctness issue for read-write transactions. Track 6 adds this guard but is listed as a separate track.
**Proposed fix**: Include the hasChangesForPage guard as Step 1 of Track 5 (subsumes Track 6). This is a small change (~10 lines across 3 files) and must be in place before any optimistic reads are used.
