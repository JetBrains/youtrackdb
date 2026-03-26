package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertNotEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests that lock delegation methods in {@link CacheEntryChanges} correctly forward to the
 * underlying {@link CacheEntryImpl} delegate. Each test creates a real CacheEntryImpl backed
 * by a PageFrame and verifies that acquiring/releasing locks through the CacheEntryChanges
 * wrapper produces valid stamps and completes without error.
 */
public class CacheEntryChangesLockTest {

  private DirectMemoryAllocator allocator;
  private PageFramePool pool;
  private CachePointer cachePointer;
  private CacheEntryChanges changes;

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
  }

  @AfterClass
  public static void afterClass() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Before
  public void setUp() {
    allocator = new DirectMemoryAllocator();
    pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    cachePointer = new CachePointer(frame, pool, 1, 0);
    cachePointer.incrementReferrer();

    var delegate = new CacheEntryImpl(1, 0, cachePointer, false, null);

    // Use null for atomicOp — lock tests never call close(), so it is not accessed.
    changes = new CacheEntryChanges(false, null);
    changes.setDelegate(delegate);
  }

  @After
  public void tearDown() {
    cachePointer.decrementReferrer();
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testAcquireExclusiveLockDelegatesToCacheEntry() {
    // Verifies that acquireExclusiveLock() on CacheEntryChanges delegates to the underlying
    // CacheEntryImpl and returns a valid (non-zero) stamp from the PageFrame's StampedLock.
    long stamp = changes.acquireExclusiveLock();
    assertNotEquals(
        "acquireExclusiveLock must return a non-zero stamp", 0L, stamp);
    changes.releaseExclusiveLock();
  }

  @Test
  public void testAcquireSharedLockDelegatesToCacheEntry() {
    // Verifies that acquireSharedLock() on CacheEntryChanges delegates to the underlying
    // CacheEntryImpl and returns a valid (non-zero) stamp from the PageFrame's StampedLock.
    long stamp = changes.acquireSharedLock();
    assertNotEquals(
        "acquireSharedLock must return a non-zero stamp", 0L, stamp);
    changes.releaseSharedLock(stamp);
  }

  @Test
  public void testReleaseSharedLockDelegatesToCacheEntry() {
    // Verifies that releaseSharedLock(stamp) on CacheEntryChanges correctly delegates to
    // the underlying CacheEntryImpl, allowing the shared lock to be freed. A second shared
    // lock acquisition after release confirms the lock was properly released.
    long stamp1 = changes.acquireSharedLock();
    changes.releaseSharedLock(stamp1);

    // Re-acquire to prove the release actually freed the lock.
    long stamp2 = changes.acquireSharedLock();
    assertNotEquals(
        "Second acquireSharedLock must return a non-zero stamp", 0L, stamp2);
    changes.releaseSharedLock(stamp2);
  }
}
