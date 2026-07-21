package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.vmlens.api.AllInterleavings;
import com.vmlens.api.AllInterleavingsBuilder;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * VMLens interleaving test for the page-reload publication protocol (YTDB-1203): a loader
 * that fills a recycled {@code PageFrame} and publishes it via the {@link CachePointer}
 * constructor runs concurrently with a reader that still holds a stale reference to the
 * same frame from its previous page assignment.
 *
 * <p><b>What is verified.</b> The publication protocol requires that EVERY write of the
 * frame's page coordinates happens inside the frame's exclusive-lock cycle (pool release,
 * pool acquire, and — the YTDB-1203 fix — the CachePointer constructor). VMLens's data-race
 * detector is the primary assertion here: the reader accesses the coordinates under the
 * frame's shared lock, so any coordinate write outside the exclusive lock (as in the
 * pre-fix code, where the constructor used plain unlocked stores) is reported as a data
 * race and fails the build. The test additionally asserts pair consistency: the reader
 * must observe either the pooled state {@code (-1, -1)} or the fully published page
 * {@code (FILE_ID, PAGE_INDEX)} — never a mixed pair, which an unlocked publication could
 * expose mid-write.
 *
 * <p><b>VMLens visibility note.</b> The actual page fill goes to off-heap direct memory,
 * which VMLens cannot instrument — the on-heap coordinate fields of {@code PageFrame} are
 * the <em>tracked proxy</em> for the fill. The coordinates must therefore stay inside the
 * locked section: if they ever move out again, the detector goes blind to the fill's
 * publication entirely and this test is the only automated signal (via the race report on
 * the coordinate fields themselves).
 *
 * <p><b>Why the reader holds a shared lock instead of an optimistic stamp.</b> The
 * production optimistic path ({@code StorageComponent.loadPageOptimistic}) reads the
 * coordinates with NO lock and discards the result when {@code validate(stamp)} fails.
 * That validated-seqlock pattern is racy by design, and a happens-before race detector
 * like VMLens necessarily flags it — with or without the fix — so it cannot be exercised
 * under VMLens. The stamp-invalidation semantics of the optimistic path (a stamp taken
 * before the reload's fill/publication must fail validation) are covered by the
 * deterministic regression test
 * {@code CachePointerPageFrameTest#testStampFromReloadWindowMustFailValidation}.
 */
public class CachePointerPublicationMTTest {

  // VMLens exhaustively explores thread schedules, so a modest iteration cap suffices.
  // The StampedLock inside PageFrame introduces several synchronization points per
  // acquire/release cycle, which multiplies the interleaving space.
  private static final int MAX_ITERATIONS = 200;

  // Generous bound — normal iterations finish in microseconds; only a genuine deadlock
  // (e.g., a regression making the constructor's lock cycle self-deadlock) exceeds it.
  private static final long JOIN_TIMEOUT_MS = 30_000;

  private static final int PAGE_SIZE = 4096;
  private static final long FILE_ID = 1;
  private static final int PAGE_INDEX = 7;
  private static final long FILL_PATTERN = 0x1234_5678_9ABC_DEF0L;

  /**
   * Snapshot the reader takes of the stale frame under its shared lock. Published to the
   * main thread via an {@link AtomicReference}: the volatile write/read pair is a
   * happens-before edge that VMLens tracks — a plain array handoff relying on the timed
   * {@code Thread.join(long)} would be reported as a data race, because VMLens does not
   * credit timed joins with a happens-before edge.
   */
  private record Observed(long fileId, int pageIndex, long word) {
  }

  /**
   * Loader (pool-acquire, fill, publish via CachePointer constructor) vs a reader holding
   * a stale reference to the same recycled frame. Every interleaving must uphold:
   * <ul>
   *   <li>no data race on the coordinate fields (all writes inside the exclusive-lock
   *       cycle — checked by VMLens itself);</li>
   *   <li>the reader observes only complete coordinate pairs: (-1, -1) or
   *       (FILE_ID, PAGE_INDEX), never a mixed pair;</li>
   *   <li>when the reader observes the published pair, the buffer fill is complete
   *       (the fill happens-before the constructor's lock cycle).</li>
   * </ul>
   */
  @Test
  public void reloadPublicationIsAtomicUnderFrameLock() throws Exception {
    try (AllInterleavings allInterleavings =
        new AllInterleavingsBuilder()
            .withMaximumIterations(MAX_ITERATIONS)
            .build("reloadPublicationIsAtomicUnderFrameLock")) {
      while (allInterleavings.hasNext()) {
        var allocator = new DirectMemoryAllocator();
        var pool = new PageFramePool(PAGE_SIZE, allocator, 2);

        // Previous life of the frame: page (FILE_ID, PAGE_INDEX) was cached here. The
        // reader captured this frame reference (getPageFrameOptimistic equivalent) before
        // the page was evicted.
        var frame = pool.acquire(true, Intention.TEST);
        var c0 = new CachePointer(frame, pool, FILE_ID, PAGE_INDEX);
        c0.incrementReadersReferrer();
        // Eviction: last referrer releases — the frame returns to the pool with its
        // coordinates reset to (-1, -1) under an exclusive-lock cycle.
        c0.decrementReadersReferrer();

        // Loader: reload the SAME page into the SAME recycled frame — mirrors
        // WOWCache.loadFileContent (pool-acquire, fill, then construct/publish).
        var loader = new Thread(() -> {
          var reacquired = pool.acquire(true, Intention.TEST);
          reacquired.getBuffer().putLong(0, FILL_PATTERN);
          var c1 = new CachePointer(reacquired, pool, FILE_ID, PAGE_INDEX);
          // Keep the frame referenced until the iteration ends so the pool does not
          // recycle it mid-read.
          c1.incrementReadersReferrer();
        });

        // Reader: reads the stale frame's coordinates and buffer under the frame's
        // shared lock. Results are captured and asserted on the main thread after join
        // (assertions thrown inside a child thread would not fail the JUnit test).
        var observed = new AtomicReference<Observed>();
        var reader = new Thread(() -> {
          long lockStamp = frame.acquireSharedLock();
          try {
            observed.set(new Observed(
                frame.getFileId(), frame.getPageIndex(), frame.getBuffer().getLong(0)));
          } finally {
            frame.releaseSharedLock(lockStamp);
          }
        });

        // Capture any child-thread failure (exception or assertion error) so it fails
        // the test on the main thread instead of being silently swallowed, and bound the
        // joins so a regression that deadlocks inside the constructor's lock cycle fails
        // the build instead of hanging it.
        var childFailure = new AtomicReference<Throwable>();
        loader.setUncaughtExceptionHandler((t, e) -> childFailure.compareAndSet(null, e));
        reader.setUncaughtExceptionHandler((t, e) -> childFailure.compareAndSet(null, e));

        loader.start();
        reader.start();
        loader.join(JOIN_TIMEOUT_MS);
        reader.join(JOIN_TIMEOUT_MS);
        assertFalse(loader.isAlive(),
            "loader thread did not finish within " + JOIN_TIMEOUT_MS + " ms — possible "
                + "deadlock in the publication lock cycle");
        assertFalse(reader.isAlive(),
            "reader thread did not finish within " + JOIN_TIMEOUT_MS + " ms — possible "
                + "deadlock in the publication lock cycle");
        if (childFailure.get() != null) {
          fail("child thread failed", childFailure.get());
        }

        var snapshot = observed.get();
        assertNotNull(snapshot, "reader terminated without publishing its observation");

        // Pair consistency: pooled state or fully published page — never a mix. An
        // unlocked publication (the pre-fix bug) can expose fileId already set while
        // pageIndex still holds the pooled -1, or vice versa.
        var pooledPair = snapshot.fileId() == -1 && snapshot.pageIndex() == -1;
        var publishedPair =
            snapshot.fileId() == FILE_ID && snapshot.pageIndex() == PAGE_INDEX;
        assertTrue(pooledPair || publishedPair,
            "reader observed a torn coordinate pair: ("
                + snapshot.fileId() + ", " + snapshot.pageIndex() + ")");

        // If the published pair is visible, the constructor's lock cycle has completed,
        // and the buffer fill (sequenced before the constructor in the loader) must be
        // fully visible with it.
        if (publishedPair) {
          assertEquals(FILL_PATTERN, snapshot.word(),
              "published coordinates visible but the buffer fill is not — publication "
                  + "barrier broken");
        }

        pool.clear();
        allocator.checkMemoryLeaks();
      }
    }
  }
}
