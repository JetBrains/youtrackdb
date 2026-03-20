# Track 4: SharedLinkBagBTree read and iteration path with SI

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/4 complete)
- [ ] Track-level code review

## Base commit
`8609968ac0`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Visibility helpers and findVisibleEntry() in SharedLinkBagBTree
  > **What was done:** Added four methods to SharedLinkBagBTree:
  > `isEdgeVersionVisible()` (static, self-read shortcut + snapshot check),
  > `resolveVisibleEntry()` (per-entry visibility with snapshot fallback),
  > `findVisibleSnapshotEntry()` (descending snapshot scan for newest visible
  > version), and `findVisibleEntry()` (prefix lookup + visibility resolution).
  > Updated `IsolatedLinkBagBTreeImpl.get()` to use `findVisibleEntry()`.
  > `resolveVisibleEntry` left package-private for spliterator use in Step 2.
  >
  > **Key files:** `SharedLinkBagBTree.java` (modified),
  > `IsolatedLinkBagBTreeImpl.java` (modified),
  > `SharedLinkBagBTreeVisibilityTest.java` (new, 12 tests)

- [x] Step 2: SpliteratorForward and SpliteratorBackward with SI visibility
  > **What was done:** Added `resolveVisibleEntry()` calls in both
  > `readKeysFromBucketsForward()` and `readKeysFromBucketsBackward()`.
  > Invisible/tombstone entries are skipped; only visible entries count
  > toward the cache limit of 10. Null-safe: falls back to original
  > behavior when `atomicOperation` is null.
  >
  > **What was discovered:** Critical position-tracking invariant: cache
  > entries must use the ORIGINAL B-tree key (not the resolved snapshot
  > key). `fetchNextCachePortionForward/Backward` uses `lastKey` from the
  > cache to re-position via `findBucket()` after cache exhaustion. A
  > snapshot key with a lower ts would sort before the original entry,
  > causing `findBucket` to land at the same entry again — infinite loop
  > leading to OOM. Fix: `new RawPair<>(entry.getKey(), visible.second())`
  > preserves the B-tree key while substituting the resolved value.
  >
  > **Key files:** `SharedLinkBagBTree.java` (modified),
  > `SharedLinkBagBTreeVisibilityTest.java` (modified, +5 tests → 17 total)

- [x] Step 3: IsolatedLinkBagBTreeImpl remaining read paths with SI visibility
  > **What was done:** Verified that all IsolatedLinkBagBTreeImpl read-path
  > methods (`firstKey`, `lastKey`, `getRealBagSize`, `isEmpty`,
  > `loadEntriesMajor`) already work correctly with SI because they delegate
  > to `streamEntriesBetween`/`iterateEntriesMajor` which use the SI-aware
  > spliterators from Step 2. No production code changes needed — existing
  > tombstone filters kept as safety nets per R6. Added 7 tests through
  > `IsolatedLinkBagBTreeImpl` verifying each method filters invisible entries.
  >
  > **Key files:** `SharedLinkBagBTreeVisibilityTest.java` (modified,
  > +7 tests → 24 total)

- [ ] Step 4: Comprehensive multi-threaded SI read-path integration tests
  > Write integration tests that exercise the full read path with concurrent
  > transactions, validating end-to-end SI correctness:
  >
  > 1. **Snapshot isolation for get()**: Thread A opens snapshot, main thread
  >    puts/removes edges and commits, thread A reads edges — must see
  >    original state via snapshot fallback.
  >
  > 2. **Iteration consistency under SI**: Thread A opens snapshot, starts
  >    iterating edges (forward spliterator), main thread modifies edges
  >    mid-iteration — thread A sees consistent snapshot.
  >
  > 3. **Backward iteration SI**: Same as #2 but with backward spliterator.
  >
  > 4. **Multiple snapshots at different points**: Thread A snapshots at T1,
  >    main thread writes at T2, thread B snapshots at T3, main thread writes
  >    at T4 — threads A and B see different edge states corresponding to
  >    their snapshot times.
  >
  > 5. **Tombstone visibility across snapshots**: Thread A snapshots, main
  >    thread deletes edges (creates tombstones) and commits — thread A still
  >    sees deleted edges via snapshot index.
  >
  > 6. **Self-read visibility**: Within a single transaction, a write followed
  >    by a read returns the written value (not a stale snapshot version).
  >
  > Tests go in a new `SharedLinkBagBTreeSIReadPathTest` class. Use the
  > established pattern from SharedLinkBagBTreePutSITest (multiple atomic
  > operations with commit between them).
