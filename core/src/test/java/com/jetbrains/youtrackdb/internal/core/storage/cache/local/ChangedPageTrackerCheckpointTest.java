package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.INVALIDATED;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.NO_SAVE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.PREFLIGHT_UNAVAILABLE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.SAVED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MemoryWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.CutPreflight;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.WALFile;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.stubbing.Answer;

public class ChangedPageTrackerCheckpointTest {

  /** Observes real publisher barriers in caller tests without exposing a production test seam. */
  public static MockedConstruction<?> observeFilesystem(Answer<Object> operations) {
    return mockConstruction(ChangedPageTrackerFile.FileOperations.class,
        withSettings().defaultAnswer(operations));
  }

  private static final LogSequenceNumber COVERAGE = new LogSequenceNumber(2, 32);

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  // A real side file covers active and sealed marks before the real WAL loses segment 1.
  // The callback checks both durable publication barriers before delegating to deletion.
  @Test
  public void publicationPrecedesRealSegmentDeletionAndPreservesBothGenerations() throws Exception {
    var path = sideFile();
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 7);
    var sealed = tracker.beginBackup();
    assertNotNull(sealed);
    tracker.mark(1, 9);
    var files = new RecordingOperations();
    try (var real = createWal()) {
      rotate(real, 2);
      var coverage = real.begin(2);
      var wal = observedWal(real, tracker);
      doAnswer(call -> {
        assertTrue(tracker.saveOrderLock().isHeldByCurrentThread());
        assertTrue(Files.exists(segment(1)));
        assertEquals(List.of("write", "file force", "publish"), files.events);
        var loaded = ChangedPageTrackerFile.load(path);
        assertEquals(coverage, loaded.coverageLsn());
        var state = capture(loaded.tracker());
        assertPages(state.active(), 9);
        assertPages(state.sealed().pages(), 7);
        assertEquals(sealed.identifier(), state.sealed().identifier());
        files.events.add("cut");
        return real.cutAllSegmentsSmallerThan(call.getArgument(0));
      }).when(wal).cutAllSegmentsSmallerThan(anyLong());
      var result = tracker.checkpoint(path, wal, 2, files);
      assertEquals(SAVED, result.outcome());
      assertTrue(result.segmentsRemoved());
      assertEquals(List.of("write", "file force", "publish", "cut"), files.events);
      assertFalse(Files.exists(segment(1)));
      assertEquals(coverage, real.begin());
      assertEquals(coverage, tracker.durableCoverageLsn());
      verify(wal).cutAllSegmentsSmallerThan(2);
      verify(wal, never()).cutTill(any());
      assertUnlocked(tracker);
    }
  }

  // Across an LSN gap, coverage names segment 5 while the cut must keep its effective boundary 3.
  // The public storage-facing overload uses the real publisher and records readable WAL coverage.
  @Test
  public void publicFacadeCutsEffectiveBoundaryNotCoverageSegmentAcrossGap() throws Exception {
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 3);
    var path = sideFile();
    try (var real = createWal()) {
      real.moveLsnAfter(new LogSequenceNumber(4, 100));
      real.log(new EmptyWALRecord());
      real.flush();
      var wal = observedWal(real, tracker);
      var result = tracker.checkpoint(path, wal, 3);
      assertEquals(SAVED, result.outcome());
      assertTrue(result.segmentsRemoved());
      var loaded = ChangedPageTrackerFile.load(path);
      assertEquals(real.begin(5), loaded.coverageLsn());
      assertEquals(loaded.coverageLsn(), real.read(loaded.coverageLsn(), 1).getFirst().getLsn());
      verify(wal).cutAllSegmentsSmallerThan(3);
      verify(wal, never()).cutTill(any());
      assertFalse(Files.exists(segment(1)));
    }
  }

  // Dropping backup protection after a no-removal prediction must not raise the later cut.
  // Unsaved marks and a repeated stale request still capture and save nothing.
  @Test
  public void blockedAndStaleAttemptsSkipCaptureAndKeepBoundedCuts() throws Exception {
    var tracker = spy(new ChangedPageTracker());
    tracker.mark(1, 4);
    var files = new RecordingOperations();
    try (var real = createWal()) {
      rotate(real, 3);
      var limit = real.begin(1);
      real.addCutTillLimit(limit);
      var wal = observedWal(real, tracker);
      doAnswer(call -> {
        var plan = real.preflightCut(3);
        real.removeCutTillLimit(limit);
        return plan;
      }).when(wal).preflightCut(3);
      var blocked = tracker.checkpoint(sideFile(), wal, 3, files);
      assertEquals(NO_SAVE, blocked.outcome());
      assertFalse(blocked.segmentsRemoved());
      assertEquals(1, real.begin().getSegment());
      verify(wal).cutAllSegmentsSmallerThan(1);
      verify(tracker, never()).capture();
      verify(tracker, never()).hasUnsavedChanges(any());
      assertTrue(files.events.isEmpty());
      // A later checkpoint can advance. A stale lower request cannot force another capture.
      assertEquals(SAVED, tracker.checkpoint(sideFile(), real, 3, files).outcome());
      files.events.clear();
      tracker.mark(1, 5);
      clearInvocations(tracker);
      var stale = tracker.checkpoint(sideFile(), real, 2, files);
      assertEquals(NO_SAVE, stale.outcome());
      assertFalse(stale.segmentsRemoved());
      verify(tracker, never()).capture();
      assertTrue(files.events.isEmpty());
      assertEquals(3, real.begin().getSegment());
    }
  }

  // Already published state with matching coverage needs no capture. Removing a preflight limit
  // must not turn its effective boundary 2 into the original request 3.
  @Test
  public void unchangedDurableStateSkipsCaptureButStillCutsOnlyEffectiveBoundary()
      throws Exception {
    var path = sideFile();
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 6);
    try (var real = createWal()) {
      rotate(real, 3);
      var limit = real.begin(2);
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
          ChangedPageTrackerFile.save(path, tracker, limit));
      real.addCutTillLimit(limit);
      var observed = spy(tracker);
      var wal = observedWal(real, observed);
      doAnswer(call -> {
        var plan = real.preflightCut(3);
        real.removeCutTillLimit(limit);
        return plan;
      }).when(wal).preflightCut(3);
      var files = new RecordingOperations();
      var result = observed.checkpoint(path, wal, 3, files);
      assertEquals(NO_SAVE, result.outcome());
      assertTrue(result.segmentsRemoved());
      verify(observed, never()).capture();
      verify(wal).cutAllSegmentsSmallerThan(2);
      assertTrue(files.events.isEmpty());
      assertEquals(limit, real.begin());
      assertTrue(Files.exists(segment(2)));
    }
  }

  // A prediction can be followed by a newly blocked cut. Durable coverage remains reusable, but
  // later marks and changed coverage each require another publication before attempting deletion.
  @Test
  public void marksAndCoverageChangesSaveAgainEvenWhenEarlierCutWasBlocked() throws Exception {
    var path = sideFile();
    var tracker = spy(new ChangedPageTracker());
    var wal = removingWal();
    doReturn(false).when(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(SAVED, tracker.checkpoint(path, wal, 3).outcome());
    clearInvocations(tracker);
    var unchanged = tracker.checkpoint(path, wal, 3);
    assertEquals(NO_SAVE, unchanged.outcome());
    assertFalse(unchanged.segmentsRemoved());
    verify(tracker, never()).capture();
    tracker.mark(1, 8);
    assertEquals(SAVED, tracker.checkpoint(path, wal, 3).outcome());
    assertPages(capture(ChangedPageTrackerFile.load(path).tracker()).active(), 8);
    var later = new LogSequenceNumber(4, 32);
    when(wal.preflightCut(5)).thenReturn(new CutPreflight(true, 4, later));
    assertEquals(SAVED, tracker.checkpoint(path, wal, 5).outcome());
    assertEquals(later, ChangedPageTrackerFile.load(path).coverageLsn());
    verify(wal).cutAllSegmentsSmallerThan(4);
    assertUnlocked(tracker);
  }

  // A memory WAL has no coverage and needs no capture, save, or physical deletion.
  @Test
  public void memoryWalNoOpDoesNotRequireCoverageOrCapture() throws Exception {
    var tracker = spy(new ChangedPageTracker());
    var result = tracker.checkpoint(sideFile(), new MemoryWriteAheadLog(), 100);
    assertEquals(NO_SAVE, result.outcome());
    assertFalse(result.segmentsRemoved());
    verify(tracker, never()).capture();
    assertFalse(Files.exists(sideFile()));
    assertUnlocked(tracker);
  }

  // Both documented availability exceptions suppress only this prediction. A later checkpoint
  // uses the same tracker and real WAL successfully, with no stale save-order hold.
  @Test
  public void transientPreflightUnavailabilityCapturesNothingAndNextCheckpointRetries()
      throws Exception {
    for (var failure : List.of(new IllegalStateException("inventory unavailable"),
        new NoSuchElementException("inventory changed"))) {
      var tracker = spy(new ChangedPageTracker());
      var path = folder.newFolder().toPath().resolve("tracker");
      try (var real = createWal(folder.newFolder().toPath())) {
        rotate(real, 2);
        var wal = observedWal(real, tracker);
        doThrow(failure).doAnswer(call -> real.preflightCut(2)).when(wal).preflightCut(2);
        var files = new RecordingOperations();
        var result = tracker.checkpoint(path, wal, 2, files);
        assertEquals(PREFLIGHT_UNAVAILABLE, result.outcome());
        assertFalse(result.segmentsRemoved());
        verify(tracker, never()).capture();
        verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
        assertTrue(files.events.isEmpty());
        assertUnlocked(tracker);
        assertEquals(SAVED, tracker.checkpoint(path, wal, 2, files).outcome());
        assertEquals(2, real.begin().getSegment());
      }
    }
  }

  // Null predictions and removal without coverage are contract failures, not availability.
  @Test
  public void malformedPreflightFailsWithoutCaptureSaveOrCut() throws Exception {
    for (var plan : new CutPreflight[] {null, new CutPreflight(true, 2, null)}) {
      var tracker = spy(new ChangedPageTracker());
      var wal = mock(WriteAheadLog.class);
      when(wal.preflightCut(3)).thenReturn(plan);
      var files = new RecordingOperations();
      assertThrows(IllegalStateException.class,
          () -> tracker.checkpoint(sideFile(), wal, 3, files));
      verify(tracker, never()).capture();
      verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
      assertTrue(files.events.isEmpty());
      assertUnlocked(tracker);
    }
  }

  // I/O, unrelated runtime failures, and Errors in prediction must propagate with no cut.
  @Test
  public void otherPreflightFailuresPropagateUnchangedWithoutCut() throws Exception {
    for (var failure : List.of(new IOException("read failure"),
        new IllegalArgumentException("bad request"),
        new OutOfMemoryError("preflight allocation"))) {
      var tracker = spy(new ChangedPageTracker());
      var wal = mock(WriteAheadLog.class);
      when(wal.preflightCut(3)).thenThrow(failure);
      assertSame(failure, assertThrows(failure.getClass(),
          () -> tracker.checkpoint(sideFile(), wal, 3)));
      verify(tracker, never()).capture();
      verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
      assertUnlocked(tracker);
    }
  }

  // An IllegalStateException after prediction is a tracker contract failure. The narrow catch
  // must not convert it into an unavailable prediction or invoke the cut.
  @Test
  public void trackerContractFailureOutsidePreflightIsNotSwallowed() throws Exception {
    var tracker = spy(new ChangedPageTracker());
    var wal = removingWal();
    var failure = new IllegalStateException("save-order contract failure");
    doThrow(failure).when(tracker).hasUnsavedChanges(COVERAGE);
    assertSame(failure, assertThrows(IllegalStateException.class,
        () -> tracker.checkpoint(sideFile(), wal, 3)));
    verify(tracker, never()).capture();
    verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
    assertUnlocked(tracker);
  }

  // Every publication stage can fail. The old side name must be removed and its folder forced
  // before the same effective cut deletes real WAL. The retry saves untrusted state successfully.
  @Test
  public void publicationFailuresDurablyInvalidateBeforeRealDeletionAndAllowRetry()
      throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    for (String stage : List.of("write", "file force", "publish")) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = new ChangedPageTracker();
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
          ChangedPageTrackerFile.save(path, tracker, new LogSequenceNumber(1, 32)));
      try (var real = createWal(folder.newFolder().toPath())) {
        rotate(real, 2);
        var files = new RecordingOperations() {
          private boolean failed;

          @Override
          void event(String event) throws IOException {
            super.event(event);
            if (!failed && stage.equals(event)) {
              failed = true;
              throw new IOException("injected " + stage);
            }
          }
        };
        var wal = observedWal(real, tracker);
        doAnswer(call -> {
          assertFalse(Files.exists(path));
          assertEquals("publish", files.events.getLast());
          assertFalse(tracker.isTrusted());
          return real.cutAllSegmentsSmallerThan(call.getArgument(0));
        }).when(wal).cutAllSegmentsSmallerThan(anyLong());
        var result = tracker.checkpoint(path, wal, 2, files);
        assertEquals(INVALIDATED, result.outcome());
        assertTrue(result.segmentsRemoved());
        verify(wal).cutAllSegmentsSmallerThan(2);
        rotate(real, 3);
        assertEquals(SAVED, tracker.checkpoint(path, real, 3).outcome());
        assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
        assertUnlocked(tracker);
      }
    }
  }

  // Failed deletion and failed folder force both leave invalidation non-durable. Neither may
  // invoke a cut, even if the side name is already absent. The caller receives a checked error.
  @Test
  public void invalidationFailuresThrowAndNeverInvokeCut() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    for (boolean failDeletion : List.of(true, false)) {
      var tracker = new ChangedPageTracker();
      var path = folder.newFolder().toPath().resolve("tracker");
      Files.writeString(path, "old authority");
      var files = new RecordingOperations() {
        @Override
        void write(FileChannel channel, ChangedPageTracker.SaveState state,
            LogSequenceNumber coverage) throws IOException {
          throw new IOException("save failure");
        }

        @Override
        void delete(Path target) throws IOException {
          if (target.equals(path.toAbsolutePath()) && failDeletion) {
            throw new IOException("delete failure");
          }
          super.delete(target);
        }

        @Override
        void forceFolder(Path parent) throws IOException {
          throw new IOException("invalidation force failure");
        }
      };
      var wal = removingWal();
      var failure = assertThrows(IOException.class, () -> tracker.checkpoint(path, wal, 3, files));
      assertTrue(failure.getMessage().contains("invalidation failed"));
      verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
      assertEquals(failDeletion, Files.exists(path));
      assertFalse(tracker.isTrusted());
      assertUnlocked(tracker);
      assertEquals(SAVED, tracker.checkpoint(path, wal, 3).outcome());
    }
  }

  // Allocation Errors before and after rename retain their identity and revoke trust. Durable
  // cleanup does not convert an Error into permission to invoke a WAL cut.
  @Test
  public void publicationErrorsPropagateAndNeverInvokeCut() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    for (String stage : List.of("write", "publish")) {
      var tracker = new ChangedPageTracker();
      var path = folder.newFolder().toPath().resolve("tracker");
      Files.writeString(path, "old authority");
      var error = new OutOfMemoryError("injected " + stage);
      var files = new RecordingOperations() {
        private boolean failed;

        @Override
        void event(String event) throws IOException {
          super.event(event);
          if (!failed && stage.equals(event)) {
            failed = true;
            throw error;
          }
        }
      };
      var wal = removingWal();
      assertSame(error, assertThrows(OutOfMemoryError.class,
          () -> tracker.checkpoint(path, wal, 3, files)));
      verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
      assertFalse(Files.exists(path));
      assertFalse(tracker.isTrusted());
      assertUnlocked(tracker);
    }
  }

  // An interrupted real side-file channel causes durable invalidation. The following real cut
  // runs without that flag and restores it. The same WAL supports the next checkpoint afterward.
  @Test
  public void interruptedPublicationAllowsRealCutAndNextCheckpoint() throws Exception {
    var path = sideFile();
    var tracker = new ChangedPageTracker();
    try (var real = createWal()) {
      rotate(real, 3);
      var wal = observedWal(real, tracker);
      doAnswer(call -> {
        assertFalse("The real cutter must not inherit cancellation", Thread.currentThread()
            .isInterrupted());
        // flush() drains the normal close queue. Install one real channel at the retained
        // boundary while excluding the writer, so this cut must execute its force branch.
        var writer = (ReentrantLock) walField(real, "recordsWriterLock");
        writer.lock();
        try (var retained = spy(WALFile.createWriteWALFile(
            folder.getRoot().toPath().resolve("cut-force-probe"), 2))) {
          closeQueue(real).add(new RawPairLongObject<>(2, retained));
          ((AtomicInteger) walField(real, "fileCloseQueueSize")).incrementAndGet();
          boolean removed = real.cutAllSegmentsSmallerThan(call.getArgument(0));
          verify(retained).force(true);
          return removed;
        } finally {
          writer.unlock();
        }
      }).when(wal).cutAllSegmentsSmallerThan(anyLong());
      Thread.currentThread().interrupt();
      try {
        var result = tracker.checkpoint(path, wal, 2);
        assertEquals(INVALIDATED, result.outcome());
        assertTrue(result.segmentsRemoved());
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(Files.exists(segment(1)));
        assertTrue(Files.exists(segment(2)));
      } finally {
        Thread.interrupted();
      }
      assertEquals(SAVED, tracker.checkpoint(path, real, 3).outcome());
      assertEquals(3, real.begin().getSegment());
      assertEquals(real.begin(), ChangedPageTrackerFile.load(path).coverageLsn());
      assertUnlocked(tracker);
    }
  }

  // No-save cutting also clears inherited cancellation. A cutter IOException must propagate,
  // restore the flag, release save order, and leave the next checkpoint able to retry.
  @Test
  public void interruptedNoSaveCutFailureRestoresFlagAndUnlocksForRetry() throws Exception {
    var tracker = spy(new ChangedPageTracker());
    var wal = mock(WriteAheadLog.class);
    when(wal.preflightCut(3)).thenReturn(new CutPreflight(false, 1, null));
    var failure = new IOException("cut failure");
    when(wal.cutAllSegmentsSmallerThan(1)).thenAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      assertTrue(tracker.saveOrderLock().isHeldByCurrentThread());
      throw failure;
    });
    Thread.currentThread().interrupt();
    try {
      assertSame(failure, assertThrows(IOException.class,
          () -> tracker.checkpoint(sideFile(), wal, 3)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertUnlocked(tracker);
    verify(tracker, never()).capture();
    doReturn(false).when(wal).cutAllSegmentsSmallerThan(1);
    assertEquals(NO_SAVE, tracker.checkpoint(sideFile(), wal, 3).outcome());
  }

  // Save order spans force and the associated cut. While an older force is paused, a newer
  // checkpoint queues, but marking, a generation switch, and WAL retention registration finish.
  @Test
  public void saveOrderSerializesCheckpointsWithoutBlockingGenerationOrWalProtection()
      throws Exception {
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 7);
    var path = sideFile();
    var atForce = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var files = new RecordingOperations() {
      @Override
      void forceFile(FileChannel channel) throws IOException {
        atForce.countDown();
        await(release);
        super.forceFile(channel);
      }
    };
    try (var real = createWal(); var workers = Executors.newFixedThreadPool(3)) {
      rotate(real, 3);
      var older = workers.submit(() -> tracker.checkpoint(path, real, 2, files));
      try {
        assertTrue(atForce.await(10, TimeUnit.SECONDS));
        var newerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var started = new CountDownLatch(1);
        var newer = workers.submit(() -> {
          newerThread.set(Thread.currentThread());
          started.countDown();
          return tracker.checkpoint(path, real, 3);
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!tracker.saveOrderLock().hasQueuedThread(newerThread.get())
            && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertTrue("The newer checkpoint must queue on save order",
            tracker.saveOrderLock().hasQueuedThread(newerThread.get()));
        assertFalse(newer.isDone());
        workers.submit(() -> {
          tracker.mark(1, 8);
          assertNotNull(tracker.beginBackup());
          var limit = real.begin(1);
          real.addCutTillLimit(limit);
          real.removeCutTillLimit(limit);
        }).get(10, TimeUnit.SECONDS);
        release.countDown();
        assertEquals(SAVED, older.get(10, TimeUnit.SECONDS).outcome());
        assertEquals(SAVED, newer.get(10, TimeUnit.SECONDS).outcome());
        var loaded = ChangedPageTrackerFile.load(path);
        assertEquals(real.begin(3), loaded.coverageLsn());
        assertPages(capture(loaded.tracker()).sealed().pages(), 7, 8);
        assertEquals(3, real.begin().getSegment());
      } finally {
        release.countDown();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
    assertUnlocked(tracker);
  }

  // A real, fsync-enabled WAL measures SAVED attempts with a fresh mutation and removing
  // preflight each time. NO_SAVE covers retention blocking and already durable unchanged state.
  @Test
  @Category(SequentialTest.class)
  public void trackerPerformanceCheckpoint() throws Exception {
    TrackerPerformanceProbe.launch(CheckpointPerformanceProbe.class,
        List.of("checkpoint-save", "retention-blocked", "unchanged-state"), List.of(0),
        "totalNs", "allocatedBytes");
  }

  public static final class CheckpointPerformanceProbe {
    public static void main(String[] args) throws Exception {
      var fixture = new ChangedPageTrackerCheckpointTest();
      fixture.folder.create();
      try {
        for (String mode : List.of("checkpoint-save", "retention-blocked", "unchanged-state")) {
          fixture.measureCheckpoint(mode);
        }
        System.out.println(TrackerPerformanceProbe.COMPLETE);
      } finally {
        fixture.folder.delete();
      }
    }
  }

  private void measureCheckpoint(String mode) throws Exception {
    var series = new TrackerPerformanceProbe.Series(mode + " marks=1024 outcome="
        + (mode.equals("checkpoint-save") ? "SAVED" : "NO_SAVE"), "totalNs", "allocatedBytes");
    for (int sample = -3; sample < TrackerPerformanceProbe.samples(); sample++) {
      var directory = folder.newFolder().toPath();
      var path = directory.resolve(ChangedPageTrackerFile.FILE_NAME);
      var tracker = new ChangedPageTracker();
      for (int mark = 0; mark < 1024; mark++) {
        tracker.mark(1, mark * 64L);
      }
      try (var wal = createWal(directory)) {
        rotate(wal, 2);
        var coverage = wal.begin(2);
        if (mode.equals("retention-blocked")) {
          wal.addCutTillLimit(wal.begin(1));
        } else if (mode.equals("unchanged-state")) {
          assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
              ChangedPageTrackerFile.save(path, tracker, coverage));
          tracker.saveOrderLock().lock();
          try {
            assertFalse(tracker.hasUnsavedChanges(coverage));
          } finally {
            tracker.saveOrderLock().unlock();
          }
        } else {
          // Publish once, then mutate a previously clear bit. The attempt cannot reuse state.
          assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
              ChangedPageTrackerFile.save(path, tracker, coverage));
          tracker.mark(1, 1);
          tracker.saveOrderLock().lock();
          try {
            assertTrue(tracker.hasUnsavedChanges(coverage));
          } finally {
            tracker.saveOrderLock().unlock();
          }
        }
        assertEquals(!mode.equals("retention-blocked"), wal.preflightCut(2).removesSegments());
        long before = TrackerPerformanceProbe.allocated();
        long start = System.nanoTime();
        var result = tracker.checkpoint(path, wal, 2);
        long elapsed = System.nanoTime() - start;
        long allocated = TrackerPerformanceProbe.allocated() - before;
        assertEquals(mode.equals("checkpoint-save") ? SAVED : NO_SAVE, result.outcome());
        assertEquals(!mode.equals("retention-blocked"), result.segmentsRemoved());
        if (sample >= 0) {
          series.add(elapsed, allocated);
        }
      }
    }
    series.report();
  }

  private Path sideFile() {
    return folder.getRoot().toPath().resolve(ChangedPageTrackerFile.FILE_NAME);
  }

  private Path segment(long number) {
    return folder.getRoot().toPath().resolve(ContextConfiguration.WAL_DEFAULT_NAME
        + "." + number + ".wal");
  }

  private CASDiskWriteAheadLog createWal() throws IOException {
    return createWal(folder.getRoot().toPath());
  }

  private static CASDiskWriteAheadLog createWal(Path directory) throws IOException {
    // Enable fsync so the real cutter exercises its channel-force path when closing segments.
    return new CASDiskWriteAheadLog("trackerCheckpoint", directory, directory,
        ContextConfiguration.WAL_DEFAULT_NAME, 100, 64, null, null, Integer.MAX_VALUE,
        Integer.MAX_VALUE, 20, true, Locale.US, -1, 1000, false, true, false, 10);
  }

  private static Object walField(CASDiskWriteAheadLog wal, String name) throws Exception {
    var field = CASDiskWriteAheadLog.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(wal);
  }

  @SuppressWarnings("unchecked")
  private static ConcurrentLinkedQueue<RawPairLongObject<WALFile>> closeQueue(
      CASDiskWriteAheadLog wal) throws Exception {
    return (ConcurrentLinkedQueue<RawPairLongObject<WALFile>>) walField(wal, "fileCloseQueue");
  }

  private static void rotate(CASDiskWriteAheadLog wal, long segment) {
    while (wal.activeSegment() < segment) {
      wal.appendNewSegment();
    }
    wal.flush();
  }

  private static WriteAheadLog observedWal(CASDiskWriteAheadLog real, ChangedPageTracker tracker)
      throws IOException {
    var wal = mock(WriteAheadLog.class);
    when(wal.preflightCut(anyLong())).thenAnswer(call -> {
      assertTrue(tracker.saveOrderLock().isHeldByCurrentThread());
      return real.preflightCut(call.getArgument(0));
    });
    when(wal.cutAllSegmentsSmallerThan(anyLong())).thenAnswer(call -> {
      assertTrue(tracker.saveOrderLock().isHeldByCurrentThread());
      return real.cutAllSegmentsSmallerThan(call.getArgument(0));
    });
    return wal;
  }

  private static WriteAheadLog removingWal() throws IOException {
    var wal = mock(WriteAheadLog.class);
    when(wal.preflightCut(3)).thenReturn(new CutPreflight(true, 2, COVERAGE));
    when(wal.cutAllSegmentsSmallerThan(2)).thenReturn(true);
    return wal;
  }

  private static ChangedPageTracker.SaveState capture(ChangedPageTracker tracker) {
    tracker.saveOrderLock().lock();
    try {
      return tracker.capture();
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static void assertPages(ChangedPageTracker.Generation generation, long... expected) {
    var pages = new ArrayList<Long>();
    generation.forEachCandidate(1, pages::add);
    assertEquals(Arrays.stream(expected).boxed().toList(), pages);
  }

  private static void assertUnlocked(ChangedPageTracker tracker) {
    assertFalse(tracker.saveOrderLock().isLocked());
  }

  private static void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IOException("Checkpoint test barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException(interrupted);
    }
  }

  private static class RecordingOperations extends ChangedPageTrackerFile.FileOperations {

    final List<String> events = new ArrayList<>();

    void event(String event) throws IOException {
      events.add(event);
    }

    @Override
    void write(FileChannel channel, ChangedPageTracker.SaveState state, LogSequenceNumber coverage)
        throws IOException {
      event("write");
      super.write(channel, state, coverage);
    }

    @Override
    void forceFile(FileChannel channel) throws IOException {
      super.forceFile(channel);
      event("file force");
    }

    @Override
    void forceFolder(Path parent) throws IOException {
      super.forceFolder(parent);
      event("publish");
    }

    @Override
    void windowsMove(Path temporary, Path target) throws IOException {
      super.windowsMove(temporary, target);
      event("publish");
    }
  }
}
