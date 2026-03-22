package com.jetbrains.youtrackdb.internal.common.concur.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Tests for {@link SharedResourceAbstract} locking behavior after the StampedLock to
 * ReentrantReadWriteLock migration.
 *
 * <p>Verifies shared/exclusive mutual exclusion, write-to-read downgrade short-circuit,
 * and per-component lock isolation.
 */
public class SharedResourceAbstractTest {

  /**
   * Verifies that the shared lock blocks while another thread holds the exclusive lock
   * on the same component.
   */
  @Test
  public void testSharedLockBlockedByExclusiveLock() throws Exception {
    var component = new TestResource();

    var exclusiveAcquired = new CountDownLatch(1);
    var releaseExclusive = new CountDownLatch(1);
    var sharedAcquired = new AtomicBoolean(false);

    var writer = new Thread(() -> {
      component.testAcquireExclusiveLock();
      exclusiveAcquired.countDown();
      try {
        releaseExclusive.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      component.testReleaseExclusiveLock();
    });

    var reader = new Thread(() -> {
      try {
        exclusiveAcquired.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      component.testAcquireSharedLock();
      sharedAcquired.set(true);
      component.testReleaseSharedLock();
    });

    writer.start();
    reader.start();

    assertTrue("Exclusive lock should be acquired", exclusiveAcquired.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);
    assertFalse(
        "Shared lock should be blocked while exclusive is held", sharedAcquired.get());

    releaseExclusive.countDown();
    reader.join(5000);
    writer.join(5000);
    assertTrue("Shared lock should eventually be acquired", sharedAcquired.get());
  }

  /**
   * Verifies that acquireSharedLock() does not deadlock when the current thread already
   * holds the exclusive lock. This exercises the write-to-read downgrade short-circuit
   * in acquireSharedLock().
   */
  @Test
  public void testWriteToReadDowngradeDoesNotDeadlock() {
    var component = new TestResource();

    component.testAcquireExclusiveLock();
    try {
      // Must not deadlock — RRWL does not allow read lock acquisition while the
      // same thread holds the write lock unless the short-circuit is in place.
      component.testAcquireSharedLock();
      component.testReleaseSharedLock();
    } finally {
      component.testReleaseExclusiveLock();
    }
    // If we reach here, no deadlock occurred.
  }

  /**
   * Verifies that two different components have independent locks — acquiring the
   * exclusive lock on one does not block the shared lock on the other.
   */
  @Test
  public void testPerComponentLockIsolation() throws Exception {
    var componentA = new TestResource();
    var componentB = new TestResource();

    var exclusiveAcquired = new CountDownLatch(1);
    var sharedAcquired = new AtomicBoolean(false);
    var releaseExclusive = new CountDownLatch(1);

    // Hold exclusive lock on component A
    var writer = new Thread(() -> {
      componentA.testAcquireExclusiveLock();
      exclusiveAcquired.countDown();
      try {
        releaseExclusive.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      componentA.testReleaseExclusiveLock();
    });

    writer.start();
    assertTrue(exclusiveAcquired.await(5, TimeUnit.SECONDS));

    // Shared lock on component B should succeed immediately
    componentB.testAcquireSharedLock();
    sharedAcquired.set(true);
    componentB.testReleaseSharedLock();

    assertTrue(
        "Shared lock on different component should not be blocked",
        sharedAcquired.get());

    releaseExclusive.countDown();
    writer.join(5000);
  }

  /**
   * Verifies that multiple threads can hold the shared lock concurrently.
   */
  @Test
  public void testMultipleConcurrentSharedLocks() throws Exception {
    var component = new TestResource();
    int readerCount = 4;
    var allAcquired = new CountDownLatch(readerCount);
    var allCanRelease = new CountDownLatch(1);
    var successCount = new java.util.concurrent.atomic.AtomicInteger(0);

    for (int i = 0; i < readerCount; i++) {
      new Thread(() -> {
        component.testAcquireSharedLock();
        allAcquired.countDown();
        try {
          allCanRelease.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        successCount.incrementAndGet();
        component.testReleaseSharedLock();
      }).start();
    }

    // All readers should acquire the shared lock concurrently
    assertTrue(
        "All readers should acquire shared lock",
        allAcquired.await(5, TimeUnit.SECONDS));
    allCanRelease.countDown();

    Thread.sleep(200);
    assertEquals(
        "All readers should have completed", readerCount, successCount.get());
  }

  /**
   * Verifies that isExclusiveOwner() returns true on the owning thread and false
   * on other threads.
   */
  @Test
  public void testIsExclusiveOwner() throws Exception {
    var component = new TestResource();

    assertFalse(component.isExclusiveOwner());

    component.testAcquireExclusiveLock();
    assertTrue(component.isExclusiveOwner());

    var otherThreadResult = new AtomicBoolean(true);
    var latch = new CountDownLatch(1);
    new Thread(() -> {
      otherThreadResult.set(component.isExclusiveOwner());
      latch.countDown();
    }).start();

    latch.await(5, TimeUnit.SECONDS);
    assertFalse("Other thread should not be exclusive owner", otherThreadResult.get());

    component.testReleaseExclusiveLock();
    assertFalse(component.isExclusiveOwner());
  }

  /**
   * Concrete subclass exposing the protected lock methods for testing.
   */
  private static class TestResource extends SharedResourceAbstract {
    void testAcquireSharedLock() {
      acquireSharedLock();
    }

    void testReleaseSharedLock() {
      releaseSharedLock();
    }

    void testAcquireExclusiveLock() {
      acquireExclusiveLock();
    }

    void testReleaseExclusiveLock() {
      releaseExclusiveLock();
    }
  }
}
