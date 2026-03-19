# Track 2: Snapshot index infrastructure for link bag entries

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/5 complete)
- [ ] Track-level code review

## Base commit
_(to be set at Phase B start)_

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [ ] Step 1: Create EdgeSnapshotKey and EdgeVisibilityKey record types with tests
  > Create `EdgeSnapshotKey` record in `core/.../storage/ridbag/ridbagbtree/` package:
  > `(int componentId, long ridBagId, int targetCollection, long targetPosition, long version)`
  > implementing `Comparable<EdgeSnapshotKey>` with natural ordering
  > `componentId → ridBagId → targetCollection → targetPosition → version`.
  > `componentId` is the `collectionId` that identifies the `SharedLinkBagBTree` instance
  > (resolves T1 — `DurableComponent.getId()` does not exist; `collectionId` is the natural
  > identifier).
  >
  > Create `EdgeVisibilityKey` record in the same package:
  > `(long recordTs, int componentId, long ridBagId, int targetCollection, long targetPosition)`
  > implementing `Comparable<EdgeVisibilityKey>` with natural ordering
  > `recordTs → componentId → ridBagId → targetCollection → targetPosition`.
  > `recordTs` must be first to enable efficient `headMap(lwm)` range-scan during cleanup.
  >
  > Add tests modeled after `SnapshotKeyTest` and `VisibilityKeyTest`: ordering by each field,
  > equal keys compare to zero, subMap range scan isolation (component isolation, position
  > isolation, version range scan), and headMap for visibility cleanup sentinel pattern.
  >
  > **Files**: `EdgeSnapshotKey.java` (new), `EdgeVisibilityKey.java` (new),
  > `EdgeSnapshotKeyTest.java` (new), `EdgeVisibilityKeyTest.java` (new)

