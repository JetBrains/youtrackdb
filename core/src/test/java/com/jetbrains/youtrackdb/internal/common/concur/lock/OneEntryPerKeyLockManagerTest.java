package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import org.junit.Test;

/**
 * Tests for {@link OneEntryPerKeyLockManager} covering lock acquisition/release by key,
 * lock reuse from cache, lock count tracking, eviction, timeout, disabled mode, and
 * multi-threaded concurrent lock acquisition on different keys.
 */
public class OneEntryPerKeyLockManagerTest {

  /** Helper to cast Lock to CountableLockWrapper for inspection. */
  private static OneEntryPerKeyLockManager.CountableLockWrapper wrapper(Lock lock) {
    return (OneEntryPerKeyLockManager.CountableLockWrapper) lock;
  }

  // --- Basic acquire/release ---

  /** Acquire and release exclusive lock by key. */
  @Test
  public void testExclusiveLockAcquireRelease() {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    var lock = mgr.acquireExclusiveLock("key1");
    assertNotNull(lock);
    assertEquals("Lock count should be 1 after acquire", 1, wrapper(lock).getLockCount());
    mgr.releaseExclusiveLock("key1");
    assertEquals("Lock count should be 0 after release", 0, wrapper(lock).getLockCount());
  }

  /** Acquire and release shared lock by key. */
  @Test
  public void testSharedLockAcquireRelease() {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    var lock = mgr.acquireSharedLock("key1");
    assertNotNull(lock);
    assertEquals(1, wrapper(lock).getLockCount());
    mgr.releaseSharedLock("key1");
    assertEquals(0, wrapper(lock).getLockCount());
  }

  // --- Lock reuse from cache ---

  /**
   * Acquiring the same key twice returns locks backed by the same CountableLock
   * (reference count incremented).
   */
  @Test
  public void testLockReuseFromCache() {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    var lock1 = mgr.acquireExclusiveLock("key1");
    // Same thread can acquire the write lock reentrantly because it's ReentrantReadWriteLock
    var lock2 = mgr.acquireExclusiveLock("key1");
    assertEquals("Lock count should be 2 after double acquire",
        2, wrapper(lock2).getLockCount());
    lock2.unlock();
    lock1.unlock();
  }

  // --- getCountCurrentLocks ---

  /** getCountCurrentLocks reflects the number of distinct keys in the map. */
  @Test
  public void testGetCountCurrentLocks() {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    assertEquals(0, mgr.getCountCurrentLocks());
    var lock1 = mgr.acquireExclusiveLock("key1");
    assertEquals(1, mgr.getCountCurrentLocks());
    var lock2 = mgr.acquireExclusiveLock("key2");
    assertEquals(2, mgr.getCountCurrentLocks());
    lock1.unlock();
    lock2.unlock();
  }

  // --- Disabled mode ---

  /** When disabled (enabled=false), acquireLock returns null and releaseLock is a no-op. */
  @Test
  public void testDisabledModeReturnsNull() {
    var mgr = new OneEntryPerKeyLockManager<String>(false, -1, 100);
    var lock = mgr.acquireLock("key1", OneEntryPerKeyLockManager.LOCK.EXCLUSIVE);
    assertNull("Disabled manager should return null", lock);
    // Release should not throw
    mgr.releaseExclusiveLock("key1");
  }

  // --- Timeout on contended lock ---

