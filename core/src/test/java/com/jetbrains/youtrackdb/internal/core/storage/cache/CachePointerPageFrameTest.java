package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.common.directmemory.Pointer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for the PageFrame-based CachePointer constructor, verifying that:
 * - The new constructor derives pointer and buffer from PageFrame
 * - decrementReferrer() releases the frame back to PageFramePool (not ByteBufferPool)
 * - The null sentinel case (both pageFrame and framePool null) works correctly
 * - getPageFrame() returns the expected value
 *
 * <p>Mutates the global {@code DIRECT_MEMORY_TRACK_MODE} flag for the leak-detector
 * assertions; the flag is snapshotted in {@code @BeforeClass} and restored in
 * {@code @AfterClass} so a parallel test that toggled the flag on is not silently
 * disabled when this class finishes. Tagged {@link SequentialTest} so surefire's
 * parallel forks do not race the snapshot/restore window.
 */
@Category(SequentialTest.class)
public class CachePointerPageFrameTest {

  /** Saved value of {@code DIRECT_MEMORY_TRACK_MODE} captured before the class mutates it. */
  private static Object savedTrackMode;

  @BeforeClass
  public static void beforeClass() {
    savedTrackMode = GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.getValue();
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
  }

  @AfterClass
  public static void afterClass() {
    // Restore whatever the flag was before this class — not a hard-coded false.
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(savedTrackMode);
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

  @Test
  public void testAcquireExclusiveLockOnSentinelThrows() {
    // Verifies that acquireExclusiveLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to acquire.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    try {
      sentinel.acquireExclusiveLock();
      Assert.fail("Expected IllegalStateException for sentinel lock");
    } catch (IllegalStateException e) {
      Assertions.assertThat(e.getMessage()).containsIgnoringCase("sentinel");
    }
  }

