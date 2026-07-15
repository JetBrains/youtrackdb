# Track 2: Add `getVisible()` to BTree and CellBTreeSingleValue interface

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (1/3 iterations — all should-fix findings resolved)

## Base commit
`32d690c19cfae5b55d7bf4ca3fb8ef55574d4e4e`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step: Add `getVisible()` to `CellBTreeSingleValue<K>` interface and implement in `BTree<K>`
  - [x] Context: unavailable
  > **What was done:** Added `getVisible()` to `CellBTreeSingleValue<K>` interface and
  > implemented in `BTree<K>` with optimistic+pinned paths following the existing `get()`
  > pattern. Key design adaptation from plan: instead of serializing a raw prefix key
  > (which fails because `IndexMultiValuKeySerializer` requires the full `keyTypes` element
  > count), the search key is padded with `Long.MIN_VALUE` as the version component via
  > `buildSearchKey()`. This places the search before all real versioned entries, so
  > `bucket.find()` returns the insertion point at the first matching entry — eliminating
  > the need for a leftward scan. Added `userKeyPrefixMatches()` (static, pre-extracted
  > key list) for forward prefix-match checking. 11 unit tests cover: committed entry,
  > tombstone, key not found, multiple versions (first in scan order), null key, empty
  > tree, cache-warm path, tombstone+live entry, adjacent-key prefix boundary, empty
  > string key, and multiple distinct keys.
  >
  > **What was discovered:** `IndexMultiValuKeySerializer.serialize()` iterates up to
  > `types.length` elements, not `keys.size()`, so a prefix CompositeKey with fewer
  > elements causes `IndexOutOfBoundsException`. This required the `buildSearchKey()`
  > approach (pad with `Long.MIN_VALUE`) instead of the raw prefix key described in D3.
  > The functional result is the same — `bucket.find()` locates the right position —
  > but there's one extra `CompositeKey` allocation for the padded search key.
  >
  > **What changed from the plan:** No leftward scan needed (the `Long.MIN_VALUE` padding
  > ensures we start at or before the first version entry). This simplifies the scan logic.
  >
  > **Key files:** `CellBTreeSingleValue.java` (modified), `BTree.java` (modified),
  > `BTreeGetVisibleTest.java` (new)

- [x] Step: Add cross-page and equivalence tests for `getVisible()`
  - [x] Context: unavailable
  > **What was done:** Added 12 tests (total now 23): cross-page (300 entries forcing
  > page splits), 50-tombstones-then-live (forward scan stress), all-tombstones returns
  > null, SnapshotMarkerRID returns identity, 6 equivalence tests comparing getVisible()
  > vs iterateEntriesBetween + visibilityFilter (committed, tombstone, multi-version,
  > key not found, snapshot marker, null key) with independent expected-value assertions,
  > key-after-all-entries and key-before-all-entries boundary tests.
  >
  > **What was discovered:** In-progress transaction and phantom detection tests cannot
  > be implemented through the real BTree infrastructure because the AtomicOperationsSnapshot
  > is derived from the real transaction counter (cannot control visibleVersion). These
  > visibility scenarios are already thoroughly tested in IndexesSnapshotVisibilityFilterTest
  > for checkVisibility(), which getVisible() delegates to.
  >
  > **Key files:** `BTreeGetVisibleTest.java` (modified — 12 new test methods)
