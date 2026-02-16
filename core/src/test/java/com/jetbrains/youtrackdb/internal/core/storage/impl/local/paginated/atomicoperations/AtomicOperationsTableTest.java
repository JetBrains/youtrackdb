package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class AtomicOperationsTableTest {

  // ==================== Basic Lifecycle Tests ====================

  @Test
  public void testStartOperation() {
    var table = new AtomicOperationsTable(100, 0);

    table.startOperation(0, 10);

    assertEquals(10, table.getSegmentEarliestOperationInProgress());
    assertEquals(10, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testStartAndCommitOperation() {
    var table = new AtomicOperationsTable(100, 0);

    table.startOperation(0, 10);
    table.commitOperation(0);

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(10, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testFullLifecycleCommitAndPersist() {
    var table = new AtomicOperationsTable(100, 0);

    table.startOperation(0, 10);
    table.commitOperation(0);
    table.persistOperation(0);

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testStartAndRollbackOperation() {
    var table = new AtomicOperationsTable(100, 0);

    table.startOperation(0, 10);
    table.rollbackOperation(0);

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testMultipleOperationsLifecycle() {
    var table = new AtomicOperationsTable(100, 0);

    table.startOperation(0, 10);
    table.startOperation(1, 11);
    table.startOperation(2, 12);

    assertEquals(10, table.getSegmentEarliestOperationInProgress());
    assertEquals(10, table.getSegmentEarliestNotPersistedOperation());

    table.commitOperation(0);
    assertEquals(11, table.getSegmentEarliestOperationInProgress());
    assertEquals(10, table.getSegmentEarliestNotPersistedOperation());

    table.persistOperation(0);
    assertEquals(11, table.getSegmentEarliestOperationInProgress());
    assertEquals(11, table.getSegmentEarliestNotPersistedOperation());

    table.rollbackOperation(1);
    assertEquals(12, table.getSegmentEarliestOperationInProgress());
    assertEquals(12, table.getSegmentEarliestNotPersistedOperation());

    table.commitOperation(2);
    table.persistOperation(2);
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  // ==================== Invalid State Transition Tests ====================

  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void testCommitNonExistentOperationThrows() {
    var table = new AtomicOperationsTable(100, 0);
    table.commitOperation(0);
  }

  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void testRollbackNonExistentOperationThrows() {
    var table = new AtomicOperationsTable(100, 0);
    table.rollbackOperation(0);
  }

  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void testPersistNonExistentOperationThrows() {
    var table = new AtomicOperationsTable(100, 0);
    table.persistOperation(0);
  }

  @Test(expected = IllegalStateException.class)
  public void testPersistInProgressOperationThrows() {
    var table = new AtomicOperationsTable(100, 0);
    table.startOperation(0, 10);
    table.persistOperation(0);
  }

  @Test(expected = IllegalStateException.class)
  public void testCommitCommittedOperationThrows() {
    var table = new AtomicOperationsTable(100, 0);
    table.startOperation(0, 10);
    table.commitOperation(0);
    table.commitOperation(0);
  }

  @Test(expected = IllegalStateException.class)
  public void testRollbackCommittedOperationThrows() {
    var table = new AtomicOperationsTable(100, 0);
    table.startOperation(0, 10);
    table.commitOperation(0);
    table.rollbackOperation(0);
  }

  @Test(expected = IllegalStateException.class)
  public void testStartOperationWithNegativeSegmentThrows() {
    var table = new AtomicOperationsTable(100, 0);
    table.startOperation(0, -1);
  }

  // ==================== Snapshot Visibility Tests ====================

  @Test
  public void testSnapshotNoActiveOperations() {
    var table = new AtomicOperationsTable(100, 0);

    var snapshot = table.snapshotAtomicOperationTableState(100);

    // With no active operations, records at or below currentTimestamp should be visible
    assertTrue(snapshot.isEntryVisible(50));
    assertTrue(snapshot.isEntryVisible(100));
    assertFalse(snapshot.isEntryVisible(101));
    assertFalse(snapshot.isEntryVisible(200));
  }

  @Test
  public void testSnapshotWithSingleInProgressOperation() {
    var table = new AtomicOperationsTable(100, 0);
    table.startOperation(50, 10);

    var snapshot = table.snapshotAtomicOperationTableState(100);

    // Records below minActiveOperationTimestamp are visible
    assertTrue(snapshot.isEntryVisible(0));
    assertTrue(snapshot.isEntryVisible(49));

    // minActiveOperationTimestamp itself is not visible (it's in progress)
    assertFalse(snapshot.isEntryVisible(50));

    // Records at or above maxActiveOperationTimestamp are not visible
    assertFalse(snapshot.isEntryVisible(51));
    assertFalse(snapshot.isEntryVisible(100));
  }

  @Test
  public void testSnapshotWithMultipleInProgressOperations() {
    var table = new AtomicOperationsTable(100, 0);
    table.startOperation(10, 1);
    table.startOperation(20, 2);
    table.startOperation(30, 3);

    var snapshot = table.snapshotAtomicOperationTableState(100);

    // Records below min (10) are visible
    assertTrue(snapshot.isEntryVisible(0));
    assertTrue(snapshot.isEntryVisible(9));

    // In-progress operations are not visible
    assertFalse(snapshot.isEntryVisible(10));
    assertFalse(snapshot.isEntryVisible(20));
    assertFalse(snapshot.isEntryVisible(30));

    // Records at or above max (30) are not visible
    assertFalse(snapshot.isEntryVisible(31));
    assertFalse(snapshot.isEntryVisible(100));
  }

  @Test
  public void testSnapshotWithGapsInInProgressOperations() {
    var table = new AtomicOperationsTable(100, 0);
    table.startOperation(10, 1);
    table.startOperation(30, 3);
    // Note: 20 is NOT started, so it represents a committed operation

    var snapshot = table.snapshotAtomicOperationTableState(100);

    // Records below min (10) are visible
    assertTrue(snapshot.isEntryVisible(9));

    // In-progress operations are not visible
    assertFalse(snapshot.isEntryVisible(10));
    assertFalse(snapshot.isEntryVisible(30));

    // Gaps (not in-progress set) between min and max are visible
    assertTrue(snapshot.isEntryVisible(15));
    assertTrue(snapshot.isEntryVisible(20));
    assertTrue(snapshot.isEntryVisible(25));
    assertTrue(snapshot.isEntryVisible(29));

    // At or above max is not visible
    assertFalse(snapshot.isEntryVisible(31));
  }

  @Test
  public void testSnapshotAfterOperationCommits() {
    var table = new AtomicOperationsTable(100, 0);
    table.startOperation(10, 1);
    table.startOperation(20, 2);

    // Initially both are in progress
    var snapshot1 = table.snapshotAtomicOperationTableState(100);
    assertFalse(snapshot1.isEntryVisible(10));
    assertFalse(snapshot1.isEntryVisible(20));

    // Commit operation 10
    table.commitOperation(10);

    // Now only 20 is in progress
    var snapshot2 = table.snapshotAtomicOperationTableState(100);
    // Operation 10 is still not visible to snapshot2 because minActiveOperationTimestamp is now 20
    // and 10 < 20, so it should be visible
    assertTrue(snapshot2.isEntryVisible(10));
    assertFalse(snapshot2.isEntryVisible(20));
  }

  // ==================== Compaction Tests ====================

  @Test
  public void testCompactionRemovesPersistedOperations() {
    var table = new AtomicOperationsTable(10, 0);

    // Start and complete several operations
    for (var i = 0; i < 5; i++) {
      table.startOperation(i, i);
      table.commitOperation(i);
      table.persistOperation(i);
    }

    // Force compaction
    table.compactTable();

    // Verify all operations are cleared
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testCompactionRemovesRolledBackOperations() {
    var table = new AtomicOperationsTable(10, 0);

    // Start and rollback several operations
    for (var i = 0; i < 5; i++) {
      table.startOperation(i, i);
      table.rollbackOperation(i);
    }

    // Force compaction
    table.compactTable();

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testCompactionPreservesInProgressOperations() {
    var table = new AtomicOperationsTable(10, 0);

    // Complete some operations
    for (var i = 0; i < 3; i++) {
      table.startOperation(i, i);
      table.commitOperation(i);
      table.persistOperation(i);
    }

    // Leave one in progress
    table.startOperation(3, 30);

    // Force compaction
    table.compactTable();

    // The in-progress operation should be preserved
    assertEquals(30, table.getSegmentEarliestOperationInProgress());
  }

  @Test
  public void testCompactionPreservesCommittedOperations() {
    var table = new AtomicOperationsTable(10, 0);

    // Persist some operations
    for (var i = 0; i < 3; i++) {
      table.startOperation(i, i);
      table.commitOperation(i);
      table.persistOperation(i);
    }

    // Leave one committed but not persisted
    table.startOperation(3, 30);
    table.commitOperation(3);

    // Force compaction
    table.compactTable();

    // The committed operation should be preserved
    assertEquals(30, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testAutomaticCompactionTriggering() {
    // Small compaction interval to trigger automatic compaction
    var table = new AtomicOperationsTable(5, 0);

    // Start and complete operations to trigger compaction
    for (var i = 0; i < 10; i++) {
      table.startOperation(i, i);
      table.commitOperation(i);
      table.persistOperation(i);
    }

    // At this point, automatic compaction should have been triggered
    // Start a new operation to verify table is still functional
    table.startOperation(10, 100);
    assertEquals(100, table.getSegmentEarliestOperationInProgress());
  }

  // ==================== Non-Zero Offset Tests ====================

  @Test
  public void testNonZeroInitialOffset() {
    var table = new AtomicOperationsTable(100, 1000);

    table.startOperation(1000, 10);
    table.startOperation(1001, 11);

    assertEquals(10, table.getSegmentEarliestOperationInProgress());

    table.commitOperation(1000);
    assertEquals(11, table.getSegmentEarliestOperationInProgress());
  }

  @Test
  public void testSnapshotWithNonZeroOffset() {
    var table = new AtomicOperationsTable(100, 1000);

    table.startOperation(1010, 10);

    var snapshot = table.snapshotAtomicOperationTableState(1100);

    assertTrue(snapshot.isEntryVisible(1000));
    assertTrue(snapshot.isEntryVisible(1009));
    assertFalse(snapshot.isEntryVisible(1010));
    assertFalse(snapshot.isEntryVisible(1011));
  }

  // ==================== Thread Safety Tests ====================

  @Test
  public void testConcurrentStartOperations() throws InterruptedException {
    var table = new AtomicOperationsTable(1000, 0);
    var threadCount = 10;
    var operationsPerThread = 100;
    var latch = new CountDownLatch(threadCount);
    var errors = new ConcurrentLinkedQueue<Throwable>();

    try (var executor = Executors.newFixedThreadPool(threadCount)) {
      for (var t = 0; t < threadCount; t++) {
        final var threadId = t;
        executor.submit(() -> {
          try {
            for (var i = 0; i < operationsPerThread; i++) {
              long opTs = threadId * operationsPerThread + i;
              table.startOperation(opTs, opTs);
            }
          } catch (Throwable e) {
            errors.add(e);
          } finally {
            latch.countDown();
          }
        });
      }

      assertTrue(latch.await(30, TimeUnit.SECONDS));
      executor.shutdown();
    }

    if (!errors.isEmpty()) {
      fail("Errors occurred during concurrent starts: " + errors.peek().getMessage());
    }

    // Verify all operations are in progress
    var snapshot = table.snapshotAtomicOperationTableState(threadCount * operationsPerThread);
    assertEquals(threadCount * operationsPerThread, snapshot.inProgressTxs().size());
  }

  @Test
  public void testConcurrentStartAndCommit() throws InterruptedException {
    var table = new AtomicOperationsTable(1000, 0);
    var threadCount = 10;
    var operationsPerThread = 100;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(0);

    var executor = Executors.newFixedThreadPool(threadCount);

    for (var t = 0; t < threadCount; t++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < operationsPerThread; i++) {
            var opTs = nextTs.getAndIncrement();
            table.startOperation(opTs, opTs);
            table.commitOperation(opTs);
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    if (!errors.isEmpty()) {
      fail("Errors occurred during concurrent start/commit: " + errors.peek().getMessage());
    }

    // All operations should be committed (not in progress)
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
  }

  @Test
  public void testConcurrentStartCommitAndPersist() throws InterruptedException {
    var table = new AtomicOperationsTable(1000, 0);
    var threadCount = 10;
    var operationsPerThread = 100;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(0);

    var executor = Executors.newFixedThreadPool(threadCount);

    for (var t = 0; t < threadCount; t++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < operationsPerThread; i++) {
            var opTs = nextTs.getAndIncrement();
            table.startOperation(opTs, opTs);
            table.commitOperation(opTs);
            table.persistOperation(opTs);
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    if (!errors.isEmpty()) {
      fail("Errors occurred during concurrent lifecycle: " + errors.peek().getMessage());
    }

    // All operations should be persisted
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testConcurrentSnapshots() throws InterruptedException {
    var table = new AtomicOperationsTable(1000, 0);

    // Start some operations
    for (var i = 0; i < 100; i++) {
      table.startOperation(i, i);
    }

    var threadCount = 10;
    var snapshotsPerThread = 100;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var errors = new ConcurrentLinkedQueue<Throwable>();

    var executor = Executors.newFixedThreadPool(threadCount);

    for (var t = 0; t < threadCount; t++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < snapshotsPerThread; i++) {
            var snapshot = table.snapshotAtomicOperationTableState(200);
            // All started operations should be in the snapshot
            assertEquals(100, snapshot.inProgressTxs().size());
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    if (!errors.isEmpty()) {
      fail("Errors occurred during concurrent snapshots: " + errors.peek().getMessage());
    }
  }

  @Test
  public void testConcurrentOperationsWithSnapshots() throws InterruptedException {
    var table = new AtomicOperationsTable(100, 0);
    var writerThreads = 5;
    var readerThreads = 5;
    var operationsPerWriter = 100;
    var snapshotsPerReader = 100;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(writerThreads + readerThreads);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(0);

    var executor = Executors.newFixedThreadPool(writerThreads + readerThreads);

    // Writer threads
    for (var t = 0; t < writerThreads; t++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < operationsPerWriter; i++) {
            var opTs = nextTs.getAndIncrement();
            table.startOperation(opTs, opTs);
            Thread.yield(); // Allow interleaving
            table.commitOperation(opTs);
            Thread.yield();
            table.persistOperation(opTs);
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });
    }

    // Reader threads
    for (var t = 0; t < readerThreads; t++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < snapshotsPerReader; i++) {
            var snapshot = table.snapshotAtomicOperationTableState(Long.MAX_VALUE);
            // Just ensure snapshot is consistent (no exceptions)
            assertNotNull(snapshot.inProgressTxs());
            Thread.yield();
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(60, TimeUnit.SECONDS));
    executor.shutdown();

    if (!errors.isEmpty()) {
      fail("Errors occurred during concurrent operations with snapshots: " + errors.peek()
          .getMessage());
    }
  }

  @Test
  public void testConcurrentCompaction() throws InterruptedException {
    var table = new AtomicOperationsTable(10, 0);
    var threadCount = 5;
    var operationsPerThread = 50;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(0);

    var executor = Executors.newFixedThreadPool(threadCount);

    for (var t = 0; t < threadCount; t++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < operationsPerThread; i++) {
            var opTs = nextTs.getAndIncrement();
            table.startOperation(opTs, opTs);
            table.commitOperation(opTs);
            table.persistOperation(opTs);
            // Trigger manual compaction occasionally
            if (i % 10 == 0) {
              table.compactTable();
            }
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
    executor.shutdown();

    if (!errors.isEmpty()) {
      fail("Errors occurred during concurrent compaction: " + errors.peek().getMessage());
    }

    // All operations should be complete
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testConcurrentMixedOperations() throws InterruptedException {
    var table = new AtomicOperationsTable(50, 0);
    var threadCount = 10;
    var operationsPerThread = 100;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(0);
    var completedOps = new AtomicInteger(0);

    var executor = Executors.newFixedThreadPool(threadCount);

    for (var t = 0; t < threadCount; t++) {
      final var threadId = t;
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < operationsPerThread; i++) {
            var opTs = nextTs.getAndIncrement();
            table.startOperation(opTs, opTs);

            // Mix of commit+persist and rollback
            if ((threadId + i) % 3 == 0) {
              table.rollbackOperation(opTs);
            } else {
              table.commitOperation(opTs);
              table.persistOperation(opTs);
            }
            completedOps.incrementAndGet();
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue(doneLatch.await(60, TimeUnit.SECONDS));
    executor.shutdown();

    if (!errors.isEmpty()) {
      fail("Errors occurred during concurrent mixed operations: " + errors.peek().getMessage());
    }

    assertEquals(threadCount * operationsPerThread, completedOps.get());

    // All operations should be complete
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  // ==================== Edge Cases Tests ====================

  @Test
  public void testLargeNumberOfOperations() {
    var table = new AtomicOperationsTable(100, 0);
    var count = 10000;

    for (var i = 0; i < count; i++) {
      table.startOperation(i, i % 100);
    }

    var snapshot = table.snapshotAtomicOperationTableState(count + 1);
    assertEquals(count, snapshot.inProgressTxs().size());

    // Commit and persist all
    for (var i = 0; i < count; i++) {
      table.commitOperation(i);
      table.persistOperation(i);
    }

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testOperationsWithSameSegment() {
    var table = new AtomicOperationsTable(100, 0);

    // Multiple operations in the same segment
    table.startOperation(0, 5);
    table.startOperation(1, 5);
    table.startOperation(2, 5);

    assertEquals(5, table.getSegmentEarliestOperationInProgress());

    table.commitOperation(0);
    table.persistOperation(0);

    // Still returns 5 because ops 1 and 2 are still in progress in segment 5
    assertEquals(5, table.getSegmentEarliestOperationInProgress());

    table.commitOperation(1);
    table.persistOperation(1);
    table.commitOperation(2);
    table.persistOperation(2);

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
  }

  @Test
  public void testSnapshotVisibilityEdgeCases() {
    var table = new AtomicOperationsTable(100, 0);

    // Start operations at timestamps 5, 10, 15
    table.startOperation(5, 1);
    table.startOperation(10, 2);
    table.startOperation(15, 3);

    var snapshot = table.snapshotAtomicOperationTableState(20);

    // minActiveOperationTimestamp = 5, maxActiveOperationTimestamp = 15
    // Boundary conditions:
    assertTrue(snapshot.isEntryVisible(4));   // Just below min - visible
    assertFalse(snapshot.isEntryVisible(5));  // Exactly min - not visible
    assertTrue(snapshot.isEntryVisible(6));   // Between min and next in-progress - visible
    assertTrue(snapshot.isEntryVisible(9));   // Just below 10 - visible
    assertFalse(snapshot.isEntryVisible(10)); // In-progress - not visible
    assertTrue(snapshot.isEntryVisible(11));  // Between 10 and 15 - visible
    assertTrue(snapshot.isEntryVisible(14));  // Just below max - visible
    assertFalse(snapshot.isEntryVisible(15)); // Exactly max - not visible
    assertFalse(snapshot.isEntryVisible(16)); // Above max - not visible
  }

  @Test
  public void testEmptyTableOperations() {
    var table = new AtomicOperationsTable(100, 0);

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());

    var snapshot = table.snapshotAtomicOperationTableState(100);
    assertTrue(snapshot.isEntryVisible(50));
    assertTrue(snapshot.isEntryVisible(100));
    assertFalse(snapshot.isEntryVisible(101));
  }

  @Test
  public void testCompactionWithEmptyTable() {
    var table = new AtomicOperationsTable(100, 0);

    // Compacting an empty table should not cause issues
    table.compactTable();

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());

    // Table should still be functional
    table.startOperation(0, 10);
    assertEquals(10, table.getSegmentEarliestOperationInProgress());
  }

  @Test
  public void testRepeatedCompaction() {
    var table = new AtomicOperationsTable(10, 0);

    for (var round = 0; round < 5; round++) {
      // Add operations
      for (var i = 0; i < 20; i++) {
        long ts = round * 20 + i;
        table.startOperation(ts, ts);
        table.commitOperation(ts);
        table.persistOperation(ts);
      }

      // Compact
      table.compactTable();
    }

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  @Test
  public void testOutOfOrderCommits() {
    var table = new AtomicOperationsTable(100, 0);

    // Start operations in order
    table.startOperation(0, 10);
    table.startOperation(1, 11);
    table.startOperation(2, 12);

    // Commit out of order
    table.commitOperation(2);
    table.commitOperation(0);
    table.commitOperation(1);

    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    // Earliest not persisted should be segment 10 (operation 0)
    assertEquals(10, table.getSegmentEarliestNotPersistedOperation());

    // Persist out of order
    table.persistOperation(1);
    assertEquals(10, table.getSegmentEarliestNotPersistedOperation());

    table.persistOperation(0);
    assertEquals(12, table.getSegmentEarliestNotPersistedOperation());

    table.persistOperation(2);
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }
}