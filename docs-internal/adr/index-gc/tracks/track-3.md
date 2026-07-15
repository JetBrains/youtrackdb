# Track 3: Concurrent and durability tests

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (passed after 1 iteration)

## Base commit
`205f5bd0`

## Reviews completed
- [x] Technical

## Steps

- [x] Step: Concurrent stress test for index BTree tombstone GC
  - [x] Context: unavailable
  > **What was done:** Created `BTreeTombstoneGCStressTest` with two test
  > methods following the `SharedLinkBagBTreeTombstoneGCStressTest` pattern.
  > `testConcurrentPutWithTombstonesAndGC()` — 4 threads, 300 ops/thread,
  > interleaved tombstones and live entries with non-overlapping key ranges.
  > `testConcurrentPutAndRemoveWithGC()` — pre-populate then concurrent
  > removers (TombstoneRID put) and inserters with interleaved key space.
  > Both verify: no exceptions/deadlocks, all live entries survive with
  > correct RID values, surviving tombstones retain state, tree size
  > consistency. Review fix: added missing `collectionId` assertion in
  > second test's inserter verification.
  > **Key files:** `BTreeTombstoneGCStressTest.java` (new)

- [x] Step: Durability test for index BTree tombstone GC after non-graceful close
  - [x] Context: unavailable
  > **What was done:** Created `BTreeTombstoneGCDurabilityTest` following the
  > `SharedLinkBagBTreeTombstoneGCDurabilityTest` pattern. Uses graph-level
  > API with indexed STRING property. Inserts 500 records, cross-tx deletes
  > 200 (creating index tombstones), inserts 300 more (triggering GC during
  > overflow), commits, force-closes without session close, reopens. Verifies
  > exact-match set equality between recovered and expected values, plus
  > separate ghost-resurrection check. Review fix: replaced per-value loop
  > verification with collect-and-compare pattern matching reference test,
  > removed dead `allValues` variable.
  > **What was discovered:** Core module `query()` API takes SQL directly
  > (no language prefix), `command()` returns void, `FrontendTransactionImpl`
  > doesn't have `browseClass()` — must use `tx.load(rid)` for cross-tx
  > record access. These are API quirks, no cross-track impact.
  > **Key files:** `BTreeTombstoneGCDurabilityTest.java` (new)
