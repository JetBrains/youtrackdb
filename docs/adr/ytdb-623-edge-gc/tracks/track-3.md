# Track 3: Concurrent and crash-recovery tests for tombstone GC

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/2 complete)
- [ ] Track-level code review

## Base commit
_(to be filled at Phase B start)_

## Reviews completed
- [x] Technical (track-3-technical.md — 1 blocker resolved, 2 should-fix resolved, 2 suggestions accepted)

## Steps

- [ ] Step 1: Contention stress test for tombstone GC under concurrent put/remove
  > **What**: Add a test class `SharedLinkBagBTreeTombstoneGCStressTest` in
  > `core/src/test/java/.../storage/index/edgebtree/btree/`. Multiple threads
  > concurrently perform `put()` and `remove()` operations on the same
  > `SharedLinkBagBTree` instance, each in separate atomic operations. Some
  > threads insert live entries, others insert tombstones (via `put()` with
  > `tombstone=true`). The interleaved operations cause bucket overflows that
  > trigger GC non-deterministically. After all threads finish, verify:
  > (1) no deadlocks or exceptions during execution, (2) tree invariants hold
  > (all entries findable via `findCurrentEntry()`, no duplicates), (3) live
  > entries are never lost.
  >
  > **Approach**: Use `ExecutorService` with a fixed thread pool. Each worker
  > runs a loop of put/remove operations within `executeInsideAtomicOperation`.
  > Operations serialize at the B-tree level (component exclusive lock), so
  > this tests contention safety, not true concurrency within the GC logic.
  > The test uses the same static storage/atomicOperationsManager setup as
  > `SharedLinkBagBTreeTombstoneGCTest`.
  >
  > **Constraints**: Must not run alongside other tests in the same JVM
  > (standard project constraint). Threads share the B-tree instance but
  > each atomic operation is independent. Use assertion-based verification
  > after thread completion, not during (to avoid lock contention in
  > verification). Timeout the executor to detect deadlocks.

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
