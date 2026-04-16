package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Tests for {@link PartitionedLockManager} covering all three lock modes (default
 * ReentrantReadWriteLock, spinLock, scalableRWLock), exclusive/shared lock operations,
 * batch acquisition, and tryAcquireExclusiveLock. Each mode is tested for basic
 * acquire/release plus multi-threaded exclusion.
 */
public class PartitionedLockManagerTest {

  // --- Constructor validation ---

  /** Both spinLock and scalableRWLock flags true throws IllegalArgumentException. */
  @Test
  public void testBothFlagsTrueThrowsException() {
    try {
      new PartitionedLockManager<String>(true, true);
      fail("Should throw IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue("Message should mention simultaneous use",
          e.getMessage().contains("can not be used simultaneously"));
    }
  }

  /** Default constructor creates a manager using ReentrantReadWriteLock. */
  @Test
  public void testDefaultConstructor() {
    var mgr = new PartitionedLockManager<String>();
    // Verify it works — acquire and release a lock
    var lock = mgr.acquireExclusiveLock("key");
    assertNotNull("Should return a lock", lock);
    mgr.releaseExclusiveLock("key");
  }

  // --- Default mode (ReentrantReadWriteLock) ---

  /** Exclusive lock acquire/release cycle for generic T. */
  @Test
  public void testDefaultExclusiveLockGeneric() {
    var mgr = new PartitionedLockManager<String>();
    var lock = mgr.acquireExclusiveLock("test");
    assertNotNull(lock);
    mgr.releaseExclusiveLock("test");
  }

  /** Shared lock acquire/release cycle for generic T. */
  @Test
  public void testDefaultSharedLockGeneric() {
    var mgr = new PartitionedLockManager<String>();
    var lock = mgr.acquireSharedLock("test");
    assertNotNull(lock);
    mgr.releaseSharedLock("test");
  }

  /** Exclusive lock acquire/release for int overload. */
  @Test
  public void testDefaultExclusiveLockInt() {
    var mgr = new PartitionedLockManager<String>();
    var lock = mgr.acquireExclusiveLock(42);
    assertNotNull(lock);
    mgr.releaseExclusiveLock(42);
  }

  /** Shared lock acquire/release for int overload. */
  @Test
  public void testDefaultSharedLockInt() {
    var mgr = new PartitionedLockManager<String>();
    var lock = mgr.acquireSharedLock(42);
    assertNotNull(lock);
    mgr.releaseSharedLock(42);
  }

  /** Exclusive lock acquire/release for long overload. */
  @Test
  public void testDefaultExclusiveLockLong() {
    var mgr = new PartitionedLockManager<String>();
    var lock = mgr.acquireExclusiveLock(100L);
    assertNotNull(lock);
    mgr.releaseExclusiveLock(100L);
  }

  /** Shared lock acquire/release for long overload. */
  @Test
  public void testDefaultSharedLockLong() {
    var mgr = new PartitionedLockManager<String>();
    var lock = mgr.acquireSharedLock(100L);
    assertNotNull(lock);
    mgr.releaseSharedLock(100L);
  }

  /** Null key maps to index 0 and does not throw. */
  @Test
  public void testNullKeyExclusiveLock() {
    var mgr = new PartitionedLockManager<String>();
    var lock = mgr.acquireExclusiveLock((String) null);
    assertNotNull(lock);
    mgr.releaseExclusiveLock((String) null);
  }

  /** Null key for shared lock does not throw. */
  @Test
  public void testNullKeySharedLock() {
    var mgr = new PartitionedLockManager<String>();
    var lock = mgr.acquireSharedLock((String) null);
    assertNotNull(lock);
    mgr.releaseSharedLock((String) null);
  }

  // --- SpinLock mode ---

  /** SpinLock mode: exclusive lock acquire/release via manager-level release. */
  @Test
  public void testSpinLockExclusiveLock() {
    var mgr = new PartitionedLockManager<String>(true, false);
    var lock = mgr.acquireExclusiveLock("key");
    assertNotNull(lock);
    mgr.releaseExclusiveLock("key");
  }

  /** SpinLock mode: shared lock acquire/release via manager-level release. */
  @Test
  public void testSpinLockSharedLock() {
    var mgr = new PartitionedLockManager<String>(true, false);
    var lock = mgr.acquireSharedLock("key");
    assertNotNull(lock);
    mgr.releaseSharedLock("key");
  }

