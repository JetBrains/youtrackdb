package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurableComponent;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;
import org.junit.Test;

/**
 * Tests for the {@link AtomicOperationsManager#executeReadOperation} template method
 * and the striped StampedLock write lock dedup logic.
 */
public class ExecuteReadOperationTest extends DbTestBase {

  private AtomicOperationsManager manager() {
    return session.getStorage().getAtomicOperationsManager();
  }

  private DurableComponent fakeComponent(String lockName) {
    return new DurableComponent(session.getStorage(), "test", ".tst", lockName) {};
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
   * When a writer holds the stripe's write lock, tryOptimisticRead returns 0,
   * so the optimistic path is skipped entirely and executeReadOperation goes
   * straight to blocking readLock. Once the writer releases, the blocking read
   * proceeds.
   */
  @Test
  public void testOptimisticReadFails_writerHoldsLock_blockingRetry()
      throws Exception {
    var mgr = manager();
    var component = fakeComponent("testComponent_b");
    int stripe = mgr.stripeIndex(component.getLockName());

    var lockField = AtomicOperationsManager.class.getDeclaredField("componentLocks");
    lockField.setAccessible(true);
    var locks = (StampedLock[]) lockField.get(mgr);
    var lock = locks[stripe];

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
    int stripe = mgr.stripeIndex(component.getLockName());

    var lockField = AtomicOperationsManager.class.getDeclaredField("componentLocks");
    lockField.setAccessible(true);
    var locks = (StampedLock[]) lockField.get(mgr);
    var lock = locks[stripe];

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

  // ==================== Test (d3): Write-lock reentrant fast path ==================

  /**
   * When a thread holds the write lock on a stripe (via an active atomic operation)
   * and then calls executeReadOperation on a component mapping to the same stripe,
   * the fast path should detect the ownership and execute the action directly without
   * trying to acquire a read lock (which would deadlock since StampedLock is
   * non-reentrant).
   */
  @Test
  public void testWriteLockReentrantFastPath_noDeadlock() throws Exception {
    var mgr = manager();
    var component = fakeComponent("reentrant_component");

    var operation = mgr.startAtomicOperation();
    try {
      // Acquire write lock on the component's stripe
      mgr.acquireExclusiveLockTillOperationComplete(operation, component.getLockName());

      // Now call executeReadOperation on the same stripe — must not deadlock
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

  // ==================== Test (e): Write lock stripe dedup ==========================

  @Test
  public void testWriteLockStripeDedup_sameStripe() throws Exception {
    var mgr = manager();
    var lockField = AtomicOperationsManager.class.getDeclaredField("componentLocks");
    lockField.setAccessible(true);
    var locks = (StampedLock[]) lockField.get(mgr);

    String name1 = "component_A";
    String name2 = null;
    int targetStripe = mgr.stripeIndex(name1);
    for (int i = 0; i < 100_000; i++) {
      String candidate = "component_" + i;
      if (!candidate.equals(name1)
          && mgr.stripeIndex(candidate) == targetStripe) {
        name2 = candidate;
        break;
      }
    }
    assertNotNull("Should find a colliding name", name2);

    var operation = mgr.startAtomicOperation();
    try {
      mgr.acquireExclusiveLockTillOperationComplete(operation, name1);
      assertTrue(operation.containsInLockedObjects(name1));
      assertTrue(operation.containsLockedStripe(targetStripe));

      mgr.acquireExclusiveLockTillOperationComplete(operation, name2);
      assertTrue(operation.containsInLockedObjects(name2));

      long stamp = locks[targetStripe].tryOptimisticRead();
      assertEquals("Write lock should be held, stamp must be 0", 0, stamp);
    } finally {
      mgr.ensureThatComponentsUnlocked(operation);
    }

    long stamp = locks[targetStripe].tryOptimisticRead();
    assertTrue("Lock should be released", stamp != 0);
    assertTrue("Stamp should validate", locks[targetStripe].validate(stamp));
  }

  // ==================== Test (f): Concurrent readers and writers stress test =========

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
                  operation, component.getLockName());
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
}
