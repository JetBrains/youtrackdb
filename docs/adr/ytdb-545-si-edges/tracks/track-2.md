# Track 2: Snapshot index infrastructure for link bag entries

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/5 complete)
- [ ] Track-level code review

## Base commit
`e32dc56608`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Create EdgeSnapshotKey and EdgeVisibilityKey record types with tests
  > **What was done:** Created `EdgeSnapshotKey` and `EdgeVisibilityKey` Java records
  > in the `ridbagbtree` package following the exact pattern of the existing
  > `SnapshotKey`/`VisibilityKey` in the `collection` package. EdgeSnapshotKey has
  > 5-field natural ordering (componentId → ridBagId → targetCollection →
  > targetPosition → version). EdgeVisibilityKey has 5-field ordering with recordTs
  > first for efficient headMap eviction. Added comprehensive tests (23 total):
  > per-field ordering, equality/hashCode, subMap/headMap range scans, component
  > isolation, ridBagId isolation, and descending scan for newest-version lookups.
  > 100% line and branch coverage on both new types.
  >
  > **Key files:** `EdgeSnapshotKey.java` (new), `EdgeVisibilityKey.java` (new),
  > `EdgeSnapshotKeyTest.java` (new), `EdgeVisibilityKeyTest.java` (new)

- [x] Step 2: Add edge snapshot maps and size counter to AbstractStorage, with eviction method
  > **What was done:** Added 3 new fields to AbstractStorage (sharedEdgeSnapshotIndex,
  > edgeVisibilityIndex, edgeSnapshotIndexSize) parallel to existing collection snapshot
  > fields. Added static `evictStaleEdgeSnapshotEntries()` method following the same
  > headMap sentinel pattern. Modified `cleanupSnapshotIndex()` to check combined size
  > of both collection and edge indexes. Cleared edge maps in both normal close and
  > delete paths. Added getter methods. Code review found missing size counter resets
  > in the delete path — fixed both the new `edgeSnapshotIndexSize` and the pre-existing
  > missing `snapshotIndexSize.set(0)`.
  >
  > **Key files:** `AbstractStorage.java` (modified), `EdgeSnapshotIndexCleanupTest.java`
  > (new — 11 tests covering eviction, boundary conditions, tombstones, idempotence)

- [x] Step 3: Extend AtomicOperation interface and AtomicOperationBinaryTracking with edge snapshot methods
  > **What was done:** Added 5 edge snapshot methods to `AtomicOperation` interface and
  > implemented them in `AtomicOperationBinaryTracking` with lazily-allocated local overlay
  > buffers (TreeMap for snapshot, HashMap for visibility). Made `MergingDescendingIterator`
  > generic (`<K extends Comparable<K>, V>`) — clean conversion, no churn in existing code
  > since only the class declaration and field types changed. Added `flushEdgeSnapshotBuffers()`
  > and wired it into `commitChanges()` right after `flushSnapshotBuffers()`. Updated
  > `AtomicOperationsManager.startAtomicOperation()` to pass the 3 new AbstractStorage fields.
  > Updated `AtomicOperationSnapshotProxyTest` factory to compile with new constructor.
  >
  > **What changed from the plan:** Step 4 originally included wiring the flush into
  > `commitChanges()`, but this was done here since it was a natural part of the implementation.
  > Step 4 is now focused on tests only.
  >
  > **Key files:** `AtomicOperation.java` (modified), `AtomicOperationBinaryTracking.java`
  > (modified), `AtomicOperationsManager.java` (modified),
  > `AtomicOperationSnapshotProxyTest.java` (modified)

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
