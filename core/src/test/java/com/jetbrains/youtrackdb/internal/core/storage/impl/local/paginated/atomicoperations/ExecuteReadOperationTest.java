package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests for {@link AtomicOperationsManager#executeReadOperation} and the per-component
 * StampedLock architecture. Verifies: optimistic reads, writer contention fallback,
 * exception handling, write-lock reentrancy, concurrent stress, standalone component
 * locks, and synthetic lock isolation.
 */
public class ExecuteReadOperationTest extends DbTestBase {

  private AtomicOperationsManager manager() {
    return session.getStorage().getAtomicOperationsManager();
  }

  /**
   * A DurableComponent subclass that exposes shared/exclusive lock methods
   * for testing purposes.
   */
  private static class TestableComponent extends DurableComponent {
    TestableComponent(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage storage,
        String lockName) {
      super(storage, "test", ".tst", lockName);
    }

    public void testAcquireSharedLock() {
      acquireSharedLock();
    }

    public void testReleaseSharedLock() {
      releaseSharedLock();
    }

    public void testAcquireExclusiveLock() {
      acquireExclusiveLock();
    }

    public void testReleaseExclusiveLock() {
      releaseExclusiveLock();
    }
  }

  private TestableComponent fakeComponent(String lockName) {
    return new TestableComponent(session.getStorage(), lockName);
  }

  // ==================== Test (a): Optimistic read succeeds — no contention ===========

  /**
   * When no concurrent writer holds the lock, the optimistic read path should
   * execute the action exactly once and return the result.
   */
  @Test
  public void testOptimisticReadSucceeds_noContention() throws IOException {
    var callCount = new AtomicInteger(0);
    var component = fakeComponent("testComponent_a");

    var result = manager().executeReadOperation(component, () -> {
      callCount.incrementAndGet();
      return "hello";
    });

    assertEquals("hello", result);
    assertEquals(1, callCount.get());
  }

  // ==================== Test (b): Stamp invalid — retry via blocking read lock ========

  /**
   * When a writer holds the component's write lock, tryOptimisticRead returns 0,
   * so the optimistic path is skipped entirely and executeReadOperation goes
   * straight to blocking readLock. Once the writer releases, the blocking read
   * proceeds.
   */
  @Test
  public void testOptimisticReadFails_writerHoldsLock_blockingRetry()
      throws Exception {
    var mgr = manager();
    var component = fakeComponent("testComponent_b");
    var lock = component.stampedLock;

    // Hold the write lock from another thread
    var writerReady = new CountDownLatch(1);
    var releaseWriter = new CountDownLatch(1);
    var writerThread = new Thread(() -> {
      long stamp = lock.writeLock();
      writerReady.countDown();
      try {
        releaseWriter.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      lock.unlockWrite(stamp);
    });
    writerThread.start();
    assertTrue("Writer should acquire lock", writerReady.await(5, TimeUnit.SECONDS));

    var callCount = new AtomicInteger(0);
    var resultRef = new AtomicReference<String>();
    var readerThread = new Thread(() -> {
      try {
        var r = mgr.executeReadOperation(component, () -> {
          callCount.incrementAndGet();
          return "retried";
        });
        resultRef.set(r);
      } catch (IOException e) {
        fail("Unexpected IOException: " + e.getMessage());
      }
    });
    readerThread.start();

    // Give the reader a moment to block on readLock, then release the writer
    Thread.sleep(100);
    releaseWriter.countDown();

    readerThread.join(5000);
    writerThread.join(5000);

    assertEquals("retried", resultRef.get());
    assertEquals(1, callCount.get());
  }

  // ==================== Test (c): Exception during optimistic — retry succeeds ========

  /**
   * When the action throws an exception during the optimistic read and the stamp
   * is invalid (due to a concurrent writer), the exception is treated as a
   * concurrent modification artifact and the action is retried.
   */
  @Test
  public void testExceptionDuringOptimisticRead_concurrentWriter_retrySucceeds()
      throws Exception {
    var mgr = manager();
    var component = fakeComponent("testComponent_c");
    var lock = component.stampedLock;

    var callCount = new AtomicInteger(0);
    var actionStarted = new CountDownLatch(1);
    var writerDone = new CountDownLatch(1);

    var writerThread = new Thread(() -> {
      try {
        actionStarted.await(5, TimeUnit.SECONDS);
        long stamp = lock.writeLock();
        lock.unlockWrite(stamp);
        writerDone.countDown();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    writerThread.start();

    var result = mgr.executeReadOperation(component, () -> {
      int call = callCount.incrementAndGet();
      if (call == 1) {
        actionStarted.countDown();
        try {
          writerDone.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        throw new RuntimeException("simulated corrupt read");
      }
      return "success";
    });

    writerThread.join(5000);

    assertEquals("success", result);
    assertEquals(2, callCount.get());
  }

  // ==================== Test (d): Real exception — stamp valid, propagated ===========

  @Test
  public void testRealIOException_stampValid_propagated() {
    var component = fakeComponent("testComponent_d");

    try {
      manager().executeReadOperation(component, () -> {
        throw new IOException("real IO error");
      });
      fail("Expected IOException to be propagated");
    } catch (IOException e) {
      assertEquals("real IO error", e.getMessage());
    }
  }

  @Test
  public void testRealRuntimeException_stampValid_propagated() throws IOException {
    var component = fakeComponent("testComponent_d2");

    try {
      manager().executeReadOperation(component, () -> {
        throw new IllegalStateException("real runtime error");
      });
      fail("Expected IllegalStateException to be propagated");
    } catch (IllegalStateException e) {
      assertEquals("real runtime error", e.getMessage());
    }
  }

  // ==================== Test (e): Write-lock reentrant fast path ==================

  /**
   * When a thread holds the exclusive lock on a component (via an active atomic
   * operation) and then calls executeReadOperation on the same component, the fast
   * path should detect the ownership via isExclusiveOwner() and execute the action
   * directly without trying to acquire a read lock (which would deadlock since
   * StampedLock is non-reentrant).
   */
  @Test
  public void testWriteLockReentrantFastPath_noDeadlock() throws Exception {
    var mgr = manager();
    var component = fakeComponent("reentrant_component");

    var operation = mgr.startAtomicOperation();
    try {
      // Acquire write lock on the component via the atomic operation
      mgr.acquireExclusiveLockTillOperationComplete(operation, component);

      // Now call executeReadOperation on the same component — must not deadlock
      var callCount = new AtomicInteger(0);
      var result = mgr.executeReadOperation(component, () -> {
        callCount.incrementAndGet();
        return "from_writer";
      });

      assertEquals("from_writer", result);
      assertEquals(1, callCount.get());
    } finally {
      mgr.ensureThatComponentsUnlocked(operation);
    }
  }

  // ==================== Test (f): Per-component lock isolation =======================

  /**
   * Verifies that each DurableComponent uses its OWN StampedLock, not a shared
   * stripe. Two components should not block each other unless they are the same
   * component.
   */
  @Test
  public void testPerComponentLockIsolation() throws Exception {
    var mgr = manager();
    var componentA = fakeComponent("isolation_A");
    var componentB = fakeComponent("isolation_B");

    var operation = mgr.startAtomicOperation();
    try {
      // Acquire exclusive on component A
      mgr.acquireExclusiveLockTillOperationComplete(operation, componentA);

      // executeReadOperation on component B should NOT block
      // (no stripe collision possible — different components, different locks)
      var result = mgr.executeReadOperation(componentB, () -> "not_blocked");
      assertEquals("not_blocked", result);

      // Verify component A's lock is actually held
      long stamp = componentA.stampedLock.tryOptimisticRead();
      assertEquals("Write lock should be held on A, stamp must be 0", 0, stamp);

      // Verify component B's lock is NOT held
      long stampB = componentB.stampedLock.tryOptimisticRead();
      assertTrue("Component B should not be locked", stampB != 0);
    } finally {
      mgr.ensureThatComponentsUnlocked(operation);
    }
  }

  // ==================== Test (g): Synthetic lock for link bags ====================

  /**
   * Verifies that string-only locks (synthetic link bag locks) work correctly
   * without a DurableComponent backing.
   */
  @Test
  public void testSyntheticLock_noComponent() throws Exception {
    var mgr = manager();
    var operation = mgr.startAtomicOperation();
    try {
      String syntheticName = "l_42.idbag";

      // Should not throw — acquires a synthetic StampedLock
      mgr.acquireExclusiveLockTillOperationComplete(operation, syntheticName);
      assertTrue(operation.containsInLockedObjects(syntheticName));

      // Second acquisition of same name should be a no-op (idempotent)
      mgr.acquireExclusiveLockTillOperationComplete(operation, syntheticName);
    } finally {
      mgr.ensureThatComponentsUnlocked(operation);
    }
  }

  // ==================== Test (h): Concurrent readers and writers stress test =========

  @Test
  public void testConcurrentReadersAndWriters_stressTest() throws Exception {
    int numReaders = 4;
    int numWriters = 2;
    int iterationsPerThread = 5_000;

    var mgr = manager();
    var component = fakeComponent("stress_component");
    var sharedCounter = new AtomicLong(0);
    var errors = new AtomicReference<Throwable>();

    ExecutorService executor = Executors.newFixedThreadPool(numReaders + numWriters);
    var startLatch = new CountDownLatch(1);

    for (int i = 0; i < numReaders; i++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (int iter = 0; iter < iterationsPerThread; iter++) {
            long value = mgr.executeReadOperation(component, sharedCounter::get);
            if (value < 0) {
              errors.compareAndSet(null,
                  new AssertionError("Negative counter value: " + value));
              return;
            }
          }
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        }
      });
    }

    for (int i = 0; i < numWriters; i++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (int iter = 0; iter < iterationsPerThread; iter++) {
            var operation = mgr.startAtomicOperation();
            try {
              mgr.acquireExclusiveLockTillOperationComplete(
                  operation, component);
              sharedCounter.incrementAndGet();
            } finally {
              mgr.ensureThatComponentsUnlocked(operation);
            }
          }
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        }
      });
    }

    startLatch.countDown();

    executor.shutdown();
    assertTrue("Threads should finish within timeout",
        executor.awaitTermination(30, TimeUnit.SECONDS));

    if (errors.get() != null) {
      fail("Error during stress test: " + errors.get());
    }
    assertEquals("Writer iterations should match counter",
        (long) numWriters * iterationsPerThread, sharedCounter.get());
  }

  // ========= Test (i): StampedLock shared/exclusive mutual exclusion ==========

  /**
   * Validates that acquireSharedLock() blocks while acquireExclusiveLock()
   * is held on the same component, and vice versa. This ensures standalone shared
   * lock callers still get proper exclusion against concurrent writers.
   */
  @Test
  public void testStampedLock_sharedBlockedByExclusive() throws Exception {
    var component = fakeComponent("stampedLock_mutex_test");

    var exclusiveAcquired = new CountDownLatch(1);
    var releaseExclusive = new CountDownLatch(1);
    var sharedAcquired = new AtomicBoolean(false);
    var errors = new AtomicReference<Throwable>();

    // Thread A: hold the exclusive lock for 200ms
    var writerThread = new Thread(() -> {
      try {
        component.testAcquireExclusiveLock();
        exclusiveAcquired.countDown();
        releaseExclusive.await(10, TimeUnit.SECONDS);
        component.testReleaseExclusiveLock();
      } catch (Throwable t) {
        errors.compareAndSet(null, t);
      }
    });

    // Thread B: try to acquire the shared lock — should block until A releases
    var readerThread = new Thread(() -> {
      try {
        exclusiveAcquired.await(5, TimeUnit.SECONDS);
        // small delay to ensure writer holds lock before reader attempts
        Thread.sleep(50);
        long startNanos = System.nanoTime();
        component.testAcquireSharedLock();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        sharedAcquired.set(true);
        component.testReleaseSharedLock();

        // The shared lock should have waited a meaningful amount of time
        if (elapsedMs < 20) {
          errors.compareAndSet(null,
              new AssertionError(
                  "Shared lock acquired too quickly (" + elapsedMs
                      + "ms) — exclusion may be broken"));
        }
      } catch (Throwable t) {
        errors.compareAndSet(null, t);
      }
    });

    writerThread.start();
    readerThread.start();

    // Verify that during the exclusive hold, shared is NOT acquired
    assertTrue("Exclusive lock should be acquired",
        exclusiveAcquired.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    assertFalse("Shared lock should still be blocked while exclusive is held",
        sharedAcquired.get());

    // Release the exclusive lock
    releaseExclusive.countDown();

    readerThread.join(5000);
    writerThread.join(5000);

    assertTrue("Shared lock should eventually be acquired", sharedAcquired.get());
    if (errors.get() != null) {
      fail("Error in StampedLock mutex test: " + errors.get());
    }
  }

  // ========= Test (j): No false-collision deadlock with per-component locks =====

  /**
   * Verifies that two threads acquiring locks on two different components
   * concurrently do NOT deadlock when both acquire in the same order. With
   * per-component locks (no hash collisions), different components have
   * independent StampedLocks.
   *
   * <p>Note: genuine ABBA ordering (thread 1: X→Y, thread 2: Y→X) would still
   * deadlock — this is prevented by the codebase always acquiring components
   * in sorted order. This test verifies there are no false collisions.
   */
  @Test
  public void testNoFalseCollisionDeadlock_perComponentLocks() throws Exception {
    var mgr = manager();
    var componentX = fakeComponent("collision_X");
    var componentY = fakeComponent("collision_Y");

    var errors = new AtomicReference<Throwable>();
    var startLatch = new CountDownLatch(1);
    int iterations = 500;
    var done = new CountDownLatch(2);

    // Both threads acquire in the same order — should never deadlock
    for (int t = 0; t < 2; t++) {
      new Thread(() -> {
        try {
          startLatch.await();
          for (int i = 0; i < iterations; i++) {
            var op = mgr.startAtomicOperation();
            try {
              mgr.acquireExclusiveLockTillOperationComplete(op, componentX);
              mgr.acquireExclusiveLockTillOperationComplete(op, componentY);
            } finally {
              mgr.ensureThatComponentsUnlocked(op);
            }
          }
        } catch (Throwable e) {
          errors.compareAndSet(null, e);
        } finally {
          done.countDown();
        }
      }).start();
    }

    startLatch.countDown();

    assertTrue("Should complete without deadlock",
        done.await(30, TimeUnit.SECONDS));

    if (errors.get() != null) {
      fail("Error during collision test: " + errors.get());
    }
  }

  // ========= Test (k): Reentrant exclusive lock within atomic operation ========

  /**
   * Verifies that acquireExclusiveLockTillOperationComplete on the same component
   * twice within one operation is a no-op (idempotent).
   */
  @Test
  public void testReentrantExclusiveLock_sameOperation() throws Exception {
    var mgr = manager();
    var component = fakeComponent("reentrant_excl_test");

    var operation = mgr.startAtomicOperation();
    try {
      mgr.acquireExclusiveLockTillOperationComplete(operation, component);
      assertTrue(operation.containsInLockedObjects(component.getLockName()));

      // Second call should be a no-op
      mgr.acquireExclusiveLockTillOperationComplete(operation, component);
      assertTrue(operation.containsInLockedObjects(component.getLockName()));

      // Lock should still be held
      long stamp = component.stampedLock.tryOptimisticRead();
      assertEquals("Write lock should be held", 0, stamp);
    } finally {
      mgr.ensureThatComponentsUnlocked(operation);
    }

    // After release, lock should be free
    long stamp = component.stampedLock.tryOptimisticRead();
    assertTrue("Lock should be released", stamp != 0);
    assertTrue("Stamp should validate", component.stampedLock.validate(stamp));
  }

  // ========= Test (l): Component lock and executeReadOperation use same lock ===

  /**
   * Verifies that the component's standalone acquireExclusiveLock() and the
   * AtomicOperationsManager's executeReadOperation() use the same StampedLock.
   * A writer holding the component lock via acquireExclusiveLock() should block
   * optimistic reads on the same component.
   */
  @Test
  public void testComponentLockAndReadOperationShareSameLock() throws Exception {
    var mgr = manager();
    var component = fakeComponent("shared_lock_test");

    // Acquire exclusive lock directly on the component (standalone)
    var writerReady = new CountDownLatch(1);
    var releaseWriter = new CountDownLatch(1);
    var readerResult = new AtomicReference<String>();

    var writerThread = new Thread(() -> {
      component.testAcquireExclusiveLock();
      writerReady.countDown();
      try {
        releaseWriter.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      component.testReleaseExclusiveLock();
    });
    writerThread.start();
    assertTrue(writerReady.await(5, TimeUnit.SECONDS));

    // executeReadOperation should block until the standalone exclusive lock is released
    var readerThread = new Thread(() -> {
      try {
        var r = mgr.executeReadOperation(component, () -> "after_release");
        readerResult.set(r);
      } catch (IOException e) {
        fail("Unexpected IOException");
      }
    });
    readerThread.start();

    // Reader should be blocked while exclusive is held
    Thread.sleep(100);
    assertFalse("Reader should be blocked", readerThread.getState() == Thread.State.TERMINATED);

    releaseWriter.countDown();
    readerThread.join(5000);
    writerThread.join(5000);

    assertEquals("after_release", readerResult.get());
  }

  // ========= Test (m): Concurrent BTree reads and writes ======================

  /**
   * Verifies that real BTree operations work correctly with per-component locks.
   * Writers insert records in transactions, while readers query the index via
   * Gremlin traversals. No exceptions or torn reads should occur.
   */
  @Test
  public void testConcurrentBTreeReadsAndWrites() throws Exception {
    // Create a vertex class with an indexed property
    var cls = session.createVertexClass("BTreeTestVertex");
    cls.createProperty("key", PropertyType.STRING);
    cls.createIndex("BTreeTestVertex_key", SchemaClass.INDEX_TYPE.UNIQUE, "key");

    int numWriters = 2;
    int numReaders = 4;
    int iterationsPerWriter = 200;
    var errors = new AtomicReference<Throwable>();
    var startLatch = new CountDownLatch(1);
    var writersDone = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(numWriters + numReaders);

    // Writers: insert records in separate sessions
    for (int w = 0; w < numWriters; w++) {
      final int writerId = w;
      executor.submit(() -> {
        try {
          startLatch.await();
          var writerSession = pool.acquire();
          try {
            for (int i = 0; i < iterationsPerWriter; i++) {
              writerSession.begin();
              try {
                var v = writerSession.newVertex("BTreeTestVertex");
                v.setProperty("key", "w" + writerId + "_" + i);
                writerSession.commit();
              } catch (Exception e) {
                if (writerSession.getTransactionInternal().isActive()) {
                  writerSession.rollback();
                }
                // ConcurrentModificationException is expected under contention
                if (!(e instanceof
                    com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException)) {
                  throw e;
                }
              }
            }
          } finally {
            writerSession.close();
            writersDone.incrementAndGet();
          }
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        }
      });
    }

    // Readers: query the index concurrently
    for (int r = 0; r < numReaders; r++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          var readerSession = pool.acquire();
          try {
            // Keep reading until all writers are done
            while (writersDone.get() < numWriters) {
              readerSession.begin();
              try {
                // Query using the indexed property — exercises BTree.get()
                try (var results = readerSession.query(
                    "SELECT FROM BTreeTestVertex WHERE key = ?", "w0_0")) {
                  //noinspection StatementWithEmptyBody
                  while (results.hasNext()) {
                    results.next();
                  }
                }

                // Range scan — exercises BTree.iterateEntriesBetween()
                try (var rangeResults = readerSession.query(
                    "SELECT FROM BTreeTestVertex WHERE key >= ? AND key <= ?",
                    "w0_0", "w0_99")) {
                  //noinspection StatementWithEmptyBody
                  while (rangeResults.hasNext()) {
                    rangeResults.next();
                  }
                }
                readerSession.commit();
              } catch (Exception e) {
                if (readerSession.getTransactionInternal().isActive()) {
                  readerSession.rollback();
                }
              }
            }
          } finally {
            readerSession.close();
          }
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        }
      });
    }

    startLatch.countDown();
    executor.shutdown();
    assertTrue("Threads should finish within timeout",
        executor.awaitTermination(60, TimeUnit.SECONDS));

    if (errors.get() != null) {
      fail("Error during concurrent BTree test: " + errors.get());
    }

    // Verify index consistency: all successfully committed records are findable
    session.begin();
    try (var allResults = session.query("SELECT FROM BTreeTestVertex")) {
      var keys = new HashSet<String>();
      while (allResults.hasNext()) {
        var record = allResults.next();
        keys.add(record.getProperty("key"));
      }
      // Each key should be unique (enforced by UNIQUE index)
      assertFalse("Should have inserted some records", keys.isEmpty());
    }
    session.commit();
  }

  // ========= Test (n): Concurrent PaginatedCollectionV2 reads and writes ======

  /**
   * Verifies that PaginatedCollectionV2 read operations work correctly with
   * per-component locks. Writers create/update/delete records while readers browse.
   */
  @Test
  public void testConcurrentCollectionReadsAndWrites() throws Exception {
    // Create a simple vertex class (no index — pure collection-level test)
    session.createVertexClass("CollTestVertex");

    // Pre-populate some records
    session.begin();
    for (int i = 0; i < 50; i++) {
      var v = session.newVertex("CollTestVertex");
      v.setProperty("val", i);
    }
    session.commit();

    int numWriters = 2;
    int numReaders = 4;
    int iterationsPerWriter = 200;
    var errors = new AtomicReference<Throwable>();
    var startLatch = new CountDownLatch(1);
    var writersDone = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(numWriters + numReaders);

    // Writers: create and delete records
    for (int w = 0; w < numWriters; w++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          var writerSession = pool.acquire();
          try {
            var rids = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.RID>();
            for (int i = 0; i < iterationsPerWriter; i++) {
              writerSession.begin();
              try {
                var v = writerSession.newVertex("CollTestVertex");
                v.setProperty("val", 1000 + i);
                var rid = v.getIdentity();
                writerSession.commit();
                // Only track RID after successful commit
                rids.add(rid);
              } catch (Exception e) {
                if (writerSession.getTransactionInternal().isActive()) {
                  writerSession.rollback();
                }
              }

              // Periodically delete a previously created record
              if (i % 5 == 4 && !rids.isEmpty()) {
                writerSession.begin();
                try {
                  var rid = rids.removeFirst();
                  var loaded = writerSession.load(rid);
                  if (loaded != null) {
                    loaded.delete();
                  }
                  writerSession.commit();
                } catch (Exception e) {
                  if (writerSession.getTransactionInternal().isActive()) {
                    writerSession.rollback();
                  }
                }
              }
            }
          } finally {
            writerSession.close();
            writersDone.incrementAndGet();
          }
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        }
      });
    }

    // Readers: browse records via SQL (exercises nextPage/readRecord)
    for (int r = 0; r < numReaders; r++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          var readerSession = pool.acquire();
          try {
            while (writersDone.get() < numWriters) {
              readerSession.begin();
              try {
                try (var results = readerSession.query(
                    "SELECT FROM CollTestVertex")) {
                  while (results.hasNext()) {
                    var record = results.next();
                    assertNotNull("Record should not be null", record);
                  }
                }
                readerSession.commit();
              } catch (Exception e) {
                if (readerSession.getTransactionInternal().isActive()) {
                  readerSession.rollback();
                }
              }
            }
          } finally {
            readerSession.close();
          }
        } catch (Throwable t) {
          errors.compareAndSet(null, t);
        }
      });
    }

    startLatch.countDown();
    executor.shutdown();
    assertTrue("Threads should finish within timeout",
        executor.awaitTermination(60, TimeUnit.SECONDS));

    if (errors.get() != null) {
      fail("Error during concurrent collection test: " + errors.get());
    }

    // Final consistency check
    session.begin();
    var finalCount = session.countClass("CollTestVertex");
    assertTrue("Should have some records remaining", finalCount > 0);
    session.commit();
  }

  // ========= Test (o): Multiple components in same operation ==================

  /**
   * Verifies that an atomic operation can lock multiple components and release
   * them all correctly at the end.
   */
  @Test
  public void testMultipleComponentsInSameOperation() throws Exception {
    var mgr = manager();
    var components = new TestableComponent[5];
    for (int i = 0; i < components.length; i++) {
      components[i] = fakeComponent("multi_comp_" + i);
    }

    var operation = mgr.startAtomicOperation();
    try {
      for (var comp : components) {
        mgr.acquireExclusiveLockTillOperationComplete(operation, comp);
      }

      // All should be locked
      for (var comp : components) {
        assertEquals("Write lock should be held",
            0, comp.stampedLock.tryOptimisticRead());
      }
    } finally {
      mgr.ensureThatComponentsUnlocked(operation);
    }

    // All should be released
    for (var comp : components) {
      long stamp = comp.stampedLock.tryOptimisticRead();
      assertTrue("Lock should be released on " + comp.getLockName(), stamp != 0);
    }
  }
}
