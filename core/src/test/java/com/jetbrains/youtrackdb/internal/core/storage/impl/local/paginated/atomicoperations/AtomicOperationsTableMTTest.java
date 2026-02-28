package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vmlens.api.AllInterleavings;
import com.vmlens.api.AllInterleavingsBuilder;
import org.junit.jupiter.api.Test;

/**
 * Multi-threaded tests for {@link AtomicOperationsTable} using VMLens for
 * systematic interleaving exploration. VMLens exhaustively explores thread
 * schedules, so small operation counts are sufficient for thorough coverage.
 *
 * <p>Each test focuses on a specific concurrent interaction with the cached
 * min field and snapshot scan range narrowing.
 */
public class AtomicOperationsTableMTTest {

  // VMLens exhaustively explores all thread interleavings, so small counts
  // provide thorough coverage (unlike random sampling which needs many iterations).
  // AtomicOperationsTable has many synchronization points (VarHandle CAS +
  // ScalableRWLock + CASObjectArray), which creates a large interleaving space.
  // A lower limit avoids hitting VMLens internal bugs when the alternating order
  // exploration is very large.
  private static final int MAX_ITERATIONS = 100;

  private AllInterleavings allInterleavings(String name) throws Exception {
    return new AllInterleavingsBuilder()
        .withMaximumIterations(MAX_ITERATIONS)
        .build(name);
  }

  /**
   * Verifies that a snapshot's min/max match the actual min/max of its
   * inProgressTxs set. If the set is empty, min and max must be equal
   * (both set to currentTimestamp + 1).
   */
  private static void verifySnapshotConsistency(
      AtomicOperationsTable.AtomicOperationsSnapshot snap) {
    var set = snap.inProgressTxs();
    if (set.isEmpty()) {
      assertEquals(snap.minActiveOperationTs(), snap.maxActiveOperationTs(),
          "min and max must be equal when no ops are in progress");
      return;
    }

    var actualMin = Long.MAX_VALUE;
    var actualMax = Long.MIN_VALUE;
    for (var it = set.iterator(); it.hasNext(); ) {
      var ts = it.nextLong();
      if (ts < actualMin) {
        actualMin = ts;
      }
      if (ts > actualMax) {
        actualMax = ts;
      }
    }
    assertEquals(actualMin, snap.minActiveOperationTs(),
        "snapshot min must match actual min of inProgressTxs set");
    assertEquals(actualMax, snap.maxActiveOperationTs(),
        "snapshot max must match actual max of inProgressTxs set");
  }

  /**
   * One thread starts a new operation while another takes a snapshot on a table
   * that already has active operations. The snapshot must be self-consistent.
   */
  @Test
  public void concurrentStartWithSnapshotShouldBeConsistent() throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentStartWithSnapshotShouldBeConsistent")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        // Pre-establish one operation and cached min
        table.startOperation(1, 1);
        table.snapshotAtomicOperationTableState(100);

        var snapHolder = new AtomicOperationsTable.AtomicOperationsSnapshot[1];

        // Thread 1 starts a new operation, Thread 2 takes a snapshot
        var t1 = new Thread(() -> table.startOperation(2, 2));
        var t2 = new Thread(
            () -> snapHolder[0] = table.snapshotAtomicOperationTableState(100));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        verifySnapshotConsistency(snapHolder[0]);

