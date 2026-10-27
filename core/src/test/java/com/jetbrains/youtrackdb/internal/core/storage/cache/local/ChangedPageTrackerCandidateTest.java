package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ChangedPageTrackerCandidateTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void sealedCandidatesAreOrderedUniqueLongsStrictlyBelowBound() throws Exception {
    var tracker = new ChangedPageTracker();
    long[] marks = {Long.MAX_VALUE, Long.MAX_VALUE - 1, (1L << 48) + 3,
        (long) Integer.MAX_VALUE + 1, Integer.MAX_VALUE, 32768, 32767, 65, 64, 63, 0};
    for (long page : marks) {
      tracker.mark(1, page);
      tracker.mark(1, page);
    }
    tracker.mark(2, 7);
    var context = ChangedPageTrackerBackup.begin(tracker, null, true);
    tracker.mark(1, 17);
    for (long bound : new long[] {-1, 0, 1, 63, 64, 65, 32768, 32769,
        Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 2, 1L << 49, Long.MAX_VALUE}) {
      // The oracle comes only from written marks, never from another tracker traversal.
      List<Long> expected = Arrays.stream(marks).filter(p -> p < bound).sorted().boxed().toList();
      assertEquals(expected, pages(context, context.sealHandle(), 1, bound));
    }
    assertEquals(List.of(), pages(context, context.sealHandle(), 3, Long.MAX_VALUE));
    assertEquals(List.of(7L), pages(context, context.sealHandle(), 2, Long.MAX_VALUE));
  }

  @Test
  public void pendingForeignAbandonedAndCompletedHandlesExposeNothing() throws Exception {
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 4);
    var context = ChangedPageTrackerBackup.begin(tracker, null, true);
    var pending = ChangedPageTrackerBackup.begin(tracker, null, true);
    var foreignTracker = new ChangedPageTracker();
    foreignTracker.mark(1, 9);
    var other = ChangedPageTrackerBackup.begin(foreignTracker, null, true);
    assertEquals(List.of(9L), pages(other, other.sealHandle(), 1, 10));
    assertEquals(List.of(), pages(context, null, 1, 10));
    assertEquals(List.of(), pages(context, other.sealHandle(), 1, 10));
    assertEquals(List.of(), pages(pending, context.sealHandle(), 1, 10));
    assertEquals(List.of(), pages(pending, pending.sealHandle(), 1, 10));
    ChangedPageTrackerBackup.abandon(tracker, context);
    assertEquals(List.of(), pages(context, context.sealHandle(), 1, 10));
    var next = ChangedPageTrackerBackup.begin(tracker, null, true);
    assertEquals(List.of(), pages(next, context.sealHandle(), 1, 10));
    assertEquals(List.of(4L), pages(next, next.sealHandle(), 1, 10));
    tracker.retire(next.internalSeal());
    assertEquals(List.of(), pages(next, next.sealHandle(), 1, 10));
    assertEquals(List.of(), pages(context, context.sealHandle(), 1, 10));
  }

  // Production completion revokes streaming before capture and keeps the handle stale after save.
  @Test
  public void productionCompletionHidesCandidatesBeforeCaptureAndAfterSave() throws Exception {
    var contexts = new ChangedPageTrackerBackup.Context[1];
    boolean[] captured = {false};
    var tracker = new ChangedPageTracker(new ChangedPageTracker.AllocationObserver() {
      @Override
      public void beforeAllocation(ChangedPageTracker.Allocation kind) {
      }

      @Override
      public void beforeCompletionCapture() {
        try {
          var context = contexts[0];
          assertEquals(List.of(), pages(context, context.sealHandle(), 1, 10));
          captured[0] = true;
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
      }
    });
    tracker.mark(1, 4);
    var context = ChangedPageTrackerBackup.begin(tracker, null, true);
    contexts[0] = context;
    assertEquals(List.of(4L), pages(context, context.sealHandle(), 1, 10));
    var source = new ChangedPageTrackerBackup.CoverageSource() {
      @Override
      public long sampleBoundary() {
        return 17;
      }

      @Override
      public WriteAheadLog.CutPreflight preflightCut(long fixedBoundary) {
        return new WriteAheadLog.CutPreflight(true, fixedBoundary, new LogSequenceNumber(17, 32));
      }
    };
    assertEquals(ChangedPageTrackerBackup.Status.SAVED_CONTINUITY,
        ChangedPageTrackerBackup.complete(tracker, context,
            folder.getRoot().toPath().resolve("tracker"),
            new ChangedPageTrackerBackup.CompletionFacts(true, true), source));
    assertTrue("Completion must reach the pre-capture assertion", captured[0]);
    assertEquals(List.of(), pages(context, context.sealHandle(), 1, 10));
  }

  @Test
  public void consumerIOExceptionRetainsIdentityCauseAndSuppressedFailure() throws Exception {
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 1);
    tracker.mark(1, 2);
    var context = ChangedPageTrackerBackup.begin(tracker, null, true);
    var cause = new IOException("read cause");
    var primary = new IOException("read failed", cause);
    var cleanup = new IOException("cleanup failed");
    primary.addSuppressed(cleanup);
    var count = new Counter();
    var actual = assertThrows(IOException.class,
        () -> context.forEachCandidate(context.sealHandle(), 1, 3, page -> {
          count.accept(page);
          throw primary;
        }));
    assertSame(primary, actual);
    assertSame(cause, actual.getCause());
    assertSame(cleanup, actual.getSuppressed()[0]);
    assertEquals(1, count.count);
    assertEquals(List.of(1L, 2L), pages(context, context.sealHandle(), 1, 3));
  }

  @Test
  public void deleteAndResetDuringStreamingKeepTheCapturedBitmap() throws Exception {
    for (boolean reset : new boolean[] {false, true}) {
      var tracker = new ChangedPageTracker();
      long[] marks = {0, 63, 64, 32767, 32768, (long) Integer.MAX_VALUE + 1};
      for (long page : marks) {
        tracker.mark(1, page);
      }
      var context = ChangedPageTrackerBackup.begin(tracker, null, true);
      var entered = new CountDownLatch(1);
      var resume = new CountDownLatch(1);
      var executor = Executors.newSingleThreadExecutor();
      try {
        var stream = executor.submit(() -> {
          var result = new ArrayList<Long>();
          context.forEachCandidate(context.sealHandle(), 1, Long.MAX_VALUE, page -> {
            result.add(page);
            if (page == 0) {
              entered.countDown();
              try {
                assertTrue(resume.await(30, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
              }
            }
          });
          return result;
        });
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        if (reset) {
          tracker.resetFile(1);
        } else {
          tracker.deleteFile(1);
        }
        tracker.mark(1, 9);
        resume.countDown();
        assertEquals(Arrays.stream(marks).boxed().toList(), stream.get(30, TimeUnit.SECONDS));
        assertEquals(List.of(), pages(context, context.sealHandle(), 1, Long.MAX_VALUE));
      } finally {
        resume.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  public void scratchAllocationDoesNotGrowWithCandidateCountOrIndexRange() throws Exception {
    // Measure allocated bytes, not timing. Reuse the consumer and keep oracle work outside
    // measurement. Dense and sparse high-index streams must both fit a fixed per-stream budget.
    var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    assertTrue(bean.isThreadAllocatedMemorySupported());
    boolean previouslyEnabled = bean.isThreadAllocatedMemoryEnabled();
    try {
      bean.setThreadAllocatedMemoryEnabled(true);
      var tracker = new ChangedPageTracker();
      int denseCount = 100_000;
      for (int segment = 0; segment < 4; segment++) {
        tracker.mark(1, (long) segment << 15);
      }
      for (int page = 0; page < denseCount; page++) {
        tracker.mark(2, page);
      }
      for (int segment = 0; segment < 512; segment++) {
        tracker.mark(3, (long) segment << 15);
        // Separate leaf nodes and intermediate branches across a wide long-index range.
        tracker.mark(4, (long) segment << 47);
      }
      var context = ChangedPageTrackerBackup.begin(tracker, null, true);
      var counter = new Counter();
      for (int warmup = 0; warmup < 200; warmup++) {
        for (int file = 1; file <= 4; file++) {
          context.forEachCandidate(context.sealHandle(), file, Long.MAX_VALUE, counter);
        }
      }
      long thread = Thread.currentThread().threadId();
      long baselineAllocation = 0;
      long[] expectedCounts = {4, denseCount, 512, 512};
      for (int file = 1; file <= 4; file++) {
        counter.count = 0;
        long before = bean.getThreadAllocatedBytes(thread);
        for (int stream = 0; stream < 200; stream++) {
          context.forEachCandidate(context.sealHandle(), file, Long.MAX_VALUE, counter);
        }
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        assertEquals(200L * expectedCounts[file - 1], counter.count);
        // Allow fixed agent overhead, but not growth with candidates, segments or radix nodes.
        // Across 200 streams, 32 bytes per extra segment or node exceeds the 64 KiB slack.
        assertTrue("Stream scratch exceeded fixed 512 KiB budget for file " + file + ": "
            + allocated, allocated <= 524288);
        if (file == 1) {
          baselineAllocation = allocated;
        } else {
          assertTrue("Stream allocation grew with file shape " + file + ": " + allocated,
              allocated <= baselineAllocation + 65536);
        }
      }
    } finally {
      bean.setThreadAllocatedMemoryEnabled(previouslyEnabled);
    }
  }

  private static List<Long> pages(ChangedPageTrackerBackup.Context context,
      ChangedPageTrackerBackup.SealHandle handle, int file, long bound) throws IOException {
    var result = new ArrayList<Long>();
    context.forEachCandidate(handle, file, bound, result::add);
    return result;
  }

  private static final class Counter implements ChangedPageTrackerBackup.CandidateConsumer {
    private long count;

    @Override
    public void accept(long page) {
      count++;
    }
  }
}
