package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link OptimisticReadStats} — the JVM-global diagnostic counters wired into
 * the optimistic read path (YTDB-1203 benchmark harness).
 *
 * <p>The counters are static and shared across the whole JVM, and core tests run with
 * {@code parallel=classes}, so other concurrently running test classes may legitimately
 * bump them. All assertions are therefore delta-based with {@code >=} — a concurrent
 * increment can only make the observed delta larger, never smaller.
 */
public class OptimisticReadStatsTest {

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
  public void testEpochAbortIncrementsEpochCounter() {
    // An optimistic read whose stamps are all valid but that overlaps a commit apply
    // phase must be counted as an EPOCH abort (not a stamp abort) — this split is the
    // key evidence the YTDB-1203 benchmark relies on.
    var epoch = new ApplyPhaseEpoch();
    var scope = new OptimisticReadScope(epoch);
    var frame = pool.acquire(true, Intention.TEST);

    scope.reset();
    scope.record(frame, frame.tryOptimisticRead());

    long epochBefore = OptimisticReadStats.epochAborts();

    epoch.enterApplyPhase();
    try {
      scope.validateOrThrow();
      fail("Expected OptimisticReadFailedException — apply phase overlapped the read");
    } catch (OptimisticReadFailedException expected) {
      // expected — the epoch check fails while the stamp is still valid
    } finally {
      epoch.exitApplyPhase();
    }

    assertTrue("epoch abort counter must have been incremented",
        OptimisticReadStats.epochAborts() >= epochBefore + 1);

    releaseFrame(frame);
  }

  @Test
  public void testStampAbortIncrementsStampCounter() {
    // An optimistic read whose per-page stamp was invalidated (page modified) must be
    // counted as a STAMP abort by validateOrThrow().
    var scope = new OptimisticReadScope();
    var frame = pool.acquire(true, Intention.TEST);

    scope.reset();
    scope.record(frame, frame.tryOptimisticRead());

    // Invalidate the stamp by cycling the exclusive lock
    long writeStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(writeStamp);

    long stampBefore = OptimisticReadStats.stampAborts();
    try {
      scope.validateOrThrow();
      fail("Expected OptimisticReadFailedException — stamp was invalidated");
    } catch (OptimisticReadFailedException expected) {
      // expected
    }

    assertTrue("stamp abort counter must have been incremented",
        OptimisticReadStats.stampAborts() >= stampBefore + 1);

    releaseFrame(frame);
  }

  @Test
  public void testValidateLastOrThrowFailureCountsAsStampAbort() {
    // validateLastOrThrow() is the mid-traversal early check — its failures are also
    // stamp aborts (it never consults the epoch).
    var scope = new OptimisticReadScope();
    var frame = pool.acquire(true, Intention.TEST);

    scope.reset();
    scope.record(frame, frame.tryOptimisticRead());

    long writeStamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(writeStamp);

    long stampBefore = OptimisticReadStats.stampAborts();
    try {
      scope.validateLastOrThrow();
      fail("Expected OptimisticReadFailedException — last stamp was invalidated");
    } catch (OptimisticReadFailedException expected) {
      // expected
    }

    assertTrue("stamp abort counter must have been incremented",
        OptimisticReadStats.stampAborts() >= stampBefore + 1);

    releaseFrame(frame);
  }

  @Test
  public void testHappyPathValidationDoesNotAbort() {
    // A fully valid optimistic read must not be counted as any kind of abort. Because
    // the counters are global and other test classes run concurrently, we cannot assert
    // an exact zero delta — instead we pump a large number of clean validations and
    // assert the counters grew by (far) less than that, which would be impossible if
    // the happy path incremented them.
    var scope = new OptimisticReadScope();
    var frame = pool.acquire(true, Intention.TEST);

    final int iterations = 10_000;
    long stampBefore = OptimisticReadStats.stampAborts();
    long epochBefore = OptimisticReadStats.epochAborts();

    for (int i = 0; i < iterations; i++) {
      scope.reset();
      scope.record(frame, frame.tryOptimisticRead());
      scope.validateOrThrow();
    }

    assertTrue("happy-path validations must not bump the stamp abort counter",
        OptimisticReadStats.stampAborts() - stampBefore < iterations);
    assertTrue("happy-path validations must not bump the epoch abort counter",
        OptimisticReadStats.epochAborts() - epochBefore < iterations);

    releaseFrame(frame);
  }

  @Test
  public void testResetClearsCounters() {
    // Pump a large, known number of epoch aborts, then reset and verify the counter
    // dropped below the pumped amount. A concurrent test class would need to produce
    // the same volume of epoch aborts within microseconds to break this — implausible.
    var epoch = new ApplyPhaseEpoch();
    var scope = new OptimisticReadScope(epoch);

    final int pumped = 10_000;
    epoch.enterApplyPhase();
    try {
      for (int i = 0; i < pumped; i++) {
        scope.reset();
        try {
          scope.validateOrThrow();
          fail("Expected OptimisticReadFailedException — apply phase is in flight");
        } catch (OptimisticReadFailedException expected) {
          // expected — counted as an epoch abort
        }
      }
    } finally {
      epoch.exitApplyPhase();
    }

    assertTrue("pumped epoch aborts must be visible before reset",
        OptimisticReadStats.epochAborts() >= pumped);

    OptimisticReadStats.reset();

    assertTrue("reset must clear the epoch abort counter",
        OptimisticReadStats.epochAborts() < pumped);
    assertTrue("reset must clear the stamp abort counter",
        OptimisticReadStats.stampAborts() < pumped);
    assertTrue("reset must clear the fallback counter",
        OptimisticReadStats.fallbacks() < pumped);
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
