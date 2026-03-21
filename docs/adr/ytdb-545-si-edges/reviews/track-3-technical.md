# Track 3 Technical Review

## Finding T1 [should-fix]
**Location**: SharedLinkBagBTree / snapshot key construction
**Issue**: SharedLinkBagBTree has no explicit `getId()` for componentId in
EdgeSnapshotKey. The plan says "componentId identifies the SharedLinkBagBTree
durable component instance" but doesn't specify how to obtain it.
**Proposed fix**: Use `getFileId()` (disk cache file ID) — same concept as
`PaginatedCollectionV2.getId()`. No new field needed.
**Resolution**: Addressed in step decomposition — Step 2 uses `getFileId()`.

## Finding T2 [should-fix]
**Location**: IsolatedLinkBagBTreeImpl.put()/remove() (lines 66, 108)
**Issue**: commitTs is hardcoded as 0L with TODO comments from Track 1.
Must be replaced with `atomicOperation.getCommitTs()`.
**Proposed fix**: Wire commitTs in IsolatedLinkBagBTreeImpl write path.
**Resolution**: Addressed in Step 4 of decomposition.

## Finding T3 [should-fix]
**Location**: SharedLinkBagBTree / prefix lookup helper
**Issue**: findCurrentEntry() specification incomplete — return type, contract,
integration with existing bucket-finding logic unspecified.
**Proposed fix**: Return `RawPair<EdgeKey, LinkBagValue>` or null. Use
`EdgeKey(ridBagId, tc, tp, Long.MIN_VALUE)` as search key, check insertion
point for matching prefix.
**Resolution**: Addressed in Step 1 of decomposition.

## Finding T4 [should-fix]
**Location**: SharedLinkBagBTree.put() same-transaction overwrite
**Issue**: Interaction between ts-based skip and existing size-based
optimization unclear.
**Proposed fix**: Version check first (skip snapshot if oldTs == newTs),
then apply size-based optimization independently.
**Resolution**: Addressed in Step 2 of decomposition.
