# Track 3: Concurrent and crash-recovery tests for tombstone GC

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/2 complete)
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

- [ ] Step 2: Crash-recovery integration test for tombstone GC
  > **What**: Add a test method (or small test class) that verifies tombstone
  > GC state is consistent after simulated crash and WAL recovery. Follow the
  > `IndexHistogramDurabilityTest` pattern: (1) create a DISK database,
  > (2) create vertices and edges via the database/Gremlin API such that
  > the SharedLinkBagBTree receives enough entries to trigger bucket overflows
  > and GC, (3) perform cross-tx edge deletions to create tombstones,
  > (4) insert more edges to trigger GC on the tombstones, (5) call
  > `ytdb.internal.forceDatabaseClose(dbName)` without session close,
  > (6) reopen the database, (7) verify observable behavior: surviving edges
  > are traversable, deleted edges don't reappear, no exceptions during
  > traversal.
  >
  > **Approach**: Use `YouTrackDBImpl` directly (not `DbTestBase`) for
  > explicit database lifecycle control. Create edge-heavy graph topology
  > (many edges from few vertices) to maximize SharedLinkBagBTree pressure.
  > Verification uses Gremlin traversal or SQL queries, not direct B-tree
  > inspection (B-tree internals are inaccessible after database reopen).
  >
  > **Constraints**: Must use `DatabaseType.DISK` for WAL to be active.
  > Must NOT call `session.close()` before `forceDatabaseClose()` — session
  > close triggers graceful flush, defeating crash simulation. Clean up test
  > directory in `@After`. The test verifies WAL durability of the GC'd split
  > operations, not GC logic itself (already covered by Track 1 unit tests).
