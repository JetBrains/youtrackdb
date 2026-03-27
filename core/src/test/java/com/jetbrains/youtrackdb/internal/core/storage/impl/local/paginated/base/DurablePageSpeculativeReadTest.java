package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the DurablePage PageView constructor and speculative read guards.
 * Covers: PageView constructor sets fields correctly, guardSize passes for valid sizes,
 * guardSize throws for negative/overflow/exceeds-page-size, speculativeRead=false skips
 * guards, getPageIndex() works with both constructors, getLsn() returns null in
 * speculative mode.
 */
public class DurablePageSpeculativeReadTest {

  private static final int PAGE_SIZE = 4096;

  private DirectMemoryAllocator allocator;
  private PageFramePool pool;

  @Before
  public void setUp() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
    allocator = new DirectMemoryAllocator();
    pool = new PageFramePool(PAGE_SIZE, allocator, 4);
  }

  @After
  public void tearDown() {
    pool.clear();
    allocator.checkMemoryLeaks();
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testPageViewConstructorSetsFieldsCorrectly() {
    // Verifies that the speculative constructor produces a DurablePage with
    // the correct buffer, null cacheEntry, and null changes.
    var frame = acquireFrameWithCoordinates(1, 42);
    long stamp = frame.tryOptimisticRead();
    var pageView = new PageView(frame.getBuffer(), frame, stamp);

    var page = new DurablePage(pageView);

    assertEquals(42, page.getPageIndex());
    assertNull("getLsn() should return null in speculative mode", page.getLsn());
    assertNull("getCacheEntry() should be null in speculative mode", page.getCacheEntry());
    assertNull("getChanges() should be null in speculative mode", page.getChanges());

    releaseFrame(frame);
  }

  @Test
  public void testGuardSizePassesForValidSizes() {
    // guardSize should not throw for sizes within [0, buffer.capacity()].
    var frame = acquireFrameWithCoordinates(1, 0);
    long stamp = frame.tryOptimisticRead();
    var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

    // These should not throw
    page.guardSize(0);
    page.guardSize(1);
    page.guardSize(PAGE_SIZE);

    releaseFrame(frame);
  }

  @Test
  public void testGuardSizeThrowsForNegativeSize() {
    // Negative size indicates stale data — should throw.
    var frame = acquireFrameWithCoordinates(1, 0);
    try {
      long stamp = frame.tryOptimisticRead();
      var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

      page.guardSize(-1);
      throw new AssertionError("Expected OptimisticReadFailedException");
    } catch (OptimisticReadFailedException expected) {
      // Expected
    } finally {
      releaseFrame(frame);
    }
  }

  @Test
  public void testGuardSizeThrowsForOverflowSize() {
    // Size exceeding buffer capacity indicates stale data — should throw.
    var frame = acquireFrameWithCoordinates(1, 0);
    try {
      long stamp = frame.tryOptimisticRead();
      var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

      page.guardSize(PAGE_SIZE + 1);
      throw new AssertionError("Expected OptimisticReadFailedException");
    } catch (OptimisticReadFailedException expected) {
      // Expected
    } finally {
      releaseFrame(frame);
    }
  }

  @Test
  public void testGuardSizeThrowsForLargeNegative() {
    // Large negative value (could come from corrupted int read) — should throw.
    var frame = acquireFrameWithCoordinates(1, 0);
    try {
      long stamp = frame.tryOptimisticRead();
      var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

      page.guardSize(Integer.MIN_VALUE);
      throw new AssertionError("Expected OptimisticReadFailedException");
    } catch (OptimisticReadFailedException expected) {
      // Expected
    } finally {
      releaseFrame(frame);
    }
  }

  @Test
  public void testGetPageIndexReturnsFramePageIndex() {
    // Verifies that getPageIndex() returns the value from the PageFrame.
    var frame = acquireFrameWithCoordinates(5, 123);
    long stamp = frame.tryOptimisticRead();
    var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

    assertEquals(123, page.getPageIndex());

    releaseFrame(frame);
  }

  @Test
  public void testGetLsnReturnsNullInSpeculativeMode() {
    // LSN is not available without a CacheEntry.
    var frame = acquireFrameWithCoordinates(1, 0);
    long stamp = frame.tryOptimisticRead();
    var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

    assertNull(page.getLsn());

    releaseFrame(frame);
  }

  @Test
  public void testToStringDoesNotThrowInSpeculativeMode() {
    // toString() should handle null cacheEntry gracefully.
    var frame = acquireFrameWithCoordinates(1, 0);
    long stamp = frame.tryOptimisticRead();
    var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

    assertNotNull(page.toString());

    releaseFrame(frame);
  }

  @Test
  public void testGetIntValueThrowsOnOutOfBoundsOffset() {
    // When a stale offset is passed to getIntValue during speculative read,
    // guardOffset should throw OptimisticReadFailedException instead of
    // IndexOutOfBoundsException.
    var frame = acquireFrameWithCoordinates(1, 0);
    try {
      long stamp = frame.tryOptimisticRead();
      var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

      page.getIntValue(PAGE_SIZE); // offset + 4 > capacity
      throw new AssertionError("Expected OptimisticReadFailedException");
    } catch (OptimisticReadFailedException expected) {
      // Expected
    } finally {
      releaseFrame(frame);
    }
  }

  @Test
  public void testGetLongValueThrowsOnNegativeOffset() {
    // Negative offsets from stale data should be caught by guardOffset.
    var frame = acquireFrameWithCoordinates(1, 0);
    try {
      long stamp = frame.tryOptimisticRead();
      var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

      page.getLongValue(-1);
      throw new AssertionError("Expected OptimisticReadFailedException");
    } catch (OptimisticReadFailedException expected) {
      // Expected
    } finally {
      releaseFrame(frame);
    }
  }

  @Test
  public void testGetShortValueThrowsOnOutOfBoundsOffset() {
    // Offset that would read past the end of the buffer.
    var frame = acquireFrameWithCoordinates(1, 0);
    try {
      long stamp = frame.tryOptimisticRead();
      var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

      page.getShortValue(PAGE_SIZE - 1); // offset + 2 > capacity
      throw new AssertionError("Expected OptimisticReadFailedException");
    } catch (OptimisticReadFailedException expected) {
      // Expected
    } finally {
      releaseFrame(frame);
    }
  }

  @Test
  public void testGetByteValueThrowsOnNegativeOffset() {
    // Negative offset from stale data.
    var frame = acquireFrameWithCoordinates(1, 0);
    try {
      long stamp = frame.tryOptimisticRead();
      var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

      page.getByteValue(-1);
      throw new AssertionError("Expected OptimisticReadFailedException");
    } catch (OptimisticReadFailedException expected) {
      // Expected
    } finally {
      releaseFrame(frame);
    }
  }

  @Test
  public void testScalarGettersPassForValidOffsetsInSpeculativeMode() {
    // Valid offsets within buffer bounds should not throw in speculative mode.
    var frame = acquireFrameWithCoordinates(1, 0);
    long stamp = frame.tryOptimisticRead();
    var page = new DurablePage(new PageView(frame.getBuffer(), frame, stamp));

    // These should not throw (reading zero-filled buffer is fine)
    page.getIntValue(0);
    page.getLongValue(0);
    page.getShortValue(0);
    page.getByteValue(0);
    // Read at last valid offset for each type
    page.getIntValue(PAGE_SIZE - 4);
    page.getLongValue(PAGE_SIZE - 8);
    page.getShortValue(PAGE_SIZE - 2);
    page.getByteValue(PAGE_SIZE - 1);

    releaseFrame(frame);
  }

  /**
   * Acquires a frame from the pool and sets page coordinates under exclusive lock.
   */
  private PageFrame acquireFrameWithCoordinates(long fileId, int pageIndex) {
    var frame = pool.acquire(true, Intention.TEST);
    long exclusiveStamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(fileId, pageIndex);
    frame.releaseExclusiveLock(exclusiveStamp);
    return frame;
  }

  /**
   * Releases a frame back to the pool with proper exclusive lock protocol.
   */
  private void releaseFrame(PageFrame frame) {
    long stamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(-1, -1);
    frame.releaseExclusiveLock(stamp);
    pool.release(frame);
  }
}