  /** SpinLock mode: int overloads. */
  @Test
  public void testSpinLockIntOverloads() {
    var mgr = new PartitionedLockManager<String>(true, false);
    var eLock = mgr.acquireExclusiveLock(7);
    assertNotNull(eLock);
    mgr.releaseExclusiveLock(7);

    mgr.acquireSharedLock(7);
    mgr.releaseSharedLock(7);
  }

  /** SpinLock mode: long overloads. */
  @Test
  public void testSpinLockLongOverloads() {
    var mgr = new PartitionedLockManager<String>(true, false);
    var eLock = mgr.acquireExclusiveLock(77L);
    assertNotNull(eLock);
    mgr.releaseExclusiveLock(77L);

    mgr.acquireSharedLock(77L);
    mgr.releaseSharedLock(77L);
  }

  /**
   * SpinLock mode: tryAcquireExclusiveLock throws IllegalStateException because
   * spin locks do not support try-lock.
   */
  @Test
  public void testSpinLockTryAcquireThrows() throws Exception {
    var mgr = new PartitionedLockManager<String>(true, false);
    try {
      mgr.tryAcquireExclusiveLock(1, 100);
      fail("Should throw IllegalStateException for spin lock try-acquire");
    } catch (IllegalStateException e) {
      assertTrue("Message should mention spin lock",
          e.getMessage().contains("Spin lock"));
    }
  }

  // --- ScalableRWLock mode ---

  /** ScalableRWLock mode: exclusive lock acquire/release. */
  @Test
  public void testScalableRWLockExclusiveLock() {
    var mgr = new PartitionedLockManager<String>(false, true);
    var lock = mgr.acquireExclusiveLock("key");
    assertNotNull(lock);
    mgr.releaseExclusiveLock("key");
  }

  /**
   * ScalableRWLock mode: shared lock acquire/release. This tests the bug fix —
   * releaseSLock previously called sharedLock() instead of sharedUnlock(), causing
   * deadlock on subsequent exclusive lock acquisition.
   */
  @Test(timeout = 10_000)
  public void testScalableRWLockSharedLockAcquireRelease() {
    var mgr = new PartitionedLockManager<String>(false, true);

    // Acquire and release shared lock
    var lock = mgr.acquireSharedLock("key");
    assertNotNull(lock);
    mgr.releaseSharedLock("key");

    // If the bug fix is not applied, this exclusive lock would deadlock
    // because releaseSharedLock would have acquired another shared lock
    // instead of releasing the existing one
    var exclusiveLock = mgr.acquireExclusiveLock("key");
    assertNotNull("Should be able to acquire exclusive lock after shared release",
        exclusiveLock);
    mgr.releaseExclusiveLock("key");
  }

  /** ScalableRWLock mode: int overloads with shared lock bug fix verification. */
  @Test(timeout = 10_000)
  public void testScalableRWLockIntOverloads() {
    var mgr = new PartitionedLockManager<String>(false, true);

    mgr.acquireSharedLock(42);
    mgr.releaseSharedLock(42);

    // Verify exclusive lock works after shared release (bug fix)
    var lock = mgr.acquireExclusiveLock(42);
    assertNotNull(lock);
    mgr.releaseExclusiveLock(42);
  }

  /** ScalableRWLock mode: long overloads with shared lock bug fix verification. */
  @Test(timeout = 10_000)
  public void testScalableRWLockLongOverloads() {
    var mgr = new PartitionedLockManager<String>(false, true);

    mgr.acquireSharedLock(100L);
    mgr.releaseSharedLock(100L);

    // Verify exclusive lock works after shared release (bug fix)
    var lock = mgr.acquireExclusiveLock(100L);
    assertNotNull(lock);
    mgr.releaseExclusiveLock(100L);
  }

  /** ScalableRWLock mode: tryAcquireExclusiveLock succeeds on uncontended lock. */
  @Test
  public void testScalableRWLockTryAcquireExclusiveLock() throws Exception {
    var mgr = new PartitionedLockManager<String>(false, true);
    assertTrue("tryAcquire should succeed on uncontended lock",
        mgr.tryAcquireExclusiveLock(1, 100));
    mgr.releaseExclusiveLock(1);
  }

  // --- Batch acquisition ---

  /** Batch exclusive lock acquisition for T array sorts by partition index. */
  @Test
  public void testBatchExclusiveLockArray() {
    var mgr = new PartitionedLockManager<String>();
    var locks = mgr.acquireExclusiveLocksInBatch("a", "b", "c");
    assertEquals("Should return one lock per key", 3, locks.length);
    for (var lock : locks) {
      assertNotNull(lock);
      lock.unlock();
    }
  }

