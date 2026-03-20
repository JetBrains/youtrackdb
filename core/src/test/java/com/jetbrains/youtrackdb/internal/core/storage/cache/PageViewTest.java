package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for PageView — the immutable view record holding a speculative page buffer,
 * PageFrame reference, and optimistic stamp.
 */
public class PageViewTest {

  private DirectMemoryAllocator allocator;
  private PageFramePool pool;

  @Before
  public void setUp() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
    allocator = new DirectMemoryAllocator();
    pool = new PageFramePool(4096, allocator, 2);
  }

  @After
  public void tearDown() {
    pool.clear();
    allocator.checkMemoryLeaks();
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testRecordComponents() {
    // Verifies that the record components (buffer, pageFrame, stamp) are accessible.
    var frame = pool.acquire(true, Intention.TEST);
    long stamp = frame.tryOptimisticRead();
    var buffer = frame.getBuffer();

    var pageView = new PageView(buffer, frame, stamp);

    assertSame(buffer, pageView.buffer());
    assertSame(frame, pageView.pageFrame());
    assertEquals(stamp, pageView.stamp());

    releaseFrame(frame);
  }

  @Test
  public void testValidateStampReturnsTrue() {
    // When no exclusive lock has been acquired, validateStamp() should return true.
    var frame = pool.acquire(true, Intention.TEST);
    long stamp = frame.tryOptimisticRead();

    var pageView = new PageView(frame.getBuffer(), frame, stamp);
    assertTrue(pageView.validateStamp());

    releaseFrame(frame);
  }

  @Test
  public void testValidateStampReturnsFalseAfterExclusiveLock() {
    // After an exclusive lock cycle invalidates the stamp, validateStamp() returns false.
    var frame = pool.acquire(true, Intention.TEST);
    long stamp = frame.tryOptimisticRead();

    var pageView = new PageView(frame.getBuffer(), frame, stamp);

    // Invalidate the stamp
    long writeStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(writeStamp);

    assertFalse(pageView.validateStamp());

    releaseFrame(frame);
  }

  /**
   * Helper to release a frame back to the pool with the required exclusive lock protocol.
   */
  private void releaseFrame(
      com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame frame) {
    long stamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(-1, -1);
    frame.releaseExclusiveLock(stamp);
    pool.release(frame);
  }
}
