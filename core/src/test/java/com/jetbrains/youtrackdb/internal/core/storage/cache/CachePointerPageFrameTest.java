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
  public void testGetPageFrameReturnsNullForLegacyConstructorWithNullPointer() {
    // Verifies that getPageFrame() returns null when the legacy Pointer+ByteBufferPool
    // constructor is used with a null Pointer (sentinel).
    var cachePointer = new CachePointer((Pointer) null, null, 10, 5);
    assertNull(cachePointer.getPageFrame());
  }

  @Test
  public void testLegacyConstructorCreatesStandalonePageFrame() {
    // Verifies that the legacy Pointer+ByteBufferPool constructor creates a standalone
    // PageFrame for lock delegation when pointer is non-null.
    var allocator = new DirectMemoryAllocator();
    var pointer = allocator.allocate(4096, true, Intention.TEST);
    try {
      var cachePointer = new CachePointer(pointer, null, 10, 5);
      assertNotNull(cachePointer.getPageFrame());
      assertSame(pointer, cachePointer.getPageFrame().getPointer());
    } finally {
      allocator.deallocate(pointer);
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRejectsPageFrameWithoutPool() {
    // Verifies that passing a non-null pageFrame with a null framePool is rejected.
    // This asymmetric case would cause a silent memory leak in decrementReferrer().
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);
    try {
      new CachePointer(frame, null, 10, 5);
    } finally {
      pool.release(frame);
      pool.clear();
    }
  }

  @Test(expected = IllegalStateException.class)
  public void testRejectsNegativeFileIdPageFrameConstructor() {
    // Verifies that the PageFrame constructor rejects negative fileId.
    new CachePointer((PageFrame) null, null, -1, 5);
  }

  @Test(expected = IllegalStateException.class)
  public void testRejectsNegativePageIndexPageFrameConstructor() {
    // Verifies that the PageFrame constructor rejects negative pageIndex.
    new CachePointer((PageFrame) null, null, 10, -1);
  }

  @Test(expected = IllegalStateException.class)
  public void testRejectsNegativeFileIdLegacyConstructor() {
    // Verifies that the legacy Pointer+ByteBufferPool constructor rejects a negative fileId.
    // The constructor must validate fileId >= 0 and throw IllegalStateException otherwise.
    new CachePointer((Pointer) null, null, -1, 5);
  }

  @Test(expected = IllegalStateException.class)
  public void testRejectsNegativePageIndexLegacyConstructor() {
    // Verifies that the legacy Pointer+ByteBufferPool constructor rejects a negative pageIndex.
    // The constructor must validate pageIndex >= 0 and throw IllegalStateException otherwise.
    new CachePointer((Pointer) null, null, 10, -1);
  }

  @Test(expected = IllegalStateException.class)
  public void testAcquireExclusiveLockOnSentinelThrows() {
    // Verifies that acquireExclusiveLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to acquire.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    sentinel.acquireExclusiveLock();
  }

  @Test(expected = IllegalStateException.class)
  public void testReleaseExclusiveLockOnSentinelThrows() {
    // Verifies that releaseExclusiveLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to release.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    sentinel.releaseExclusiveLock(42L);
  }

  @Test(expected = IllegalStateException.class)
  public void testAcquireSharedLockOnSentinelThrows() {
    // Verifies that acquireSharedLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to acquire.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    sentinel.acquireSharedLock();
  }

  @Test(expected = IllegalStateException.class)
  public void testReleaseSharedLockOnSentinelThrows() {
    // Verifies that releaseSharedLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to release.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    sentinel.releaseSharedLock(42L);
  }

  @Test(expected = IllegalStateException.class)
  public void testTryAcquireSharedLockOnSentinelThrows() {
    // Verifies that tryAcquireSharedLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to try.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    sentinel.tryAcquireSharedLock();
  }

  @Test
  public void testPageFrameCoordinatesPropagatedByPageFrameConstructor() {
    // Verifies that the PageFrame-based constructor propagates fileId and pageIndex
    // to the PageFrame, so the coordinate-verification guard in
    // DurableComponent.loadPageOptimistic() can detect frame reuse.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    var cp = new CachePointer(frame, pool, 42, 7);

    assertEquals(42L, frame.getFileId());
    assertEquals(7, frame.getPageIndex());

    pool.release(frame);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testPageFrameCoordinatesPropagatedByLegacyConstructor() {
    // Verifies that the legacy Pointer+ByteBufferPool constructor propagates fileId and
    // pageIndex to the standalone PageFrame created for lock delegation.
    var allocator = new DirectMemoryAllocator();
    var pointer = allocator.allocate(4096, true, Intention.TEST);

    var cp = new CachePointer(pointer, null, 99, 13);
    var frame = cp.getPageFrame();

    assertNotNull(frame);
    assertEquals(99L, frame.getFileId());
    assertEquals(13, frame.getPageIndex());

    allocator.deallocate(pointer);
    allocator.checkMemoryLeaks();
  }
}