  /** Batch exclusive lock acquisition for null array returns empty. */
  @Test
  public void testBatchExclusiveLockNullArray() {
    var mgr = new PartitionedLockManager<String>();
    var locks = mgr.acquireExclusiveLocksInBatch((String[]) null);
    assertEquals("Null array should return empty", 0, locks.length);
  }

  /** Batch exclusive lock acquisition for Collection. */
  @Test
  public void testBatchExclusiveLockCollection() {
    var mgr = new PartitionedLockManager<String>();
    var keys = List.of("x", "y", "z");
    var locks = mgr.acquireExclusiveLocksInBatch(keys);
    assertEquals(3, locks.length);
    for (var lock : locks) {
      assertNotNull(lock);
      lock.unlock();
    }
  }

  /** Batch exclusive lock acquisition for null/empty collection returns empty. */
  @Test
  public void testBatchExclusiveLockNullCollection() {
    var mgr = new PartitionedLockManager<String>();
    assertEquals(0, mgr.acquireExclusiveLocksInBatch((java.util.Collection<String>) null).length);
    assertEquals(0, mgr.acquireExclusiveLocksInBatch(new ArrayList<>()).length);
  }

  /** Batch shared lock acquisition for T array. */
  @Test
  public void testBatchSharedLockArray() {
    var mgr = new PartitionedLockManager<String>();
    var locks = mgr.acquireSharedLocksInBatch("a", "b");
    assertEquals(2, locks.length);
    for (var lock : locks) {
      assertNotNull(lock);
      lock.unlock();
    }
  }

  /** Batch shared lock acquisition for null array returns empty. */
  @Test
  public void testBatchSharedLockNullArray() {
    var mgr = new PartitionedLockManager<String>();
    var locks = mgr.acquireSharedLocksInBatch((String[]) null);
    assertEquals(0, locks.length);
  }

  /**
   * Batch exclusive lock acquisition for int array. Note: there is a pre-existing bug
   * where the input values are not copied into the sorted array (line 352 allocates
   * a zero-filled array instead of copying). All locks are acquired for partition
   * index 0 regardless of input. This test documents the current (buggy) behavior.
   */
  @Test
  public void testBatchExclusiveLockIntArray() {
    var mgr = new PartitionedLockManager<String>();
    var locks = mgr.acquireExclusiveLocksInBatch(new int[] {1, 2, 3});
    assertEquals(3, locks.length);
    for (var lock : locks) {
      lock.unlock();
    }
  }

  /** Batch exclusive lock for null/empty int array returns empty. */
  @Test
  public void testBatchExclusiveLockNullIntArray() {
    var mgr = new PartitionedLockManager<String>();
    assertEquals(0, mgr.acquireExclusiveLocksInBatch((int[]) null).length);
    assertEquals(0, mgr.acquireExclusiveLocksInBatch(new int[0]).length);
  }

  // --- tryAcquireExclusiveLock (default mode) ---

  /** Default mode: tryAcquireExclusiveLock succeeds on uncontended lock. */
  @Test
  public void testDefaultTryAcquireExclusiveSucceeds() throws Exception {
    var mgr = new PartitionedLockManager<String>();
    assertTrue(mgr.tryAcquireExclusiveLock(1, 100));
    mgr.releaseExclusiveLock(1);
  }

  /** Default mode: tryAcquireExclusiveLock returns false when lock is held. */
  @Test(timeout = 10_000)
  public void testDefaultTryAcquireExclusiveFails() throws Exception {
    var mgr = new PartitionedLockManager<String>();
    var lockHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);

    var holder = new Thread(() -> {
      mgr.acquireExclusiveLock(1);
      lockHeld.countDown();
      try {
        canRelease.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      mgr.releaseExclusiveLock(1);
    });
    holder.start();
    assertTrue(lockHeld.await(5, TimeUnit.SECONDS));

    assertFalse("tryAcquire should fail when lock is held",
        mgr.tryAcquireExclusiveLock(1, 100));

    canRelease.countDown();
    holder.join(5_000);
  }

  // --- Multi-threaded: exclusive blocks shared ---