- [ ] Step 2: Add edge snapshot maps and size counter to AbstractStorage, with eviction method
  > Add three new fields to `AbstractStorage` (parallel to existing collection snapshot fields
  > at lines 280-292):
  > - `ConcurrentSkipListMap<EdgeSnapshotKey, LinkBagValue> sharedEdgeSnapshotIndex`
  > - `ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey> edgeVisibilityIndex`
  > - `AtomicLong edgeSnapshotIndexSize`
  > Initialize inline as final fields (same pattern as collection maps).
  >
  > Add static `evictStaleEdgeSnapshotEntries(long lwm, ConcurrentSkipListMap<EdgeSnapshotKey,
  > LinkBagValue>, ConcurrentSkipListMap<EdgeVisibilityKey, EdgeSnapshotKey>, AtomicLong)`
  > method following the `evictStaleSnapshotEntries` pattern (no `collections` parameter —
  > edge cleanup does not participate in records GC dead record counting; resolves T8).
  > Use sentinel key `new EdgeVisibilityKey(lwm, Integer.MIN_VALUE, Long.MIN_VALUE,
  > Integer.MIN_VALUE, Long.MIN_VALUE)` for `headMap` range scan.
  >
  > Modify `cleanupSnapshotIndex()` to check combined size:
  > `snapshotIndexSize.get() + edgeSnapshotIndexSize.get() > threshold` (resolves T4).
  > Call `evictStaleEdgeSnapshotEntries()` after collection eviction.
  >
  > Clear edge maps in `clearSnapshotMaps()` (called during storage close/clear).
  > Add getter methods for the 3 new fields (matching existing getter pattern).
  >
  > Add tests in `SnapshotIndexCleanupTest` (or new `EdgeSnapshotIndexCleanupTest`): verify
  > edge entries are evicted when LWM advances, verify combined threshold triggers cleanup,
  > verify edge and collection cleanup are independent (evicting one doesn't affect the other).
  >
  > **Files**: `AbstractStorage.java` (modified), `SnapshotIndexCleanupTest.java` or
  > `EdgeSnapshotIndexCleanupTest.java` (new/modified)

- [ ] Step 3: Extend AtomicOperation interface and AtomicOperationBinaryTracking with edge snapshot methods
  > Add 5 new methods to `AtomicOperation` interface (resolves T2):
  > - `putEdgeSnapshotEntry(EdgeSnapshotKey key, LinkBagValue value)`
  > - `getEdgeSnapshotEntry(EdgeSnapshotKey key): LinkBagValue`
  > - `edgeSnapshotSubMapDescending(EdgeSnapshotKey from, EdgeSnapshotKey to):
  >    Iterable<Map.Entry<EdgeSnapshotKey, LinkBagValue>>`
  > - `putEdgeVisibilityEntry(EdgeVisibilityKey key, EdgeSnapshotKey value)`
  > - `containsEdgeVisibilityEntry(EdgeVisibilityKey key): boolean`
  >
  > Implement in `AtomicOperationBinaryTracking`:
  > - Add 3 constructor parameters: `sharedEdgeSnapshotIndex`, `sharedEdgeVisibilityIndex`,
  >   `edgeSnapshotIndexSize` (all `@Nonnull`) — resolves R4.
  > - Add lazily-allocated local overlay buffers (resolves T5):
  >   `@Nullable TreeMap<EdgeSnapshotKey, LinkBagValue> localEdgeSnapshotBuffer`
  >   `@Nullable HashMap<EdgeVisibilityKey, EdgeSnapshotKey> localEdgeVisibilityBuffer`
  > - Implement all 5 methods following exact same pattern as collection snapshot methods
  >   (lines 698-771).
  > - For `edgeSnapshotSubMapDescending`, the `MergingDescendingIterator` is not generic
  >   (typed to `SnapshotKey, PositionEntry`). Either make it generic:
  >   `MergingDescendingIterator<K extends Comparable<K>, V>` or create a parallel
  >   `EdgeMergingDescendingIterator` — resolves T9. Generic approach preferred if it
  >   doesn't create excessive churn in existing code.
  > - Add `flushEdgeSnapshotBuffers()` mirroring `flushSnapshotBuffers()` (lines 773-785).
  >
  > Update `AtomicOperationsManager.startOperation()` to pass the 3 new `AbstractStorage`
  > fields to the `AtomicOperationBinaryTracking` constructor. Update `storage.getXxx()` calls.
  >
  > **Files**: `AtomicOperation.java` (modified), `AtomicOperationBinaryTracking.java` (modified),
  > `AtomicOperationsManager.java` (modified)

- [ ] Step 4: Wire edge snapshot buffer flush into commit path
  > In `AtomicOperationBinaryTracking.commitChanges()`, call `flushEdgeSnapshotBuffers()`
  > immediately after `flushSnapshotBuffers()` (line 576), inside the `if (!rollback)` block.
  > This ensures edge snapshot entries are visible in shared maps before page changes are
  > applied to cache (resolves R3).
  >
  > On rollback, local edge buffers are discarded — the write-nothing-on-error pattern applies
  > identically.
  >
  > Add tests in `AtomicOperationSnapshotProxyTest` (or new test class):
  > - `putEdgeSnapshotEntry` / `getEdgeSnapshotEntry` local buffer behavior
  > - `getEdgeSnapshotEntry` falls back to shared map
  > - Local entry shadows shared entry with same key
  > - `edgeSnapshotSubMapDescending` merges local and shared in descending order
  > - `putEdgeVisibilityEntry` / `containsEdgeVisibilityEntry` local and shared behavior
  > - `flushEdgeSnapshotBuffers` moves local entries to shared maps and increments size counter
  > - Full commit path test: put entries, commit, verify entries in shared maps
  > - Rollback discards local edge buffers (entries not in shared maps after rollback)
  >
  > Update the test factory `createOperation()` in `AtomicOperationSnapshotProxyTest` to pass
  > the 3 new constructor parameters.
  >
  > **Files**: `AtomicOperationBinaryTracking.java` (modified),
  > `AtomicOperationSnapshotProxyTest.java` (modified)

- [ ] Step 5: Integration test for edge snapshot lifecycle (put → flush → cleanup)
  > Write a focused integration test that exercises the full lifecycle:
  > 1. Create edge snapshot and visibility entries via `AtomicOperation`
  > 2. Commit the operation (flush to shared maps)
  > 3. Verify entries are in shared maps with correct values
  > 4. Advance LWM past the entries' `recordTs`
  > 5. Call `evictStaleEdgeSnapshotEntries()` and verify entries are removed
  > 6. Verify size counter decremented correctly
  >
  > Also test:
  > - Mixed scenario: both collection and edge snapshot entries in the same transaction
  > - Edge entries survive cleanup when LWM is below their `recordTs`
  > - Multiple edge entries for different components/ridBagIds cleaned independently
  >
  > **Files**: `EdgeSnapshotLifecycleTest.java` (new)
