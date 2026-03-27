package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for OptimisticReadScope — the multi-page stamp tracker used by optimistic reads.
 * Covers: record/validate happy path, validateOrThrow on invalid stamp, validateLastOrThrow,
 * reset clears state, grow on overflow, empty scope validates, and cross-thread stamp
 * invalidation via PageFrame exclusive lock (review finding R2).
 */
public class OptimisticReadScopeTest {

  private DirectMemoryAllocator allocator;
  private PageFramePool pool;

  @Before
  public void setUp() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
    allocator = new DirectMemoryAllocator();
    pool = new PageFramePool(4096, allocator, 16);
  }

  @After
  public void tearDown() {
    pool.clear();
    allocator.checkMemoryLeaks();
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testRecordAndValidateHappyPath() {
    // Records multiple frames with valid stamps and validates all succeed.
    var scope = new OptimisticReadScope();
    var frame1 = pool.acquire(true, Intention.TEST);
    var frame2 = pool.acquire(true, Intention.TEST);

    long stamp1 = frame1.tryOptimisticRead();
    long stamp2 = frame2.tryOptimisticRead();

    scope.record(frame1, stamp1);
    scope.record(frame2, stamp2);

    assertEquals(2, scope.count());
    // Should not throw — all stamps are still valid
    scope.validateOrThrow();

    releaseFrame(frame1);
    releaseFrame(frame2);
  }

  @Test(expected = OptimisticReadFailedException.class)
  public void testValidateOrThrowOnInvalidStamp() {
    // Invalidates a stamp via exclusive lock and verifies validateOrThrow() throws.
    var scope = new OptimisticReadScope();
    var frame = pool.acquire(true, Intention.TEST);

    long stamp = frame.tryOptimisticRead();
    scope.record(frame, stamp);

    // Invalidate by acquiring and releasing the exclusive lock
    long writeStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(writeStamp);

    // Should throw — stamp is now invalid
    scope.validateOrThrow();
  }

  @Test
  public void testValidateLastOrThrowHappyPath() {
    // Records two frames and validates only the last one succeeds.
    var scope = new OptimisticReadScope();
    var frame1 = pool.acquire(true, Intention.TEST);
    var frame2 = pool.acquire(true, Intention.TEST);

    scope.record(frame1, frame1.tryOptimisticRead());
    scope.record(frame2, frame2.tryOptimisticRead());

    // Should not throw — the last stamp is valid
    scope.validateLastOrThrow();

    releaseFrame(frame1);
    releaseFrame(frame2);
  }

  @Test(expected = OptimisticReadFailedException.class)
  public void testValidateLastOrThrowOnInvalidLastStamp() {
    // Invalidates only the last stamp and verifies validateLastOrThrow() throws.
    var scope = new OptimisticReadScope();
    var frame1 = pool.acquire(true, Intention.TEST);
    var frame2 = pool.acquire(true, Intention.TEST);

    scope.record(frame1, frame1.tryOptimisticRead());
    scope.record(frame2, frame2.tryOptimisticRead());

    // Invalidate only frame2
    long writeStamp = frame2.acquireExclusiveLock();
    frame2.releaseExclusiveLock(writeStamp);

    scope.validateLastOrThrow();
  }

  @Test
  public void testResetClearsState() {
    // Records frames, resets, and verifies count is zero and subsequent validate succeeds.
    var scope = new OptimisticReadScope();
    var frame = pool.acquire(true, Intention.TEST);

    scope.record(frame, frame.tryOptimisticRead());
    assertEquals(1, scope.count());

    scope.reset();
    assertEquals(0, scope.count());

    // Empty scope should validate fine
    scope.validateOrThrow();

    releaseFrame(frame);
  }

  @Test
  public void testGrowOnOverflow() {
    // Records more than the initial capacity (8) to trigger array growth.
    var scope = new OptimisticReadScope();
    var frames = new PageFrame[12];

    for (int i = 0; i < 12; i++) {
      frames[i] = pool.acquire(true, Intention.TEST);
      scope.record(frames[i], frames[i].tryOptimisticRead());
    }

    assertEquals(12, scope.count());
    // All stamps should still be valid
    scope.validateOrThrow();

    for (var frame : frames) {
      releaseFrame(frame);
    }
  }

  @Test
  public void testEmptyScopeValidates() {
    // An empty scope (no recorded stamps) should validate successfully.
    var scope = new OptimisticReadScope();
    assertEquals(0, scope.count());
    scope.validateOrThrow();
  }

  @Test
  public void testCrossThreadStampInvalidation() throws Exception {
    // Verifies that an exclusive lock acquired from another thread invalidates the stamp,
    // causing validateOrThrow() to throw (review finding R2).
    var scope = new OptimisticReadScope();
    var frame = pool.acquire(true, Intention.TEST);

    long stamp = frame.tryOptimisticRead();
    scope.record(frame, stamp);

    var latch = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();

    // Invalidate from another thread
    var thread = new Thread(() -> {
      try {
        long writeStamp = frame.acquireExclusiveLock();
        frame.releaseExclusiveLock(writeStamp);
      } catch (Throwable t) {
        error.set(t);
      } finally {
        latch.countDown();
      }
    });
    thread.start();
    latch.await();

    if (error.get() != null) {
      fail("Background thread failed: " + error.get());
    }

    try {
      scope.validateOrThrow();
      fail("Expected OptimisticReadFailedException");
    } catch (OptimisticReadFailedException expected) {
      // Expected — stamp was invalidated by the other thread's exclusive lock
    }
  }

  @Test
  public void testReuseAfterReset() {
    // Verifies that the scope can be reused after reset with new stamps.
    var scope = new OptimisticReadScope();
    var frame = pool.acquire(true, Intention.TEST);

    scope.record(frame, frame.tryOptimisticRead());
    scope.validateOrThrow();
    scope.reset();

    // Record a fresh stamp and validate again
    scope.record(frame, frame.tryOptimisticRead());
    assertEquals(1, scope.count());
    scope.validateOrThrow();

    releaseFrame(frame);
  }

  @Test(expected = OptimisticReadFailedException.class)
  public void testFirstStampInvalidSecondValid() {
    // When the first stamp is invalid but the second is valid, validateOrThrow()
    // should still throw because it checks all stamps.
    var scope = new OptimisticReadScope();
    var frame1 = pool.acquire(true, Intention.TEST);
    var frame2 = pool.acquire(true, Intention.TEST);

    scope.record(frame1, frame1.tryOptimisticRead());
    scope.record(frame2, frame2.tryOptimisticRead());

    // Invalidate only frame1
    long writeStamp = frame1.acquireExclusiveLock();
    frame1.releaseExclusiveLock(writeStamp);

    scope.validateOrThrow();
  }

  /**
   * Helper to release a frame back to the pool with the required exclusive lock protocol.
   */
  private void releaseFrame(PageFrame frame) {
    long stamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(-1, -1);
    frame.releaseExclusiveLock(stamp);
    pool.release(frame);
  }
}
