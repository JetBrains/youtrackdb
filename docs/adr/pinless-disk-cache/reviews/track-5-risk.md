# Track 5 Risk Review

## Iteration 1 — PASS (8 findings: 0 blocker after triage, 3 should-fix, 3 suggestion, 2 rejected)

### Finding R1 [suggestion] (downgraded from blocker)
**Location**: B-tree traversal in findBucketSerialized
**Issue**: Mid-traversal validation needed to limit wasted work on stale pointers. Risk of following incorrect pointer if page evicted between reading child index and loading child page.
**Mitigation**: Plan already shows validateLastOrThrow() per level. The migration step will refactor findBucketSerialized to include per-level validation. Stamp validation catches frame reuse (different coordinates) or eviction. Downgraded because the plan already addresses this.

### Finding R2 [suggestion]
**Location**: BTree.firstKey/lastKey, SharedLinkBagBTree.firstItem/lastItem
**Issue**: Deep tree traversals accumulate many frames in scope. firstItem/lastItem in SharedLinkBagBTree maintain a path LinkedList with O(depth) pages.
**Mitigation**: Evaluate during implementation. If scope grows >20 frames for typical trees, keep these on pinned path. firstKey/lastKey are single-path traversals (no branching), so scope growth is bounded by tree depth (typically 3-5).

### Finding R3 [rejected]
**Location**: Multi-page record detection in PaginatedCollectionV2
**Issue**: Loading first page to check nextPagePointer wastes one page load for multi-page records.
**Rejection rationale**: One page load is negligible overhead. The position map lookup is needed regardless.

### Finding R4 [should-fix] (downgraded from blocker)
**Location**: DurablePage subclass constructors
**Issue**: Plan listed only 6 subclasses but actual audit found 8. The plan's reference to "CollectionPositionMapV2.PositionMapPage" is wrong — the actual class is CollectionPositionMapBucket.
**Mitigation**: Codebase audit completed: exactly 8 subclasses need PageView constructors (see technical review T2). Addressed as dedicated Step 2.

### Finding R5 [should-fix] (downgraded from blocker)
**Location**: loadPageOptimistic and WAL changes
**Issue**: Without hasChangesForPage check, optimistic reads on pages with local WAL changes return committed (stale) data. Stamp validation does NOT catch this because the frame wasn't evicted.
**Mitigation**: Include hasChangesForPage guard as Step 1 of Track 5 (subsumes Track 6). Small change, must be done before any component migrations.
**Note**: The risk reviewer's claim that Track 5 is "not ready to implement" is overstated — the fix is a 10-line addition that becomes Step 1.

### Finding R6 [suggestion]
**Location**: BTree.size()
**Issue**: Implementation unclear in plan.
**Resolution**: Verified — size() reads a cached field from CellBTreeSingleValueEntryPointV3.getTreeSize(). Single page load, trivial to migrate.

### Finding R7 [should-fix]
**Location**: Execution strategy for DurablePage subclass constructors
**Issue**: Need coordinated addition of PageView constructors before functional migrations begin.
**Mitigation**: Addressed as dedicated Step 2.

### Finding R8 [rejected]
**Location**: FreeSpaceMap two-level retry rate
**Issue**: Concern about high failure rate on second-level page under cache pressure.
**Rejection rationale**: FreeSpaceMap lookups are called during write operations (finding space for new records), not on the read hot path. The optimistic migration here is opportunistic. If retry rate is high, the fallback path works correctly. No special measurement needed.
