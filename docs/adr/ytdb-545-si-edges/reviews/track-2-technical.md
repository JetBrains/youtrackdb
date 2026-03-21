# Track 2 Technical Review

## Findings

### Finding T1 [blocker]
**Location**: Track 2 description — `componentId` obtained via `DurableComponent.getId()`
**Issue**: `DurableComponent` has no `getId()` method. The plan references a non-existent API.
`SharedLinkBagBTree` instances are identified by `collectionId` — one B-tree per collection,
created via `LinkCollectionsBTreeManagerShared.createComponent(operation, collectionId)`.
**Proposed fix**: Use `collectionId` as `componentId` in edge snapshot/visibility keys. This
is the natural identifier — it uniquely identifies the `SharedLinkBagBTree` instance and
matches how `SnapshotKey.componentId` identifies `PaginatedCollectionV2` instances.

### Finding T2 [should-fix]
**Location**: Track 2 — AtomicOperation interface method signatures
**Issue**: Plan lists method names without exact signatures. Exact signatures needed:
**Proposed fix**: Specify signatures matching the existing collection pattern:
- `putEdgeSnapshotEntry(EdgeSnapshotKey key, LinkBagValue value)`
- `getEdgeSnapshotEntry(EdgeSnapshotKey key): LinkBagValue`
- `edgeSnapshotSubMapDescending(EdgeSnapshotKey from, EdgeSnapshotKey to): Iterable<Entry<EdgeSnapshotKey, LinkBagValue>>`
- `putEdgeVisibilityEntry(EdgeVisibilityKey key, EdgeSnapshotKey value)`
- `containsEdgeVisibilityEntry(EdgeVisibilityKey key): boolean`

### Finding T3 [should-fix]
**Location**: Track 2 — size tracking for edge snapshot entries
**Issue**: Current `snapshotIndexSize` only counts collection snapshots. Edge snapshot size
must be tracked separately with its own `AtomicLong`.
**Proposed fix**: Add `edgeSnapshotIndexSize: AtomicLong` in `AbstractStorage`, parallel to
`snapshotIndexSize`. Increment in `flushEdgeSnapshotBuffers()`, decrement in eviction.

### Finding T4 [should-fix]
**Location**: Track 2 — cleanup threshold in `cleanupSnapshotIndex()`
**Issue**: Threshold check at line 5462 only considers `snapshotIndexSize`. Edge snapshots
would never trigger cleanup independently.
**Proposed fix**: Check combined size: `snapshotIndexSize.get() + edgeSnapshotIndexSize.get()`.
Call both `evictStaleSnapshotEntries()` and `evictStaleEdgeSnapshotEntries()` when threshold
exceeded.

### Finding T5 [should-fix]
**Location**: Track 2 — lazy buffer allocation
**Issue**: Plan mentions lazy allocation in constraints but easy to overlook during implementation.
**Proposed fix**: Use same `@Nullable` + allocate-on-first-put pattern as existing collection buffers.

### Finding T6 [suggestion]
**Location**: Track 2 — eviction method location
**Issue**: Plan doesn't specify where `evictStaleEdgeSnapshotEntries()` lives.
**Proposed fix**: Static method in `AbstractStorage` following `evictStaleSnapshotEntries()` pattern.
No `collections` parameter needed (edge snapshots don't participate in records GC).

### Finding T7 [suggestion]
**Location**: Track 2 — component map clarity
**Issue**: Map doesn't show parallel collection vs. edge snapshot structures.
**Proposed fix**: Informational — addressed during implementation by following the parallel pattern.

### Finding T8 [should-fix]
**Location**: Track 2 — edge cleanup and records GC
**Issue**: Existing eviction feeds `PaginatedCollectionV2.deadRecordCount`. Edge snapshots have
no equivalent counter.
**Proposed fix**: Edge cleanup is standalone — no dead record counter integration. Document this
explicitly. The simpler `evictStaleEdgeSnapshotEntries` overload (without `collections` param)
is sufficient.

### Finding T9 [suggestion]
**Location**: Track 2 — `MergingDescendingIterator` type compatibility
**Issue**: Current class is typed to `SnapshotKey, PositionEntry` — not generic. Edge snapshots
need `EdgeSnapshotKey, LinkBagValue`.
**Proposed fix**: Either make it generic or create a parallel `EdgeMergingDescendingIterator`.
Generic approach is cleaner but requires touching the existing class.

## Summary

- **Blockers**: T1 (componentId = collectionId)
- **Should-fix**: T2, T3, T4, T5, T8
- **Suggestions**: T6, T7, T9
