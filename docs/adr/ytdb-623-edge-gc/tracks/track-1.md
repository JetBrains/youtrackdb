# Track 1: Tombstone filtering during leaf page split

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
- [ ] Track-level code review

## Base commit
`6838a708f9`

## Reviews completed
- [x] Technical (track-1-technical.md — 2 blockers resolved, 4 should-fix, 2 suggestions)
- [x] Risk (track-1-risk.md — 1 blocker duplicate of T1, 5 should-fix, 2 suggestions)

## Steps

- [x] Step 1: Add tombstone GC helper methods to SharedLinkBagBTree
  > **What was done:** Added three private helper methods:
  > `hasEdgeSnapshotEntries()` (lazy iterator check on edge snapshot index),
  > `isRemovableTombstone()` (3-condition check: tombstone flag, ts < LWM
  > strict, no snapshot entries), and `filterAndRebuildBucket()` (iterate
  > bucket, collect survivors, shrink(0)+addAll rebuild). Added defensive
  > asserts: fileId assigned, bucket is leaf, partition invariant
  > (removed + survivors == original size), post-rebuild size check.
  >
  > **What changed from the plan:** Steps 1-3 were implemented as a single
  > commit because the helpers (Step 1) cannot be tested independently —
  > they are private methods and the GC is only triggered through the
  > put()/remove() integration (Steps 2-3). All three steps share one
  > implementation commit and one test class.
  >
  > **Key files:** `SharedLinkBagBTree.java` (modified),
  > `SharedLinkBagBTreeTombstoneGCTest.java` (new)

- [x] Step 2: Integrate GC into `put()` with filter-rebuild-retry
  > **What was done:** Added gcAttempted flag and GC-before-split block
  > in put()'s while loop. Computes LWM once (with positivity assert),
  > calls filterAndRebuildBucket, updates tree size, re-derives insertion
  > index via find() with runtime StorageException guard (not just assert),
  > then retries via continue. Documented 4 tree size accounting cases.
  >
  > **What was discovered:** The public iteration API (iterateEntriesMajor)
  > filters tombstones via SI visibility — tests cannot use iteration to
  > count tombstones. findCurrentEntry() returns raw B-tree entries and
  > is the correct API for test verification. Also, tombstones inserted
  > with low ts values get GC'd even during initial insertion (not just
  > when live entries are added), because bucket overflows during tombstone
  > insertion trigger GC on earlier tombstones in the same bucket.
  >
  > **Key files:** `SharedLinkBagBTree.java` (modified),
  > `SharedLinkBagBTreeTombstoneGCTest.java` (new — 10 tests)

- [x] Step 3: Integrate GC into `remove()` cross-tx tombstone insertion path
  > **What was done:** Applied same filter-rebuild-retry pattern to
  > remove()'s cross-tx tombstone insertion while loop. Same guards:
  > LWM positivity assert, runtime StorageException for key-exists check.
  > Added test for GC during cross-tx remove path.
  >
  > **Key files:** `SharedLinkBagBTree.java` (modified)