  /** With a short timeout, acquiring a contended exclusive lock throws LockException. */
  @Test(timeout = 10_000)
  public void testTimeoutOnContendedLock() throws Exception {
    var mgr = new OneEntryPerKeyLockManager<String>(true, 200, 100);
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = new Thread(() -> {
      mgr.acquireExclusiveLock("key1");
      lockHeld.countDown();
      try {
        canRelease.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      mgr.releaseExclusiveLock("key1");
    });
    holder.start();
    assertTrue(lockHeld.await(5, TimeUnit.SECONDS));

    try {
      mgr.acquireExclusiveLock("key1");
      fail("Should throw LockException on timeout");
    } catch (LockException e) {
      assertTrue("Message should mention timeout",
          e.getMessage().contains("Timeout"));
    } finally {
      canRelease.countDown();
      holder.join(5_000);
    }
  }

  // --- Release of non-acquired lock ---

  /**
   * Releasing a lock that was never acquired throws LockException (only when
   * the manager is enabled and the key is not in the map).
   */
  @Test(expected = LockException.class)
  public void testReleaseNonAcquiredLockThrows() {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    mgr.releaseLock(Thread.currentThread(), "nonexistent",
        OneEntryPerKeyLockManager.LOCK.EXCLUSIVE);
  }

  // --- Batch lock acquisition ---

  /** Batch exclusive lock for T array — verify distinct keys are locked. */
  @Test
  public void testBatchExclusiveLockArray() {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    var locks = mgr.acquireExclusiveLocksInBatch("a", "b", "c");
    assertNotNull(locks);
    assertEquals(3, locks.length);
    for (var lock : locks) {
      assertNotNull("Each batch lock should be non-null", lock);
    }
    assertEquals("Should have 3 distinct keys locked", 3, mgr.getCountCurrentLocks());
    for (var lock : locks) {
      lock.unlock();
    }
  }

  /** Batch shared lock for T array — verify distinct keys are locked. */
  @Test
  public void testBatchSharedLockArray() {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    var locks = mgr.acquireSharedLocksInBatch("a", "b");
    assertNotNull(locks);
    assertEquals(2, locks.length);
    for (var lock : locks) {
      assertNotNull("Each batch lock should be non-null", lock);
    }
    assertEquals("Should have 2 distinct keys locked", 2, mgr.getCountCurrentLocks());
    for (var lock : locks) {
      lock.unlock();
    }
  }

  /** Batch exclusive lock for null/empty array returns null. */
  @Test
  public void testBatchExclusiveLockNullArray() {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    assertNull(mgr.acquireExclusiveLocksInBatch((String[]) null));
    assertNull(mgr.acquireExclusiveLocksInBatch(new String[0]));
  }

  // --- Multi-threaded: concurrent locks on different keys ---

  /** Different keys can be locked concurrently by different threads without blocking. */
  @Test(timeout = 10_000)
  public void testConcurrentLocksOnDifferentKeys() throws Exception {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    int threadCount = 4;
    var allAcquired = new CountDownLatch(threadCount);
    var canRelease = new CountDownLatch(1);

    var threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      final var key = "key-" + i;
      threads[i] = new Thread(() -> {
        mgr.acquireExclusiveLock(key);
        allAcquired.countDown();
        try {
          canRelease.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        mgr.releaseExclusiveLock(key);
      });
      threads[i].start();
    }

    assertTrue("All threads should acquire locks on different keys concurrently",
        allAcquired.await(5, TimeUnit.SECONDS));

    canRelease.countDown();
    for (var t : threads) {
      t.join(5_000);
    }
  }

  /** Exclusive lock on the same key blocks another thread. */
  @Test(timeout = 10_000)
  public void testExclusiveLockBlocksAnotherThread() throws Exception {
    var mgr = new OneEntryPerKeyLockManager<String>(true, -1, 100);
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);
    var secondAcquired = new AtomicBoolean(false);

    var holder = new Thread(() -> {
      mgr.acquireExclusiveLock("key1");
      lockHeld.countDown();
      try {
        canRelease.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      mgr.releaseExclusiveLock("key1");
    });
    holder.start();
    assertTrue(lockHeld.await(5, TimeUnit.SECONDS));

    var contender = new Thread(() -> {
      mgr.acquireExclusiveLock("key1");
      secondAcquired.set(true);
      mgr.releaseExclusiveLock("key1");
    });
    contender.start();

    Thread.sleep(200);
    assertFalse("Second thread should be blocked",
        secondAcquired.get());

    canRelease.countDown();
    contender.join(5_000);
    holder.join(5_000);
    assertTrue("Second thread should acquire after first released",
        secondAcquired.get());
  }
}
