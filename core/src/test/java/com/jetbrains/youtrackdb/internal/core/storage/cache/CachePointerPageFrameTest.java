package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for the PageFrame-based CachePointer constructor, verifying that:
 * - The new constructor derives pointer and buffer from PageFrame
 * - decrementReferrer() releases the frame back to PageFramePool (not ByteBufferPool)
 * - The null sentinel case (both pageFrame and framePool null) works correctly
 * - getPageFrame() returns the expected value
 */
public class CachePointerPageFrameTest {

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
  }

  @AfterClass
  public static void afterClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testPageFrameConstructorDerivesPointerAndBuffer() {
    // Verifies that the PageFrame-based constructor correctly derives the Pointer and
    // buffer from the PageFrame, and getPageFrame() returns the expected value.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    var cachePointer = new CachePointer(frame, pool, 10, 5);

    assertSame(frame.getPointer(), cachePointer.getPointer());
    assertNotNull(cachePointer.getBuffer());
    assertEquals(4096, cachePointer.getBuffer().capacity());
    assertSame(frame, cachePointer.getPageFrame());
    assertEquals(10, cachePointer.getFileId());
    assertEquals(5, cachePointer.getPageIndex());

    pool.release(frame);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testDecrementReferrerReleasesToFramePool() {
    // Verifies that when referrersCount reaches 0, the PageFrame is released to the
    // PageFramePool (not ByteBufferPool). Memory should still be allocated (in pool),
    // not deallocated.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(false, Intention.TEST);

    var cachePointer = new CachePointer(frame, pool, 10, 5);
    cachePointer.incrementReferrer();

    assertEquals(0, pool.getPoolSize());
    assertEquals(4096, allocator.getMemoryConsumption());

    // Decrement to 0 — frame should be released back to pool
    cachePointer.decrementReferrer();

    assertEquals(1, pool.getPoolSize());
    // Memory still allocated because frame is pooled, not deallocated
    assertEquals(4096, allocator.getMemoryConsumption());

    pool.clear();
    assertEquals(0, allocator.getMemoryConsumption());
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testDecrementReferrerMultipleRefs() {
    // Verifies that the frame is only released when referrersCount reaches 0, not
    // on intermediate decrements.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(false, Intention.TEST);

    var cachePointer = new CachePointer(frame, pool, 10, 5);
    cachePointer.incrementReferrer(); // ref = 1
    cachePointer.incrementReferrer(); // ref = 2

    assertEquals(0, pool.getPoolSize());

    cachePointer.decrementReferrer(); // ref = 1 — should NOT release
    assertEquals(0, pool.getPoolSize());

    cachePointer.decrementReferrer(); // ref = 0 — should release to pool
    assertEquals(1, pool.getPoolSize());

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testNullSentinelConstructor() {
    // Verifies the null sentinel case: both pageFrame and framePool are null
    // (used by AtomicOperationBinaryTracking for metadata-only entries).
    // getBuffer() must return null and decrementReferrer() must not throw.
    var cachePointer = new CachePointer((PageFrame) null, null, 10, 5);

    assertNull(cachePointer.getPageFrame());
    assertNull(cachePointer.getPointer());
    assertNull(cachePointer.getBuffer());

    cachePointer.incrementReferrer();
    cachePointer.decrementReferrer(); // Should not throw
  }

  @Test
  public void testGetPageFrameReturnsNullForLegacyConstructor() {
    // Verifies that getPageFrame() returns null when the legacy Pointer+ByteBufferPool
    // constructor is used.
    var cachePointer = new CachePointer((Pointer) null, null, 10, 5);
    assertNull(cachePointer.getPageFrame());
  }
}
