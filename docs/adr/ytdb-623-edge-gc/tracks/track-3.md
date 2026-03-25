# Track 3: Concurrent and crash-recovery tests for tombstone GC

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [ ] Track-level code review

## Base commit
`347b4bef05`

## Reviews completed
- [x] Technical (track-3-technical.md — 1 blocker resolved, 2 should-fix resolved, 2 suggestions accepted)

## Steps

- [x] Step 1: Contention stress test for tombstone GC under concurrent put/remove
  > **What was done:** Added `SharedLinkBagBTreeTombstoneGCStressTest` with two
  > tests: (1) `testConcurrentPutWithTombstonesAndGC` — 4 threads concurrently
  > insert interleaved tombstones and live entries, (2) `testConcurrentPutAndRemoveWithGC`
  > — pre-populate + concurrent cross-tx removes and inserts. Both use shared
  > `ridBagId=1` with non-overlapping position ranges so entries co-locate in
  > the same B-tree buckets, enabling cross-thread GC interaction.
  >
  > **What was discovered:** Initial design used per-thread `ridBagId`, but
  > dimensional review (6 of 10 agents) flagged that `EdgeKey.compareTo()` sorts
  > by `ridBagId` first, so per-thread IDs prevent cross-thread bucket co-location.
  > Switched to shared `ridBagId=1` with non-overlapping position ranges.
  >
  > **Key files:** `core/src/test/java/.../storage/index/edgebtree/btree/SharedLinkBagBTreeTombstoneGCStressTest.java` (new)

- [x] Step 2: Durability test for tombstone GC after non-graceful close
  > **What was done:** Added `SharedLinkBagBTreeTombstoneGCDurabilityTest` with
  > one test: `forceClose_afterTombstoneGC_preservesEdges`. Creates 600 edges,
  > deletes 200 (creating tombstones), inserts 300 more (triggering GC), then
  > force-closes without session close. Reopens and verifies exact edge set
  > using `containsExactlyInAnyOrderElementsOf` + ghost resurrection check.
  > Uses forced BTree link bag threshold (-1) for full SharedLinkBagBTree
  > coverage.
  >
  > **What was discovered:** Dimensional review revealed that `forceDatabaseClose`
  > calls `flushAllData()` before closing — it's not a true crash simulation.
  > Test was relabeled from "crash" to "forceClose" with Javadoc documenting
  > the limitation. The test still provides value as a non-graceful close
  > durability test (same pattern as `IndexHistogramDurabilityTest`).
  >
  > **Key files:** `core/src/test/java/.../storage/index/edgebtree/btree/SharedLinkBagBTreeTombstoneGCDurabilityTest.java` (new)