  @Test
  public void testReleaseExclusiveLockOnSentinelThrows() {
    // Verifies that releaseExclusiveLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to release.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    try {
      sentinel.releaseExclusiveLock(42L);
      Assert.fail("Expected IllegalStateException for sentinel lock");
    } catch (IllegalStateException e) {
      Assertions.assertThat(e.getMessage()).containsIgnoringCase("sentinel");
    }
  }

  @Test
  public void testAcquireSharedLockOnSentinelThrows() {
    // Verifies that acquireSharedLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to acquire.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    try {
      sentinel.acquireSharedLock();
      Assert.fail("Expected IllegalStateException for sentinel lock");
    } catch (IllegalStateException e) {
      Assertions.assertThat(e.getMessage()).containsIgnoringCase("sentinel");
    }
  }

  @Test
  public void testReleaseSharedLockOnSentinelThrows() {
    // Verifies that releaseSharedLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to release.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    try {
      sentinel.releaseSharedLock(42L);
      Assert.fail("Expected IllegalStateException for sentinel lock");
    } catch (IllegalStateException e) {
      Assertions.assertThat(e.getMessage()).containsIgnoringCase("sentinel");
    }
  }

  @Test
  public void testTryAcquireSharedLockOnSentinelThrows() {
    // Verifies that tryAcquireSharedLock() throws IllegalStateException on a sentinel
    // CachePointer (one with null PageFrame), since there is no lock to try.
    var sentinel = new CachePointer((PageFrame) null, null, 10, 5);
    try {
      sentinel.tryAcquireSharedLock();
      Assert.fail("Expected IllegalStateException for sentinel lock");
    } catch (IllegalStateException e) {
      Assertions.assertThat(e.getMessage()).containsIgnoringCase("sentinel");
    }
  }

  @Test
  public void testBoundaryValueFileIdZeroPageIndexZero() {
    // Verifies that the minimum valid boundary values (fileId=0, pageIndex=0) are accepted
    // and propagated correctly. These are edge cases because 0 is the first valid value
    // after the -1 rejection boundary.
    var cachePointer = new CachePointer((PageFrame) null, null, 0, 0);
    assertEquals(0, cachePointer.getFileId());
    assertEquals(0, cachePointer.getPageIndex());
  }

  @Test
  public void testBoundaryValueMaxFileIdAndPageIndex() {
    // Verifies that maximum valid values (Integer.MAX_VALUE for both fileId and pageIndex)
    // are accepted and stored correctly. Tests that no overflow or sign-extension issues
    // occur with large values.
    var cachePointer = new CachePointer(
        (PageFrame) null, null, Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE, cachePointer.getFileId());
    assertEquals(Integer.MAX_VALUE, cachePointer.getPageIndex());
  }

  @Test
  public void testBoundaryValueMaxFileIdPageFrameConstructor() {
    // Verifies that large coordinate values are propagated correctly to the PageFrame.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    var cp = new CachePointer(frame, pool, Integer.MAX_VALUE, Integer.MAX_VALUE);

    assertEquals((long) Integer.MAX_VALUE, frame.getFileId());
    assertEquals(Integer.MAX_VALUE, frame.getPageIndex());

    pool.release(frame);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test
  public void testPageFrameCoordinatesPropagatedByPageFrameConstructor() {
    // Verifies that the PageFrame-based constructor propagates fileId and pageIndex
    // to the PageFrame, so the coordinate-verification guard in
    // StorageComponent.loadPageOptimistic() can detect frame reuse.
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

  /**
   * Regression test for the validated-torn-read race on page reload into a recycled frame
   * (YTDB-1203). It deterministically replays the confirmed interleaving from
   * {@code StorageComponent.loadPageOptimistic}:
   *
   * <ol>
   *   <li>Page (1, 7) lives in a frame; a reader obtains the frame reference (as
   *       {@code getPageFrameOptimistic} would) and is "preempted" before taking its
   *       optimistic stamp.</li>
   *   <li>The page is evicted: the last referrer releases, the frame returns to the pool
   *       (coordinates reset to (-1, -1) under lock).</li>
   *   <li>A loader re-acquires the SAME frame from the pool for the SAME page (1, 7).
   *       The reader resumes and takes its stamp exactly here — after the pool-acquire
   *       lock cycle but before the loader has filled the buffer or published the
   *       coordinates. This is the dangerous window: the stamp is non-zero and, without
   *       the fix, nothing invalidates it later.</li>
   *   <li>The loader fills the buffer from "disk" and constructs the CachePointer.</li>
   *   <li>The reader performs the loadPageOptimistic sequence: coordinate check (passes —
   *       coordinates are published by now), buffer read (could have been torn had it
   *       overlapped the fill), then stamp validation.</li>
   * </ol>
   *
   * <p>Expected outcome: validation MUST fail, because the CachePointer constructor
   * publishes the coordinates inside an exclusive-lock cycle which invalidates every stamp
   * taken before the fill + coordinates were complete. Before the fix the constructor wrote
   * the coordinates with plain unlocked stores, the stamp stayed valid, and the reader
   * could validate a half-filled buffer (silent torn read).
   */
  @Test
  public void testStampFromReloadWindowMustFailValidation() {
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);

    // Initial assignment: page (1, 7) lives in this frame.
    var frame = pool.acquire(true, Intention.TEST);
    var c0 = new CachePointer(frame, pool, 1, 7);
    c0.incrementReadersReferrer();

    // Reader captures the frame reference (getPageFrameOptimistic equivalent) and is
    // preempted before tryOptimisticRead. In this test the "reader" is the main thread;
    // preemption is modeled by simply running the evict/reload steps before it stamps.
    var staleFrameRef = frame;

    // Eviction: last referrer releases — the frame goes back to the pool. The release
    // resets the coordinates to (-1, -1) under an exclusive-lock cycle.
    c0.decrementReadersReferrer();

    // Loader: re-acquires a frame from the pool — must be the same recycled instance for
    // the scenario to be meaningful.
    var reacquired = pool.acquire(true, Intention.TEST);
    assertSame("pool must recycle the same frame for this scenario", staleFrameRef,
        reacquired);

    // Reader resumes: takes its stamp in the dangerous window — after the pool-acquire
    // lock cycle, before the fill and coordinate publication.
    long staleStamp = staleFrameRef.tryOptimisticRead();
    assertNotEquals("no lock is held in the window — stamp must be non-zero", 0L,
        staleStamp);

    // Loader: fills the buffer from "disk" and publishes via the CachePointer constructor
    // (mirrors WOWCache.loadFileContent: fill first, then construct).
    reacquired.getBuffer().putLong(0, 0x1234_5678_9ABC_DEF0L);
    var c1 = new CachePointer(reacquired, pool, 1, 7);
    c1.incrementReadersReferrer();

    // Reader: loadPageOptimistic sequence. The coordinate check passes — the loader has
    // already published (1, 7) — so only stamp validation stands between the reader and
    // a potentially torn buffer read.
    assertEquals(1L, staleFrameRef.getFileId());
    assertEquals(7, staleFrameRef.getPageIndex());
    staleFrameRef.getBuffer().getLong(0); // speculative buffer read (could have been torn)

    // THE regression assertion: the stamp predates the fill + coordinate publication, so
    // it must NOT validate. Before the fix the constructor published the coordinates with
    // plain unlocked stores and this returned true — a validated torn read.
    assertFalse(
        "stamp taken before the reload's fill/publication must fail validation",
        staleFrameRef.validate(staleStamp));

    // Sanity: a stamp taken after publication validates and observes the complete fill.
    long freshStamp = staleFrameRef.tryOptimisticRead();
    assertEquals(1L, staleFrameRef.getFileId());
    assertEquals(7, staleFrameRef.getPageIndex());
    assertEquals(0x1234_5678_9ABC_DEF0L, staleFrameRef.getBuffer().getLong(0));
    assertTrue("post-publication stamp must validate", staleFrameRef.validate(freshStamp));

    c1.decrementReadersReferrer();
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  @Test(timeout = 10_000)
  public void testOptimisticReadDetectsCoordinateChangeAcrossThreads() throws Exception {
    // Verifies that tryOptimisticRead()/validate() on a PageFrame correctly detects
    // coordinate changes made by another thread under exclusive lock. The writer thread
    // changes the coordinates, and the reader's stamp must be invalidated.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    // Set initial coordinates.
    long initStamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(1, 10);
    frame.releaseExclusiveLock(initStamp);

    // Reader obtains an optimistic stamp before the writer changes coordinates.
    long optimisticStamp = frame.tryOptimisticRead();
    Assert.assertNotEquals("Optimistic stamp should be non-zero", 0L, optimisticStamp);

    // Read coordinates under the optimistic stamp.
    long fileIdBefore = frame.getFileId();
    int pageIndexBefore = frame.getPageIndex();
    assertEquals(1L, fileIdBefore);
    assertEquals(10, pageIndexBefore);

    // Writer thread changes the coordinates under exclusive lock.
    var writerDone = new java.util.concurrent.CountDownLatch(1);
    var writerThread = new Thread(() -> {
      long writeStamp = frame.acquireExclusiveLock();
      frame.setPageCoordinates(2, 20);
      frame.releaseExclusiveLock(writeStamp);
      writerDone.countDown();
    });
    writerThread.start();
    // Bounded wait — a regression in StampedLock acquire/release must not hang the build.
    Assert.assertTrue("writer thread must finish in 5 s",
        writerDone.await(5, TimeUnit.SECONDS));

    // The reader's optimistic stamp must be invalidated because the writer acquired
    // the exclusive lock (which bumps the StampedLock's write-stamp sequence).
    Assert.assertFalse(
        "Optimistic stamp should be invalidated after exclusive write",
        frame.validate(optimisticStamp));

    // Re-read under a new optimistic stamp to verify the new coordinates.
    long newStamp = frame.tryOptimisticRead();
    long fileIdAfter = frame.getFileId();
    int pageIndexAfter = frame.getPageIndex();
    Assert.assertTrue("New optimistic stamp should be valid", frame.validate(newStamp));
    assertEquals(2L, fileIdAfter);
    assertEquals(20, pageIndexAfter);

    pool.release(frame);
    pool.clear();
    allocator.checkMemoryLeaks();
  }

  // ---------------------------------------------------------------------------
  // Readers / Writers referrer tracking
  // ---------------------------------------------------------------------------

  /**
   * Verifies that incrementReadersReferrer() increments both the reader sub-count and the
   * overall referrer count. After decrementing back, the referrer reaches 0 and the frame
   * is returned to the pool.
   */
  @Test
  public void testIncrementAndDecrementReadersReferrer() {
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    var cp = new CachePointer(frame, pool, 5, 3);

    // incrementReadersReferrer increments the hidden referrersCount by 1.
    cp.incrementReadersReferrer();
    assertEquals(0, pool.getPoolSize()); // Frame still in use.

    // decrementReadersReferrer decrements by 1, reaching 0 → frame returned to pool.
    cp.decrementReadersReferrer();
    assertEquals(1, pool.getPoolSize());

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies that incrementWritersReferrer() increments the writer sub-count and the overall
   * referrer count, and decrementWritersReferrer() decrements both. Frame is returned to the
   * pool when referrers reach 0.
   */
  @Test
  public void testIncrementAndDecrementWritersReferrer() {
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    var cp = new CachePointer(frame, pool, 7, 2);

    cp.incrementWritersReferrer();
    assertEquals(0, pool.getPoolSize());

    cp.decrementWritersReferrer();
    assertEquals(1, pool.getPoolSize());

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies the full reader/writer listener notification cycle:
   * (1) Writer joins alone → no notification (not yet "only writers" state because we start from 0).
   * (2) Reader joins while writer is present → removeOnlyWriters fires (page transitions from
   *     "only writers" to "readers+writers").
   * (3) Reader leaves while writer is still present → addOnlyWriters fires (page transitions
   *     back to "only writers").
   * (4) Writer leaves alone (readers==0, writers==0 after decrement) → removeOnlyWriters fires.
   */
  @Test
  public void testWritersListenerFullCycle() {
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    var cp = new CachePointer(frame, pool, 9, 1);

    var capturedEvent = new AtomicReference<String>();
    cp.setWritersListener(new CachePointer.WritersListener() {
      @Override
      public void addOnlyWriters(long fileId, long pageIndex) {
        capturedEvent.set("addOnly:" + fileId + ":" + pageIndex);
      }

      @Override
      public void removeOnlyWriters(long fileId, long pageIndex) {
        capturedEvent.set("removeOnly:" + fileId + ":" + pageIndex);
      }
    });

    // Step 1: Writer joins alone → no notification (initial state, not "only writers" yet).
    cp.incrementWritersReferrer();
    assertNull("No notification on first writer join", capturedEvent.get());

    // Step 2: Reader joins while writer present → removeOnlyWriters fires (no longer only writers).
    cp.incrementReadersReferrer();
    assertEquals("removeOnly:9:1", capturedEvent.get());

    // Step 3: Reader leaves while writer still present → addOnlyWriters fires (only writers again).
    capturedEvent.set(null);
    cp.decrementReadersReferrer();
    assertEquals("addOnly:9:1", capturedEvent.get());

    // Step 4: Writer leaves alone (readers==0, writers==0 after) → removeOnlyWriters fires.
    capturedEvent.set(null);
    cp.decrementWritersReferrer();
    assertEquals("removeOnly:9:1", capturedEvent.get());

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies that the WritersListener.removeOnlyWriters() callback fires when a reader joins
   * a page that currently has only writers (readers == 0, writers > 0). After incrementing
   * a reader, the "only writers" state ends — removeOnlyWriters must fire.
   */
  @Test
  public void testWritersListenerRemoveOnlyWritersCalledWhenReaderJoins() {
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    var cp = new CachePointer(frame, pool, 11, 4);

    var capturedEvent = new AtomicReference<String>();
    cp.setWritersListener(new CachePointer.WritersListener() {
      @Override
      public void addOnlyWriters(long fileId, long pageIndex) {
        capturedEvent.set("addOnly:" + fileId + ":" + pageIndex);
      }

      @Override
      public void removeOnlyWriters(long fileId, long pageIndex) {
        capturedEvent.set("removeOnly:" + fileId + ":" + pageIndex);
      }
    });

    // Writer is present, no readers → "only writers" state.
    cp.incrementWritersReferrer();

    assertNull("No notification yet — only writer joined, no reader", capturedEvent.get());

    // Reader joins → no longer "only writers" → removeOnlyWriters must fire.
    cp.incrementReadersReferrer();
    assertEquals("removeOnly:11:4", capturedEvent.get());

    // Clean up.
    cp.decrementReadersReferrer();
    cp.decrementWritersReferrer();

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  /**
   * Verifies that the WritersListener.removeOnlyWriters() callback fires when the last writer
   * leaves and no readers are present (writers == 0, readers == 0 after decrement). This is
   * the "only-writers state ends because the writer left" notification.
   */
  @Test
  public void testWritersListenerRemoveOnlyWritersCalledWhenLastWriterLeaves() {
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    var cp = new CachePointer(frame, pool, 13, 5);

    var capturedEvent = new AtomicReference<String>();
    cp.setWritersListener(new CachePointer.WritersListener() {
      @Override
      public void addOnlyWriters(long fileId, long pageIndex) {
        capturedEvent.set("addOnly:" + fileId + ":" + pageIndex);
      }

      @Override
      public void removeOnlyWriters(long fileId, long pageIndex) {
        capturedEvent.set("removeOnly:" + fileId + ":" + pageIndex);
      }
    });

    // Start with one writer, no readers → only-writers state.
    cp.incrementWritersReferrer();

    // Writer leaves with no readers → removeOnlyWriters fires (writers+readers both 0).
    cp.decrementWritersReferrer();
    assertEquals("removeOnly:13:5", capturedEvent.get());

    pool.clear();
    allocator.checkMemoryLeaks();
  }

  // ---------------------------------------------------------------------------
  // equals / hashCode / toString
  // ---------------------------------------------------------------------------

  /**
   * Verifies that equals() returns true for two CachePointers with identical (fileId, pageIndex)
   * and false for different coordinates or incompatible types. Also verifies reflexivity (a == a)
   * and null-safety (a.equals(null) == false).
   */
  @Test
  public void testEqualsAndHashCode() {
    var a = new CachePointer((PageFrame) null, null, 10, 5);
    var b = new CachePointer((PageFrame) null, null, 10, 5);
    var c = new CachePointer((PageFrame) null, null, 10, 6);
    var d = new CachePointer((PageFrame) null, null, 11, 5);

    // Reflexivity.
    assertTrue("Reflexive equals failed", a.equals(a));

    // Symmetry for equal pair.
    assertTrue("Equal pair not equal", a.equals(b));
    assertTrue("Equal pair not equal (b.equals(a))", b.equals(a));

    // Hash code consistency.
    assertEquals("Hash codes must be equal for equal objects", a.hashCode(), b.hashCode());

    // Not equal for different pageIndex.
    assertFalse("Different pageIndex must not be equal", a.equals(c));

    // Not equal for different fileId.
    assertFalse("Different fileId must not be equal", a.equals(d));

    // Null safety.
    assertFalse("equals(null) must return false", a.equals(null));

    // Different type.
    assertFalse("equals(String) must return false", a.equals("not a pointer"));

    // hashCode is stable across calls (caching).
    assertEquals("hashCode must be stable", a.hashCode(), a.hashCode());

    // hashCode is non-zero for typical non-trivial coordinates (not guaranteed, but (10,5)
    // produces non-zero by formula: (int)(10^0) * 31 + 5 = 36).
    assertNotEquals("hashCode expected non-zero for (10,5)", 0, a.hashCode());
  }

  @Test(timeout = 30_000)
  public void testConcurrentOptimisticReadDuringExclusiveWrite() throws Exception {
    // Verifies that optimistic reads during concurrent exclusive writes either observe
    // consistent coordinates (and validate succeeds) or detect the write (validation
    // fails). No torn read (mismatched fileId/pageIndex pair) should be observed.
    var allocator = new DirectMemoryAllocator();
    var pool = new PageFramePool(4096, allocator, 2);
    var frame = pool.acquire(true, Intention.TEST);

    // Initial coordinates.
    long initStamp = frame.acquireExclusiveLock();
    frame.setPageCoordinates(0, 0);
    frame.releaseExclusiveLock(initStamp);

    var iterations = 10_000;
    var errors = new java.util.concurrent.atomic.AtomicReference<String>();
    var running = new java.util.concurrent.atomic.AtomicBoolean(true);

    // Writer thread: alternates between two coordinate pairs.
    var writerThread = new Thread(() -> {
      for (var i = 0; i < iterations && errors.get() == null; i++) {
        long stamp = frame.acquireExclusiveLock();
        if (i % 2 == 0) {
          frame.setPageCoordinates(100, 200);
        } else {
          frame.setPageCoordinates(300, 400);
        }
        frame.releaseExclusiveLock(stamp);
      }
      running.set(false);
    });

    // Reader thread: performs optimistic reads and checks for torn values.
    var readerThread = new Thread(() -> {
      while (running.get() && errors.get() == null) {
        long stamp = frame.tryOptimisticRead();
        if (stamp == 0) {
          continue; // Exclusively locked, retry.
        }
        long fid = frame.getFileId();
        int pidx = frame.getPageIndex();

        if (frame.validate(stamp)) {
          // If validation passed, the (fid, pidx) pair must be one of the consistent
          // pairs: (0,0), (100,200), or (300,400).
          boolean consistent = (fid == 0 && pidx == 0)
              || (fid == 100 && pidx == 200)
              || (fid == 300 && pidx == 400);
          if (!consistent) {
            errors.set("Torn read detected: fileId=" + fid + ", pageIndex=" + pidx);
          }
        }
        // If validation failed, the data is discarded — that's correct behavior.
      }
    });

    writerThread.start();
    readerThread.start();
    writerThread.join(10_000);
    readerThread.join(10_000);

    // Bounded waits — assert both threads actually completed instead of silently moving on.
    Assert.assertFalse("writer thread did not complete within 10 s", writerThread.isAlive());
    Assert.assertFalse("reader thread did not complete within 10 s", readerThread.isAlive());

    assertNull("No torn reads should be observed: " + errors.get(), errors.get());

    pool.release(frame);
    pool.clear();
    allocator.checkMemoryLeaks();
  }
}