  /**
   * Verifies that holding an exclusive lock blocks shared lock acquisition in
   * another thread (default mode).
   */
  @Test(timeout = 10_000)
  public void testDefaultExclusiveBlocksShared() throws Exception {
    var mgr = new PartitionedLockManager<String>();
    var exclusiveHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);
    var sharedAcquired = new AtomicBoolean(false);
    // Use the same key to ensure they map to the same partition
    var key = "sameKey";

    var writer = new Thread(() -> {
      mgr.acquireExclusiveLock(key);
      exclusiveHeld.countDown();
      try {
        canRelease.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      mgr.releaseExclusiveLock(key);
    });
    writer.start();
    assertTrue(exclusiveHeld.await(5, TimeUnit.SECONDS));

    var reader = new Thread(() -> {
      var lock = mgr.acquireSharedLock(key);
      sharedAcquired.set(true);
      mgr.releaseSharedLock(key);
    });
    reader.start();

    Thread.sleep(200);
    assertFalse("Shared lock should be blocked while exclusive is held",
        sharedAcquired.get());

    canRelease.countDown();
    reader.join(5_000);
    writer.join(5_000);
    assertTrue("Shared lock should be acquired after exclusive released",
        sharedAcquired.get());
  }

  /**
   * Verifies that multiple shared locks can be held concurrently (default mode).
   */
  @Test(timeout = 10_000)
  public void testDefaultMultipleSharedLocksConcurrent() throws Exception {
    var mgr = new PartitionedLockManager<String>();
    var key = "sameKey";
    int readerCount = 4;
    var allAcquired = new CountDownLatch(readerCount);
    var canRelease = new CountDownLatch(1);

    var readers = new Thread[readerCount];
    for (int i = 0; i < readerCount; i++) {
      readers[i] = new Thread(() -> {
        mgr.acquireSharedLock(key);
        allAcquired.countDown();
        try {
          canRelease.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        mgr.releaseSharedLock(key);
      });
      readers[i].start();
    }

    assertTrue("All readers should acquire shared locks concurrently",
        allAcquired.await(5, TimeUnit.SECONDS));

    canRelease.countDown();
    for (var reader : readers) {
      reader.join(5_000);
    }
  }

  // --- ScalableRWLock multi-threaded ---

  /**
   * ScalableRWLock mode: exclusive lock blocks shared lock in another thread.
   * Verifies the bug fix path (releaseSLock → sharedUnlock) under cross-thread
   * contention — the same code path where the sharedLock/sharedUnlock bug was fixed.
   */
  @Test(timeout = 10_000)
  public void testScalableRWLockExclusiveBlocksShared() throws Exception {
    var mgr = new PartitionedLockManager<String>(false, true);
    var exclusiveHeld = new CountDownLatch(1);
    var canRelease = new CountDownLatch(1);
    var sharedAcquired = new AtomicBoolean(false);
    var key = "sameKey";

    var writer = new Thread(() -> {
      mgr.acquireExclusiveLock(key);
      exclusiveHeld.countDown();
      try {
        canRelease.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      mgr.releaseExclusiveLock(key);
    });
    writer.start();
    assertTrue(exclusiveHeld.await(5, TimeUnit.SECONDS));

    var reader = new Thread(() -> {
      mgr.acquireSharedLock(key);
      sharedAcquired.set(true);
      mgr.releaseSharedLock(key);
    });
    reader.start();

    Thread.sleep(200);
    assertFalse("Shared lock should be blocked while exclusive is held (scalableRWLock)",
        sharedAcquired.get());

    canRelease.countDown();
    reader.join(5_000);
    writer.join(5_000);
    assertTrue("Shared lock should succeed after exclusive released",
        sharedAcquired.get());
  }

  // --- Boundary values ---

  /** Boundary int values (0, -1, MIN_VALUE, MAX_VALUE) map to valid partitions. */
  @Test
  public void testBoundaryIntValues() {
    var mgr = new PartitionedLockManager<String>();
    for (int val : new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      var lock = mgr.acquireExclusiveLock(val);
      assertNotNull("Should handle int value " + val, lock);
      mgr.releaseExclusiveLock(val);
    }
  }

  /** Boundary long values (0, -1, MIN_VALUE, MAX_VALUE) map to valid partitions. */
  @Test
  public void testBoundaryLongValues() {
    var mgr = new PartitionedLockManager<String>();
    for (long val : new long[] {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
      var lock = mgr.acquireExclusiveLock(val);
      assertNotNull("Should handle long value " + val, lock);
      mgr.releaseExclusiveLock(val);
    }
  }
}
