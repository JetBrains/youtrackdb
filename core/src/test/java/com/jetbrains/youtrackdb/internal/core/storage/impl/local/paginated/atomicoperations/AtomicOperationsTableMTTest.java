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
 * min/max fields and snapshot scan range narrowing.
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
        // Pre-establish one operation and caches
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
        // Establish caches
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
        // Establish caches
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
   * One thread commits the max while another starts a new operation.
   * The max cache is invalidated on commit and should be restored by the
   * new start. Every interleaving must produce a self-consistent snapshot.
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
        // Establish caches: min=1, max=3
        table.snapshotAtomicOperationTableState(100);

        // Thread 1 commits max (ts=3), Thread 2 starts ts=5
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
   * Commits the only active operation (isMin && isMax) while another thread
   * starts a new operation. Both caches are invalidated to UNKNOWN, then the
   * new start should restore them. Every interleaving must produce a
   * self-consistent snapshot.
   */
  @Test
  public void concurrentCommitSoleOpWithStartShouldBeConsistent()
      throws Exception {
    try (var allInterleavings =
        allInterleavings("concurrentCommitSoleOpWithStartShouldBeConsistent")) {
      while (allInterleavings.hasNext()) {
        var table = new AtomicOperationsTable(100, 1);
        table.startOperation(1, 1);
        // Establish caches: min=1, max=1
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
   * The cached min/max should remain unchanged (Case 4 in the commit handler).
   * Every interleaving must produce a self-consistent snapshot.
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
        // Establish caches: min=1, max=5
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
}
