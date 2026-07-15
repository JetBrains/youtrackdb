# Track 3 Technical Review

## Review scope
Track 3: Concurrent and crash-recovery tests for tombstone GC

## Findings

### Finding T1 [blocker]
**Location**: Track 3, "Concurrent GC tests (TX1/TX2)" — plan describes
"Multiple threads performing concurrent `put()` and `remove()` operations on
the same `SharedLinkBagBTree` while tombstone GC is active"
**Issue**: This scenario is architecturally impossible.
`SharedLinkBagBTree.put()` and `remove()` both acquire the component-level
exclusive lock via `calculateInsideComponentOperation()` which calls
`acquireExclusiveLockTillOperationComplete()`. This lock is held for the
entire atomic operation. Multiple threads calling put()/remove() on the same
SharedLinkBagBTree instance will serialize — only one thread executes at a
time. The GC logic runs inside this exclusive lock. There is no window for
concurrent GC-triggering splits.
**Proposed fix**: Reframe as a **serialized stress test** that verifies tree
invariants hold after many interleaved put/remove operations from multiple
threads (each in separate atomic operations). The test value is: (1) no
deadlocks under contention, (2) tree size consistency after many operations
where GC triggers non-deterministically, (3) no corruption from the
lock-release/reacquire pattern.
**Decision**: ACCEPTED — reframe concurrent tests as contention stress tests.

### Finding T2 [should-fix]
**Location**: Track 3, "Crash recovery test (TY1)" — "Use the existing
crash-recovery test infrastructure"
**Issue**: The plan assumes a B-tree-level crash-recovery test pattern. In
reality, crash-recovery tests operate at the database session level.
`IndexHistogramDurabilityTest` uses `forceDatabaseClose()` followed by
`ytdb.open()` to simulate crash and recovery. There is no lower-level
pattern for B-tree crash-recovery. The static storage/atomicOperationsManager
setup in existing tombstone GC tests is incompatible with `forceDatabaseClose`.
**Proposed fix**: Follow the `IndexHistogramDurabilityTest` pattern: create
DISK database, populate edges via database API, trigger GC operations,
`forceDatabaseClose()`, reopen, verify edge data consistency via queries.
**Decision**: ACCEPTED — use database-level crash simulation.

### Finding T3 [should-fix]
**Location**: Track 3, "Verify tree size matches actual entries"
**Issue**: After `forceDatabaseClose()` + reopen, direct B-tree inspection
(tree size counter, `findCurrentEntry`) is not feasible through the database
lifecycle. The WAL guarantees atomicity: if the atomic operation was committed,
the GC'd split is durable; if not, it's rolled back entirely.
**Proposed fix**: Define crash-recovery verification in terms of observable
behavior: (1) surviving edges are traversable, (2) deleted edges don't
reappear, (3) no exceptions during traversal. Drop internal tree size check.
**Decision**: ACCEPTED — verify via observable behavior.

### Finding T4 [suggestion]
**Location**: Track 3, "Use ConcurrentTestHelper"
**Issue**: `ConcurrentTestHelper` works for simple parallel hammering.
Existing `SharedLinkBagBTreeSIReadPathTest` uses `CountDownLatch` instead.
**Proposed fix**: Let implementation choose the appropriate concurrency
utility based on test shape.
**Decision**: ACCEPTED.

### Finding T5 [suggestion]
**Location**: Track 3, overall scope
**Issue**: Given serialized locking, the concurrent test primarily validates
locking infrastructure, not GC-specific concurrency. The crash-recovery test
is more valuable but requires a different setup.
**Proposed fix**: Keep both tests but with proper framing. Stress test
validates contention safety; crash-recovery test validates WAL durability.
**Decision**: PARTIALLY ACCEPTED — keep both tests with corrected framing.

## Summary
- 1 blocker (T1) — resolved by reframing concurrent test approach
- 2 should-fix (T2, T3) — resolved by switching to database-level crash pattern
- 2 suggestions (T4, T5) — accepted
- All findings resolved in step decomposition below
