# Track 3 Risk Review

## Finding R1 [should-fix]
**Location**: SharedLinkBagBTree write path / prefix lookup
**Issue**: Current put()/remove() use exact key match; prefix-based lookup
needed to find entries regardless of ts. Blast radius: high if missing.
**Proposed fix**: Implement findCurrentEntry() helper in Step 1.
**Resolution**: Step 1 of decomposition.

## Finding R2 [should-fix]
**Location**: SharedLinkBagBTree.put() / snapshot preservation
**Issue**: No snapshot preservation logic exists yet. Must add
putEdgeSnapshotEntry + putEdgeVisibilityEntry calls.
**Proposed fix**: Implement in put() after prefix lookup finds old entry.
**Resolution**: Step 2 of decomposition.

## Finding R3 [should-fix]
**Location**: IsolatedLinkBagBTreeImpl / commitTs wiring
**Issue**: commitTs hardcoded as 0L. Must use atomicOperation.getCommitTs().
**Proposed fix**: Wire in Step 4.
**Resolution**: Step 4 of decomposition.

## Finding R4 [should-fix]
**Location**: SharedLinkBagBTree.remove() / tombstone creation
**Issue**: Current remove() physically deletes; must insert tombstone instead.
**Proposed fix**: Implement tombstone creation in Step 3.
**Resolution**: Step 3 of decomposition.

## Finding R5 [should-fix]
**Location**: remove() return semantics
**Issue**: Should return old value (before deletion), not tombstone.
**Proposed fix**: Return deserialized old value before inserting tombstone.
**Resolution**: Step 3 of decomposition.

## Finding R6 [should-fix]
**Location**: IsolatedLinkBagBTreeImpl.clear()/delete() with tombstones
**Issue**: After SI, clear() will create tombstones instead of physical delete.
**Proposed fix**: This is correct for SI — concurrent readers need tombstones
to know edges were deleted. clear() already delegates to remove() which will
create tombstones. Tombstone GC is deferred to follow-up PR (per plan).
**Resolution**: No special handling needed — clear() naturally uses SI-aware
remove().

## Finding R7 [should-fix]
**Location**: EdgeSnapshotKey/EdgeVisibilityKey construction
**Issue**: componentId and version field meanings need clarity.
**Proposed fix**: componentId = getFileId(), version = oldEdgeKey.ts().
**Resolution**: Documented in step descriptions.

## Finding R8 [should-fix]
**Location**: Cleanup wiring verification
**Issue**: Need to verify Track 2 wired evictStaleEdgeSnapshotEntries().
**Proposed fix**: Verify during implementation. Track 2 episode confirms
cleanup is wired into cleanupSnapshotIndex().
**Resolution**: Verified by Track 2 episode.

## Finding R9 [suggestion]
**Location**: Testing strategy
**Issue**: Concurrent write test cases not specified.
**Proposed fix**: Add to Step 4 integration tests.
**Resolution**: Step 4 includes multi-write scenarios.

## Finding R10 [should-fix]
**Location**: Size tracking with tombstones
**Issue**: B-tree size semantics ambiguous when tombstones are present.
**Proposed fix**: Size counts entries (live + tombstones) in the B-tree.
getRealBagSize() filtering is a read-path concern (Track 4). remove()
with tombstone does net-zero size change (remove old + insert tombstone).
**Resolution**: Addressed in Step 3 — size unchanged when replacing with
tombstone.

## Finding R11 [should-fix]
**Location**: Same-transaction overwrite detection
**Issue**: Must compare oldTs with commitTs to decide snapshot preservation.
**Proposed fix**: If oldTs == commitTs, update in place. If oldTs < commitTs,
preserve old entry.
**Resolution**: Step 2 of decomposition.
