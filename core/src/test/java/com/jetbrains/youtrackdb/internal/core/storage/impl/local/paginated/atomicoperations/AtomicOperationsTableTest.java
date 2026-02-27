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

    try (var executor = Executors.newFixedThreadPool(threadCount)) {
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
    }

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

    try (var executor = Executors.newFixedThreadPool(threadCount)) {
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
    }

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

    try (var executor = Executors.newFixedThreadPool(threadCount)) {
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
    }

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

    try (var executor = Executors.newFixedThreadPool(writerThreads + readerThreads)) {
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
    }

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

    try (var executor = Executors.newFixedThreadPool(threadCount)) {
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
    }

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

    try (var executor = Executors.newFixedThreadPool(threadCount)) {
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
    }

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
    // Earliest not-persisted operation should map to WAL segment 10 (operation 0)
    assertEquals(10, table.getSegmentEarliestNotPersistedOperation());

    // Persist out of order
    table.persistOperation(1);
    assertEquals(10, table.getSegmentEarliestNotPersistedOperation());

    table.persistOperation(0);
    assertEquals(12, table.getSegmentEarliestNotPersistedOperation());

    table.persistOperation(2);
    assertEquals(-1, table.getSegmentEarliestNotPersistedOperation());
  }

  // ==================== Cached Min/Max Tests ====================

  /// Verifies the basic lifecycle of cached min/max: start ops, take snapshot to
  /// verify min/max, commit boundary ops, and verify min is updated via forward
  /// scan while max is invalidated and recovered.
  @Test
  public void testCachedMinMaxBasicLifecycle() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(1, 1);
    table.startOperation(5, 5);
    table.startOperation(10, 10);

    // First snapshot establishes cached min=1, max=10
    var snap1 = table.snapshotAtomicOperationTableState(100);
    assertEquals(1, snap1.minActiveOperationTs());
    assertEquals(10, snap1.maxActiveOperationTs());
    assertEquals(3, snap1.inProgressTxs().size());

    // Commit the min (ts=1) — forward scan should set cached min to 5
    table.commitOperation(1);
    var snap2 = table.snapshotAtomicOperationTableState(100);
    assertEquals(5, snap2.minActiveOperationTs());
    assertEquals(10, snap2.maxActiveOperationTs());
    assertEquals(2, snap2.inProgressTxs().size());

    // Commit the max (ts=10) — max invalidated, snapshot recovers it to 5
    table.commitOperation(10);
    var snap3 = table.snapshotAtomicOperationTableState(100);
    assertEquals(5, snap3.minActiveOperationTs());
    assertEquals(5, snap3.maxActiveOperationTs());
    assertEquals(1, snap3.inProgressTxs().size());
  }

  /// Starts 5 operations, commits the min, and verifies that the new min
  /// is the next IN_PROGRESS entry (forward-scanned, not a full rescan).
  @Test
  public void testCachedMinForwardScanOnMinCommit() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(1, 1);
    table.startOperation(2, 2);
    table.startOperation(3, 3);
    table.startOperation(4, 4);
    table.startOperation(5, 5);

    // Establish caches
    table.snapshotAtomicOperationTableState(100);

    // Commit the min (ts=1) — forward scan finds ts=2
    table.commitOperation(1);
    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(2, snap.minActiveOperationTs());
    assertEquals(5, snap.maxActiveOperationTs());
    assertEquals(4, snap.inProgressTxs().size());
    assertTrue(snap.inProgressTxs().contains(2));
    assertTrue(snap.inProgressTxs().contains(3));
    assertTrue(snap.inProgressTxs().contains(4));
    assertTrue(snap.inProgressTxs().contains(5));
  }

  /// Starts 3 operations, commits the max, and verifies max is recovered
  /// either by the next start or by the snapshot scan.
  @Test
  public void testCachedMaxInvalidatedOnMaxCommit() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(1, 1);
    table.startOperation(5, 5);
    table.startOperation(10, 10);

    // Establish caches
    table.snapshotAtomicOperationTableState(100);

    // Commit the max (ts=10) — max goes to UNKNOWN
    table.commitOperation(10);

    // Snapshot recovers the actual max from scan
    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(1, snap.minActiveOperationTs());
    assertEquals(5, snap.maxActiveOperationTs());
    assertEquals(2, snap.inProgressTxs().size());

    // Starting a new operation sets max correctly
    table.startOperation(20, 20);
    var snap2 = table.snapshotAtomicOperationTableState(100);
    assertEquals(1, snap2.minActiveOperationTs());
    assertEquals(20, snap2.maxActiveOperationTs());
    assertEquals(3, snap2.inProgressTxs().size());
  }

  /// Commits both the min and max boundaries and verifies correct state:
  /// min is forward-scanned, max is invalidated.
  @Test
  public void testCachedMinMaxBothBoundariesCommitted() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(1, 1);
    table.startOperation(5, 5);
    table.startOperation(10, 10);

    // Establish caches
    table.snapshotAtomicOperationTableState(100);

    // Commit min first, then max
    table.commitOperation(1);
    table.commitOperation(10);

    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(5, snap.minActiveOperationTs());
    assertEquals(5, snap.maxActiveOperationTs());
    assertEquals(1, snap.inProgressTxs().size());
    assertTrue(snap.inProgressTxs().contains(5));
  }

  /// Only one active operation: commit it and verify both caches go to
  /// UNKNOWN. The next snapshot should return currentTimestamp + 1.
  @Test
  public void testCachedMinMaxSingleOpCommit() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(5, 5);

    // Establish caches via snapshot
    var snap1 = table.snapshotAtomicOperationTableState(100);
    assertEquals(5, snap1.minActiveOperationTs());
    assertEquals(5, snap1.maxActiveOperationTs());

    // Commit the only active op — both caches become UNKNOWN
    table.commitOperation(5);

    var snap2 = table.snapshotAtomicOperationTableState(100);
    // No active ops: min and max should be currentTimestamp + 1
    assertEquals(101, snap2.minActiveOperationTs());
    assertEquals(101, snap2.maxActiveOperationTs());
    assertTrue(snap2.inProgressTxs().isEmpty());
  }

  /// Commits a middle operation (not min, not max) and verifies that
  /// the cached min/max remain unchanged.
  @Test
  public void testCachedMinMaxNonBoundaryCommit() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(1, 1);
    table.startOperation(5, 5);
    table.startOperation(10, 10);

    // Establish caches
    table.snapshotAtomicOperationTableState(100);

    // Commit the middle op (ts=5) — neither min nor max should change
    table.commitOperation(5);

    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(1, snap.minActiveOperationTs());
    assertEquals(10, snap.maxActiveOperationTs());
    assertEquals(2, snap.inProgressTxs().size());
    assertTrue(snap.inProgressTxs().contains(1));
    assertTrue(snap.inProgressTxs().contains(10));
  }

  /// Verifies that cached min/max survive compaction correctly: after
  /// compaction restructures segments, snapshots still return accurate results.
  @Test
  public void testCachedMinMaxAfterCompaction() {
    var table = new AtomicOperationsTable(10, 1);

    // Create enough operations to build up segments
    for (var i = 1; i <= 15; i++) {
      table.startOperation(i, i);
      if (i <= 10) {
        table.commitOperation(i);
        table.persistOperation(i);
      }
    }

    // Establish caches — active ops are 11-15
    var snap1 = table.snapshotAtomicOperationTableState(100);
    assertEquals(11, snap1.minActiveOperationTs());
    assertEquals(15, snap1.maxActiveOperationTs());

    // Force compaction — segments restructure but active ops don't change
    table.compactTable();

    // Snapshot after compaction should show same active set
    var snap2 = table.snapshotAtomicOperationTableState(100);
    assertEquals(11, snap2.minActiveOperationTs());
    assertEquals(15, snap2.maxActiveOperationTs());
    assertEquals(5, snap2.inProgressTxs().size());
  }

  /// Verifies that snapshot min/max always match the actual min/max of the
  /// inProgressTxs set across a sequence of operations.
  @Test
  public void testCachedMinMaxConsistencyWithInProgressSet() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(3, 3);
    table.startOperation(7, 7);
    table.startOperation(12, 12);
    table.startOperation(18, 18);

    // Verify consistency after each mutation
    assertSnapshotConsistency(table, 100);

    table.commitOperation(3);
    assertSnapshotConsistency(table, 100);

    table.commitOperation(18);
    assertSnapshotConsistency(table, 100);

    table.rollbackOperation(7);
    assertSnapshotConsistency(table, 100);

    table.commitOperation(12);
    assertSnapshotConsistency(table, 100);
  }

  /// Verifies that with cached min/max, the snapshot correctly builds the
  /// inProgressTxs set — all active operations are included, no extras.
  @Test
  public void testSnapshotScanRangeNarrowing() {
    var table = new AtomicOperationsTable(100, 1);

    // Start operations spread across the table
    table.startOperation(5, 5);
    table.startOperation(10, 10);
    table.startOperation(50, 50);
    table.startOperation(90, 90);

    // First snapshot: full scan establishes caches
    var snap1 = table.snapshotAtomicOperationTableState(100);
    assertEquals(4, snap1.inProgressTxs().size());
    assertEquals(5, snap1.minActiveOperationTs());
    assertEquals(90, snap1.maxActiveOperationTs());

    // Second snapshot: narrowed scan [5, 90] should find all the same ops
    var snap2 = table.snapshotAtomicOperationTableState(100);
    assertEquals(4, snap2.inProgressTxs().size());
    assertTrue(snap2.inProgressTxs().contains(5));
    assertTrue(snap2.inProgressTxs().contains(10));
    assertTrue(snap2.inProgressTxs().contains(50));
    assertTrue(snap2.inProgressTxs().contains(90));

    // Commit min and max, verify narrowed scan still works
    table.commitOperation(5);
    table.commitOperation(90);
    var snap3 = table.snapshotAtomicOperationTableState(100);
    assertEquals(2, snap3.inProgressTxs().size());
    assertEquals(10, snap3.minActiveOperationTs());
    assertEquals(50, snap3.maxActiveOperationTs());
  }

  /// All operations complete, both caches go UNKNOWN. New starts correctly
  /// reinitialize both caches.
  @Test
  public void testCachedMinMaxRecoveryAfterAllOpsComplete() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(1, 1);
    table.startOperation(5, 5);

    // Establish caches
    table.snapshotAtomicOperationTableState(100);

    // Complete all ops
    table.commitOperation(1);
    table.commitOperation(5);

    // No active ops — snapshot returns currentTimestamp + 1
    var snap1 = table.snapshotAtomicOperationTableState(200);
    assertEquals(201, snap1.minActiveOperationTs());
    assertEquals(201, snap1.maxActiveOperationTs());
    assertTrue(snap1.inProgressTxs().isEmpty());

    // New starts reinitialize caches
    table.startOperation(10, 10);
    table.startOperation(20, 20);

    var snap2 = table.snapshotAtomicOperationTableState(200);
    assertEquals(10, snap2.minActiveOperationTs());
    assertEquals(20, snap2.maxActiveOperationTs());
    assertEquals(2, snap2.inProgressTxs().size());
  }

  /// Rolls back the min boundary and verifies that the cached min advances
  /// to the next IN_PROGRESS entry (same as commit, but via rollback path).
  @Test
  public void testCachedMinMaxRollbackOfMinBoundary() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(1, 1);
    table.startOperation(5, 5);
    table.startOperation(10, 10);

    // Establish caches
    table.snapshotAtomicOperationTableState(100);

    // Rollback the min (ts=1) — forward scan should find ts=5
    table.rollbackOperation(1);
    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(5, snap.minActiveOperationTs());
    assertEquals(10, snap.maxActiveOperationTs());
    assertEquals(2, snap.inProgressTxs().size());

    // Rollback the max (ts=10) — max invalidated, snapshot recovers to 5
    table.rollbackOperation(10);
    var snap2 = table.snapshotAtomicOperationTableState(100);
    assertEquals(5, snap2.minActiveOperationTs());
    assertEquals(5, snap2.maxActiveOperationTs());
    assertEquals(1, snap2.inProgressTxs().size());
  }

  /// Creates a gap larger than MAX_FORWARD_SCAN (128) between the committed
  /// min and the next IN_PROGRESS entry. Verifies that the forward scan cap
  /// causes a fallback to UNKNOWN and the snapshot still produces correct
  /// results via a broader scan.
  @Test
  public void testForwardScanCapFallbackToUnknown() {
    var table = new AtomicOperationsTable(1000, 1);

    // Start 200 operations: ts 1..200
    for (var i = 1; i <= 200; i++) {
      table.startOperation(i, i);
    }

    // Establish caches — min=1, max=200
    var snap1 = table.snapshotAtomicOperationTableState(300);
    assertEquals(1, snap1.minActiveOperationTs());
    assertEquals(200, snap1.maxActiveOperationTs());

    // Commit the first 150 operations, creating a gap of 150
    // between the old min (1) and the next IN_PROGRESS (151)
    for (var i = 1; i <= 150; i++) {
      table.commitOperation(i);
    }

    // The forward scan on min=1's commit scans up to 128 entries and gives up
    // (returns UNKNOWN). The next snapshot must still find the correct min.
    var snap2 = table.snapshotAtomicOperationTableState(300);
    assertEquals(151, snap2.minActiveOperationTs());
    assertEquals(200, snap2.maxActiveOperationTs());
    assertEquals(50, snap2.inProgressTxs().size());
  }

  /// Creates operations spanning multiple segments with a small compaction
  /// interval, then verifies that the snapshot correctly skips completed
  /// segments via scan range narrowing.
  @Test
  public void testMultiSegmentScanRangeSkipping() {
    // Small compaction interval to create multiple segments
    var table = new AtomicOperationsTable(5, 1);

    // Start 20 operations (spanning multiple segments)
    for (var i = 1; i <= 20; i++) {
      table.startOperation(i, i);
    }

    // Commit and persist all except ts=15 and ts=18
    for (var i = 1; i <= 20; i++) {
      if (i != 15 && i != 18) {
        table.commitOperation(i);
        table.persistOperation(i);
      }
    }

    // Force compaction to restructure segments
    table.compactTable();

    // Snapshot should find exactly ts=15 and ts=18 despite segment restructuring
    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(15, snap.minActiveOperationTs());
    assertEquals(18, snap.maxActiveOperationTs());
    assertEquals(2, snap.inProgressTxs().size());
    assertTrue(snap.inProgressTxs().contains(15));
    assertTrue(snap.inProgressTxs().contains(18));

    // Second snapshot uses narrowed range [15, 18] — should still be correct
    var snap2 = table.snapshotAtomicOperationTableState(100);
    assertEquals(15, snap2.minActiveOperationTs());
    assertEquals(18, snap2.maxActiveOperationTs());
    assertEquals(2, snap2.inProgressTxs().size());
  }

  /// Verifies correctness when operations are started and committed before
  /// any snapshot is ever taken (caches remain at UNKNOWN throughout).
  @Test
  public void testSnapshotWithNoPriorSnapshotCachesUnknown() {
    var table = new AtomicOperationsTable(100, 1);

    // Start and commit some operations without ever taking a snapshot
    table.startOperation(1, 1);
    table.startOperation(5, 5);
    table.startOperation(10, 10);
    table.commitOperation(1);
    table.commitOperation(10);

    // First-ever snapshot: caches are UNKNOWN, must do full scan
    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(5, snap.minActiveOperationTs());
    assertEquals(5, snap.maxActiveOperationTs());
    assertEquals(1, snap.inProgressTxs().size());
    assertTrue(snap.inProgressTxs().contains(5));
  }

  /// Reproduces out-of-order starts: a higher-TS operation starts before a
  /// lower-TS one. The cached min must be lowered when the lower-TS operation
  /// starts, and snapshots must include both operations.
  @Test
  public void testOutOfOrderStartLowersCachedMin() {
    var table = new AtomicOperationsTable(100, 1);

    // Start ts=1 as the initial min
    table.startOperation(1, 1);

    // Establish caches: min=1, max=1
    var snap0 = table.snapshotAtomicOperationTableState(100);
    assertEquals(1, snap0.minActiveOperationTs());

    // Simulate out-of-order starts: ts=5 arrives before ts=3
    // (this happens when timestamps are assigned under a lock but
    // startOperation is called after releasing it)
    table.startOperation(5, 5);

    // Commit ts=1 (the min) — forward scan skips NOT_STARTED at index 3,
    // finds IN_PROGRESS at index 5
    table.commitOperation(1);

    // Now start ts=3 (out of order) — must lower cachedMin from 5 to 3
    table.startOperation(3, 3);

    // Snapshot must include both ts=3 and ts=5
    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(3, snap.minActiveOperationTs());
    assertEquals(5, snap.maxActiveOperationTs());
    assertEquals(2, snap.inProgressTxs().size());
    assertTrue(snap.inProgressTxs().contains(3));
    assertTrue(snap.inProgressTxs().contains(5));
  }

  /// Verifies that after an out-of-order start lowers the cached min,
  /// committing that min correctly advances the cache via forward scan.
  @Test
  public void testOutOfOrderStartThenCommitMin() {
    var table = new AtomicOperationsTable(100, 1);

    table.startOperation(1, 1);
    table.startOperation(10, 10);

    // Establish caches
    table.snapshotAtomicOperationTableState(100);

    // Commit ts=1, then out-of-order start ts=3
    table.commitOperation(1);
    table.startOperation(3, 3);

    // Verify min was lowered to 3
    var snap1 = table.snapshotAtomicOperationTableState(100);
    assertEquals(3, snap1.minActiveOperationTs());
    assertEquals(10, snap1.maxActiveOperationTs());
    assertEquals(2, snap1.inProgressTxs().size());

    // Now commit ts=3 — forward scan should advance min to 10
    table.commitOperation(3);
    var snap2 = table.snapshotAtomicOperationTableState(100);
    assertEquals(10, snap2.minActiveOperationTs());
    assertEquals(10, snap2.maxActiveOperationTs());
    assertEquals(1, snap2.inProgressTxs().size());
  }

  /// Verifies that multiple out-of-order starts correctly track the lowest
  /// active timestamp, even when several arrive in reverse order.
  @Test
  public void testMultipleOutOfOrderStarts() {
    var table = new AtomicOperationsTable(100, 1);

    // Start ts=10 first (highest)
    table.startOperation(10, 10);

    // Establish caches: min=10, max=10
    table.snapshotAtomicOperationTableState(100);

    // Out-of-order starts in reverse: 7, 5, 3
    table.startOperation(7, 7);
    table.startOperation(5, 5);
    table.startOperation(3, 3);

    var snap = table.snapshotAtomicOperationTableState(100);
    assertEquals(3, snap.minActiveOperationTs());
    assertEquals(10, snap.maxActiveOperationTs());
    assertEquals(4, snap.inProgressTxs().size());
  }

  // ==================== Cached Min/Max Thread Safety Tests ====================

  /// Multiple threads start operations concurrently. After all starts, the
  /// snapshot max must equal the highest TS and min the lowest TS.
  @Test
  public void testConcurrentStartsUpdateCachedMax() throws InterruptedException {
    var table = new AtomicOperationsTable(10000, 1);
    var threadCount = 8;
    var opsPerThread = 200;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(1);

    try (var executor = Executors.newFixedThreadPool(threadCount)) {
      for (var t = 0; t < threadCount; t++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            for (var i = 0; i < opsPerThread; i++) {
              var ts = nextTs.getAndIncrement();
              table.startOperation(ts, ts);
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
    }

    assertNoErrors(errors);

    var totalOps = threadCount * opsPerThread;
    // Take two snapshots: the first updates caches authoritatively, the second
    // uses the narrowed range. Both must be self-consistent and include all ops.
    var snapshot1 = table.snapshotAtomicOperationTableState(totalOps + 100);
    verifySnapshotConsistency(snapshot1);
    var snapshot2 = table.snapshotAtomicOperationTableState(totalOps + 100);
    verifySnapshotConsistency(snapshot2);
    assertEquals(totalOps, snapshot2.inProgressTxs().size());
    assertEquals(1, snapshot2.minActiveOperationTs());
    assertEquals(totalOps, snapshot2.maxActiveOperationTs());
  }

  /// Thread A commits the min (triggering forward scan) while Thread B starts
  /// new operations. Every snapshot taken is self-consistent.
  @Test
  public void testConcurrentCommitMinWithStartsVerifyCacheConsistency()
      throws InterruptedException {
    var table = new AtomicOperationsTable(10000, 1);
    var totalOps = 500;
    var nextTs = new AtomicLong(1);

    // Pre-start operations
    for (var i = 0; i < totalOps; i++) {
      var ts = nextTs.getAndIncrement();
      table.startOperation(ts, ts);
    }

    // Establish caches
    table.snapshotAtomicOperationTableState(totalOps + 100);

    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(3);
    var errors = new ConcurrentLinkedQueue<Throwable>();

    try (var executor = Executors.newFixedThreadPool(3)) {
      // Thread A: commits from the min upward
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var ts = 1L; ts <= totalOps / 2; ts++) {
            table.commitOperation(ts);
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });

      // Thread B: starts new operations
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < 200; i++) {
            var ts = nextTs.getAndIncrement();
            table.startOperation(ts, ts);
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });

      // Thread C: takes snapshots and verifies consistency
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < 300; i++) {
            var snap = table.snapshotAtomicOperationTableState(Long.MAX_VALUE - 2);
            verifySnapshotConsistency(snap);
            Thread.yield();
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });

      startLatch.countDown();
      assertTrue(doneLatch.await(60, TimeUnit.SECONDS));
    }

    assertNoErrors(errors);
  }

  /// Reader threads take snapshots while writer threads start/commit/rollback.
  /// Every snapshot must be self-consistent.
  @Test
  public void testConcurrentSnapshotsWithStatusChanges() throws InterruptedException {
    var table = new AtomicOperationsTable(1000, 1);
    var writerThreads = 4;
    var readerThreads = 4;
    var opsPerWriter = 200;
    var snapshotsPerReader = 300;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(writerThreads + readerThreads);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(1);

    try (var executor = Executors.newFixedThreadPool(writerThreads + readerThreads)) {
      // Writer threads: start, then commit or rollback
      for (var t = 0; t < writerThreads; t++) {
        final var threadId = t;
        executor.submit(() -> {
          try {
            startLatch.await();
            for (var i = 0; i < opsPerWriter; i++) {
              var ts = nextTs.getAndIncrement();
              table.startOperation(ts, ts);
              Thread.yield();
              if ((threadId + i) % 3 == 0) {
                table.rollbackOperation(ts);
              } else {
                table.commitOperation(ts);
              }
            }
          } catch (Throwable e) {
            errors.add(e);
          } finally {
            doneLatch.countDown();
          }
        });
      }

      // Reader threads: take snapshots and verify consistency
      for (var t = 0; t < readerThreads; t++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            for (var i = 0; i < snapshotsPerReader; i++) {
              var snap = table.snapshotAtomicOperationTableState(Long.MAX_VALUE - 2);
              verifySnapshotConsistency(snap);
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
    }

    assertNoErrors(errors);
  }

  /// High contention: many threads rapidly start and immediately commit operations
  /// (frequent boundary changes). Verifies no incorrect snapshots.
  @Test
  public void testConcurrentCacheInvalidationStorm() throws InterruptedException {
    var table = new AtomicOperationsTable(10000, 1);
    var threadCount = 8;
    var opsPerThread = 300;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount + 2);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(1);

    try (var executor = Executors.newFixedThreadPool(threadCount + 2)) {
      // Writer threads: rapid start+commit cycles
      for (var t = 0; t < threadCount; t++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            for (var i = 0; i < opsPerThread; i++) {
              var ts = nextTs.getAndIncrement();
              table.startOperation(ts, ts);
              table.commitOperation(ts);
            }
          } catch (Throwable e) {
            errors.add(e);
          } finally {
            doneLatch.countDown();
          }
        });
      }

      // Reader threads: continuous snapshots
      for (var r = 0; r < 2; r++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            for (var i = 0; i < 500; i++) {
              var snap = table.snapshotAtomicOperationTableState(Long.MAX_VALUE - 2);
              verifySnapshotConsistency(snap);
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
    }

    assertNoErrors(errors);
  }

  /// Mixed workload: threads start, commit, rollback, and take snapshots
  /// concurrently. After each snapshot, min/max match the actual set.
  @Test
  public void testConcurrentMixedOperationsVerifyCacheConsistency()
      throws InterruptedException {
    var table = new AtomicOperationsTable(500, 1);
    var writerThreads = 6;
    var readerThreads = 3;
    var opsPerWriter = 200;
    var snapshotsPerReader = 400;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(writerThreads + readerThreads);
    var errors = new ConcurrentLinkedQueue<Throwable>();
    var nextTs = new AtomicLong(1);

    try (var executor = Executors.newFixedThreadPool(writerThreads + readerThreads)) {
      for (var t = 0; t < writerThreads; t++) {
        final var threadId = t;
        executor.submit(() -> {
          try {
            startLatch.await();
            for (var i = 0; i < opsPerWriter; i++) {
              var ts = nextTs.getAndIncrement();
              table.startOperation(ts, ts);
              Thread.yield();
              switch ((threadId + i) % 4) {
                case 0 -> table.rollbackOperation(ts);
                case 1 -> table.commitOperation(ts);
                default -> {
                  table.commitOperation(ts);
                  table.persistOperation(ts);
                }
              }
            }
          } catch (Throwable e) {
            errors.add(e);
          } finally {
            doneLatch.countDown();
          }
        });
      }

      for (var t = 0; t < readerThreads; t++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            for (var i = 0; i < snapshotsPerReader; i++) {
              var snap = table.snapshotAtomicOperationTableState(Long.MAX_VALUE - 2);
              verifySnapshotConsistency(snap);
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
    }

    assertNoErrors(errors);
  }

  /// Many threads commit the current min concurrently (each triggering a forward
  /// scan). Verifies min converges to the correct value.
  @Test
  public void testRapidMinForwardScansUnderContention() throws InterruptedException {
    var table = new AtomicOperationsTable(10000, 1);
    var totalOps = 1000;

    // Pre-start all operations
    for (var i = 1; i <= totalOps; i++) {
      table.startOperation(i, i);
    }

    // Establish caches
    table.snapshotAtomicOperationTableState(totalOps + 100);

    var threadCount = 8;
    var opsPerThread = totalOps / threadCount;
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount + 1);
    var errors = new ConcurrentLinkedQueue<Throwable>();

    try (var executor = Executors.newFixedThreadPool(threadCount + 1)) {
      // Each thread commits a contiguous slice of operations
      for (var t = 0; t < threadCount; t++) {
        final var fromTs = t * opsPerThread + 1;
        final var toTs = (t + 1) * opsPerThread;
        executor.submit(() -> {
          try {
            startLatch.await();
            for (var ts = fromTs; ts <= toTs; ts++) {
              table.commitOperation(ts);
            }
          } catch (Throwable e) {
            errors.add(e);
          } finally {
            doneLatch.countDown();
          }
        });
      }

      // Reader thread verifies consistency
      executor.submit(() -> {
        try {
          startLatch.await();
          for (var i = 0; i < 500; i++) {
            var snap = table.snapshotAtomicOperationTableState(totalOps + 100);
            verifySnapshotConsistency(snap);
            Thread.yield();
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          doneLatch.countDown();
        }
      });

      startLatch.countDown();
      assertTrue(doneLatch.await(60, TimeUnit.SECONDS));
    }

    assertNoErrors(errors);

    // All operations should be committed
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
  }

  // ==================== Test Helpers ====================

  /// Asserts that a snapshot's min/max match the actual min/max of its
  /// inProgressTxs set. If the set is empty, min and max should be equal
  /// (both set to currentTimestamp + 1).
  private static void verifySnapshotConsistency(
      AtomicOperationsTable.AtomicOperationsSnapshot snap) {
    var set = snap.inProgressTxs();
    if (set.isEmpty()) {
      assertEquals(
          "min and max must be equal when no ops are in progress",
          snap.minActiveOperationTs(), snap.maxActiveOperationTs());
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
    assertEquals("snapshot min must match actual min of inProgressTxs set",
        actualMin, snap.minActiveOperationTs());
    assertEquals("snapshot max must match actual max of inProgressTxs set",
        actualMax, snap.maxActiveOperationTs());
  }

  /// Takes a snapshot and asserts self-consistency.
  private static void assertSnapshotConsistency(
      AtomicOperationsTable table, long currentTimestamp) {
    verifySnapshotConsistency(
        table.snapshotAtomicOperationTableState(currentTimestamp));
  }

  /// Fails the test if any errors were collected.
  private static void assertNoErrors(ConcurrentLinkedQueue<Throwable> errors) {
    if (!errors.isEmpty()) {
      var first = errors.peek();
      fail("Errors occurred: " + first.getClass().getSimpleName()
          + ": " + first.getMessage());
    }
  }
}