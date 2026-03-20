# Track 4: SharedLinkBagBTree read and iteration path with SI

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/4 complete)
- [ ] Track-level code review

## Base commit
`8609968ac0`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [ ] Step 1: Visibility helpers and findVisibleEntry() in SharedLinkBagBTree
  > Add the core SI visibility infrastructure to SharedLinkBagBTree:
  >
  > 1. **`isEdgeVersionVisible(long version, long currentOperationTs,
  >    AtomicOperationsSnapshot snapshot)`** — static helper matching
  >    PaginatedCollectionV2.isRecordVersionVisible() pattern: self-read
  >    shortcut (`version == currentOperationTs`) then
  >    `snapshot.isEntryVisible(version)`.
  >
  > 2. **`resolveVisibleEntry(EdgeKey bTreeKey, LinkBagValue bTreeValue,
  >    AtomicOperation atomicOp)`** — given a B-tree entry, check visibility:
  >    - If visible and not tombstone → return the entry
  >    - If visible and tombstone → return null (edge deleted)
  >    - If not visible → search snapshot index via
  >      `atomicOp.edgeSnapshotSubMapDescending(lowerKey, upperKey)` with
  >      `EdgeSnapshotKey(componentId, ridBagId, tc, tp, Long.MIN_VALUE)` to
  >      `EdgeSnapshotKey(componentId, ridBagId, tc, tp, Long.MAX_VALUE)`.
  >      Iterate descending (newest first), return first visible non-tombstone
  >      version. Return null if none found.
  >    Use `atomicOp.getCommitTsUnsafe()` for currentOperationTs.
  >
  > 3. **`findVisibleEntry(AtomicOperation atomicOp, long ridBagId,
  >    int targetCollection, long targetPosition)`** — combines
  >    findCurrentEntry() prefix lookup with resolveVisibleEntry():
  >    prefix lookup → if found, resolve visibility → return visible entry
  >    or null.
  >
  > 4. Update **IsolatedLinkBagBTreeImpl.get()** to call
  >    `bTree.findVisibleEntry()` instead of `bTree.findCurrentEntry()` +
  >    manual tombstone check.
  >
  > Tests: Unit tests in a new `SharedLinkBagBTreeVisibilityTest` class
  > covering: visible entry (fast path), invisible entry with snapshot
  > fallback, invisible tombstone in B-tree with visible live version in
  > snapshot, visible tombstone returns null, self-read shortcut, no visible
  > version returns null, entry not found returns null.

- [ ] Step 2: SpliteratorForward and SpliteratorBackward with SI visibility
  > Modify `readKeysFromBucketsForward()` and `readKeysFromBucketsBackward()`
  > in SharedLinkBagBTree to apply SI visibility checks per entry before
  > adding to the spliterator cache:
  >
  > For each entry read from a bucket page:
  > 1. Call `resolveVisibleEntry(key, value, atomicOp)` (from Step 1)
  > 2. If result is non-null → add to cache
  > 3. If result is null → skip (invisible or tombstone)
  >
  > Both forward and backward methods follow the same pattern. The
  > atomicOperation is already available (parameter of both methods).
  >
  > **Edge case**: When an invisible B-tree entry is replaced by a snapshot
  > version, the snapshot version's EdgeKey has a different ts. The cache
  > stores RawPair<EdgeKey, LinkBagValue> — the snapshot version must be
  > wrapped in an EdgeKey with the original (ridBagId, tc, tp) and the
  > snapshot version's ts. resolveVisibleEntry already returns the correct
  > pair.
  >
  > **Cache count**: The current code counts entries added to cache. Invisible
  > entries that are skipped (no visible version) should NOT count toward the
  > cache limit — only entries actually added count.
  >
  > Tests: Unit tests covering forward iteration with mixed visible/invisible
  > entries, backward iteration same, snapshot fallback during iteration,
  > tombstone skipping during iteration, cache limit behavior with invisible
  > entries.

- [ ] Step 3: IsolatedLinkBagBTreeImpl remaining read paths with SI visibility
  > Update the remaining IsolatedLinkBagBTreeImpl read-path methods to use
  > SI-aware SharedLinkBagBTree methods:
  >
  > 1. **loadEntriesMajor()** — currently uses `bTree.streamEntriesBetween()`
  >    with tombstone filter. If streamEntriesBetween() uses spliterators
  >    internally (which inherit SI from Step 2), the tombstone filter becomes
  >    redundant but harmless. Verify this path works correctly with SI.
  >
  > 2. **firstKey() / lastKey()** — currently filter tombstones but don't
  >    check visibility. With SI, these must return the first/last VISIBLE
  >    non-tombstone key. Options:
  >    a. Use the SI-aware spliterator to find the first/last visible entry
  >    b. Add visibility-aware variants to SharedLinkBagBTree
  >    Choose the simpler approach based on current implementation.
  >
  > 3. **getRealBagSize()** — counts live entries. With SI, must count only
  >    VISIBLE non-tombstone entries. If it uses spliterators (which now have
  >    SI from Step 2), this may already work. Verify and fix if needed.
  >
  > 4. **isEmpty()** — depends on firstKey(). If firstKey() is visibility-
  >    aware, isEmpty() inherits correctness.
  >
  > Keep existing tombstone filtering as safety net per R6 decision.
  >
  > Tests: Tests covering firstKey/lastKey visibility, getRealBagSize with
  > invisible entries, isEmpty with all-invisible entries. Verify
  > loadEntriesMajor returns only visible entries.

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