        // After both threads: both ops should be visible
        var snapAfter = table.snapshotAtomicOperationTableState(100);
        verifySnapshotConsistency(snapAfter);
        assertEquals(2, snapAfter.inProgressTxs().size());
      }
    }
  }

  /**
   * One thread starts an operation while another commits a different one.
   * Every interleaving must produce a self-consistent snapshot.
   */
  @Test
  public void concurrentStartAndCommitShouldProduceConsistentSnapshot()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentStartAndCommitShouldProduceConsistentSnapshot")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        // Pre-start two operations
        table.startOperation(1, 1);
        table.startOperation(2, 2);
        // Establish cached min
        table.snapshotAtomicOperationTableState(100);

        // Thread 1 commits ts=1 (the min), Thread 2 starts ts=3
        var t1 = new Thread(() -> table.commitOperation(1));
        var t2 = new Thread(() -> table.startOperation(3, 3));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        var snap = table.snapshotAtomicOperationTableState(100);
        verifySnapshotConsistency(snap);
        // ts=1 is committed, ts=2 and ts=3 should be in progress
        assertTrue(snap.inProgressTxs().contains(2));
        assertTrue(snap.inProgressTxs().contains(3));
      }
    }
  }

  /**
   * One thread rolls back the min while another takes a snapshot. The snapshot
   * must be self-consistent regardless of the interleaving.
   */
  @Test
  public void concurrentRollbackMinWithSnapshotShouldBeConsistent()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentRollbackMinWithSnapshotShouldBeConsistent")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        table.startOperation(1, 1);
        table.startOperation(2, 2);
        table.startOperation(3, 3);
        table.snapshotAtomicOperationTableState(100);

        var snapHolder = new AtomicOperationsTable.AtomicOperationsSnapshot[1];

        var t1 = new Thread(() -> table.rollbackOperation(1));
        var t2 = new Thread(
            () -> snapHolder[0] = table.snapshotAtomicOperationTableState(100));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        verifySnapshotConsistency(snapHolder[0]);
      }
    }
  }

  /**
   * One thread commits the min (triggering a forward scan for the new min)
   * while another thread takes a snapshot. Every interleaving must produce
   * a self-consistent snapshot.
   */
  @Test
  public void concurrentCommitMinWithSnapshotShouldBeConsistent()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentCommitMinWithSnapshotShouldBeConsistent")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        table.startOperation(1, 1);
        table.startOperation(2, 2);
        table.startOperation(3, 3);
        // Establish cached min
        table.snapshotAtomicOperationTableState(100);

        var snapHolder = new AtomicOperationsTable.AtomicOperationsSnapshot[1];

        // Thread 1 commits ts=1 (triggers forward scan for new min)
        var t1 = new Thread(() -> table.commitOperation(1));
        // Thread 2 takes a snapshot concurrently
        var t2 = new Thread(
            () -> snapHolder[0] = table.snapshotAtomicOperationTableState(100));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // The concurrent snapshot must be self-consistent
        verifySnapshotConsistency(snapHolder[0]);

        // A snapshot after both threads complete must also be consistent
        var snapAfter = table.snapshotAtomicOperationTableState(100);
        verifySnapshotConsistency(snapAfter);
        assertTrue(snapAfter.inProgressTxs().contains(2));
        assertTrue(snapAfter.inProgressTxs().contains(3));
      }
    }
  }

  /**
   * One thread commits the highest active operation while another starts a new
   * operation. Every interleaving must produce a self-consistent snapshot.
   */
  @Test
  public void concurrentCommitMaxWithStartShouldBeConsistent()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentCommitMaxWithStartShouldBeConsistent")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        table.startOperation(1, 1);
        table.startOperation(3, 3);
        table.snapshotAtomicOperationTableState(100);

        // Thread 1 commits ts=3, Thread 2 starts ts=5
        var t1 = new Thread(() -> table.commitOperation(3));
        var t2 = new Thread(() -> table.startOperation(5, 5));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        var snap = table.snapshotAtomicOperationTableState(100);
        verifySnapshotConsistency(snap);
        assertTrue(snap.inProgressTxs().contains(1));
        assertTrue(snap.inProgressTxs().contains(5));
      }
    }
  }

  /**
   * Commits the only active operation while another thread starts a new
   * operation. Every interleaving must produce a self-consistent snapshot.
   */
  @Test
  public void concurrentCommitSoleOpWithStartShouldBeConsistent()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentCommitSoleOpWithStartShouldBeConsistent")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        table.startOperation(1, 1);
        table.snapshotAtomicOperationTableState(100);

        // Thread 1 commits the only op, Thread 2 starts a new one
        var t1 = new Thread(() -> table.commitOperation(1));
        var t2 = new Thread(() -> table.startOperation(2, 2));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        var snap = table.snapshotAtomicOperationTableState(100);
        verifySnapshotConsistency(snap);
        // ts=1 committed, ts=2 should be in progress
        assertEquals(1, snap.inProgressTxs().size());
        assertTrue(snap.inProgressTxs().contains(2));
      }
    }
  }

  /**
   * Two threads take snapshots concurrently while operations are in progress.
   * Both snapshots must be self-consistent.
   */
  @Test
  public void concurrentSnapshotsShouldBothBeConsistent() throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentSnapshotsShouldBothBeConsistent")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        table.startOperation(1, 1);
        table.startOperation(3, 3);
        table.startOperation(5, 5);

        var snap1Holder = new AtomicOperationsTable.AtomicOperationsSnapshot[1];
        var snap2Holder = new AtomicOperationsTable.AtomicOperationsSnapshot[1];

        var t1 = new Thread(
            () -> snap1Holder[0] = table.snapshotAtomicOperationTableState(100));
        var t2 = new Thread(
            () -> snap2Holder[0] = table.snapshotAtomicOperationTableState(100));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        verifySnapshotConsistency(snap1Holder[0]);
        verifySnapshotConsistency(snap2Holder[0]);
        assertEquals(3, snap1Holder[0].inProgressTxs().size());
        assertEquals(3, snap2Holder[0].inProgressTxs().size());
      }
    }
  }

  /**
   * One thread commits a non-boundary operation while another takes a snapshot.
   * The cached min should remain unchanged since the committed operation is not
   * the min. Every interleaving must produce a self-consistent snapshot.
   */
  @Test
  public void concurrentNonBoundaryCommitWithSnapshotShouldBeConsistent()
      throws Exception {
    try (var allInterleavings = allInterleavings(
        "concurrentNonBoundaryCommitWithSnapshotShouldBeConsistent")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        table.startOperation(1, 1);
        table.startOperation(3, 3);
        table.startOperation(5, 5);
        // Establish cached min via snapshot
        table.snapshotAtomicOperationTableState(100);

        var snapHolder = new AtomicOperationsTable.AtomicOperationsSnapshot[1];

        // Thread 1 commits middle op (ts=3), Thread 2 takes snapshot
        var t1 = new Thread(() -> table.commitOperation(3));
        var t2 = new Thread(
            () -> snapHolder[0] = table.snapshotAtomicOperationTableState(100));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        verifySnapshotConsistency(snapHolder[0]);

        // After both threads: min=1, max=5, only ts=3 committed
        var snapAfter = table.snapshotAtomicOperationTableState(100);
        verifySnapshotConsistency(snapAfter);
        assertEquals(2, snapAfter.inProgressTxs().size());
        assertTrue(snapAfter.inProgressTxs().contains(1));
        assertTrue(snapAfter.inProgressTxs().contains(5));
      }
    }
  }

  /**
   * Regression test: verifies that a snapshot never reports a
   * {@code maxActiveOperationTs} lower than any actually-found IN_PROGRESS
   * entry. Before the fix, the snapshot scan used a cached upper bound
   * ({@code cachedMaxActiveTs}) that could be stale when a new operation was
   * concurrently started. The scan would stop too early and produce a snapshot
   * with a {@code maxActiveOperationTs} that excluded the just-started entry.
   * This caused committed operations between the stale max and the true max
   * to be erroneously visible, violating Snapshot Isolation.
   *
   * <p>The test starts two operations, establishes cached scan bounds, then
   * concurrently starts a third operation (beyond the cached max) while
   * taking a snapshot. The snapshot's {@code maxActiveOperationTs} must be
   * at least as high as every entry in its {@code inProgressTxs} set.
   */
  @Test
  public void snapshotShouldNeverMissStartBeyondCachedMax()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("snapshotShouldNeverMissStartBeyondCachedMax")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        // Pre-establish two operations and populate the cached min bound.
        table.startOperation(1, 1);
        table.startOperation(2, 2);
        table.snapshotAtomicOperationTableState(100);

        var snapHolder = new AtomicOperationsTable.AtomicOperationsSnapshot[1];

        // Thread 1 starts ts=3 (beyond the previously scanned max=2).
        // Thread 2 takes a snapshot concurrently.
        var t1 = new Thread(() -> table.startOperation(3, 3));
        var t2 = new Thread(
            () -> snapHolder[0] = table.snapshotAtomicOperationTableState(100));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // The concurrent snapshot must be self-consistent: min/max must
        // match the actual bounds of inProgressTxs.
        verifySnapshotConsistency(snapHolder[0]);

        // Stronger check: maxActiveOperationTs must be >= every entry.
        // With the old cachedMax-based scan, certain interleavings would
        // produce a snapshot with max=2 even though ts=3 was IN_PROGRESS,
        // because the scan stopped at the stale cachedMax=2.
        var snap = snapHolder[0];
        for (var it = snap.inProgressTxs().iterator(); it.hasNext(); ) {
          var ts = it.nextLong();
          assertTrue(ts <= snap.maxActiveOperationTs(),
              "snapshot max (" + snap.maxActiveOperationTs()
                  + ") must be >= every in-progress entry (" + ts + ")");
          assertTrue(ts >= snap.minActiveOperationTs(),
              "snapshot min (" + snap.minActiveOperationTs()
                  + ") must be <= every in-progress entry (" + ts + ")");
        }

        // After both threads complete, all three operations must be visible.
        var snapAfter = table.snapshotAtomicOperationTableState(100);
        verifySnapshotConsistency(snapAfter);
        assertEquals(3, snapAfter.inProgressTxs().size());
        assertTrue(snapAfter.inProgressTxs().contains(1));
        assertTrue(snapAfter.inProgressTxs().contains(2));
        assertTrue(snapAfter.inProgressTxs().contains(3));
      }
    }
  }
}
