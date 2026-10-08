package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

public class ChangedPageTrackerFileTest {

  private static final LogSequenceNumber COVERAGE = new LogSequenceNumber(17, 32);

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  // Real side-file publications measure codec/channel writes and both durability barriers.
  // Sparse, one-bit-per-word, and all-bits-set inputs each cover one and two generations.
  @Test
  @Category(SequentialTest.class)
  public void trackerPerformancePublication() throws Exception {
    TrackerPerformanceProbe.launch(PublicationPerformanceProbe.class,
        List.of("sparse", "one-bit-per-word", "all-bits-set"), List.of(1, 2),
        "encodeWriteNs", "totalNs", "fileForceNs", "publicationBarrierNs", "allocatedBytes");
  }

  public static final class PublicationPerformanceProbe {
    public static void main(String[] args) throws Exception {
      for (String density : List.of("sparse", "one-bit-per-word", "all-bits-set")) {
        for (int generations = 1; generations <= 2; generations++) {
          measure(density, generations);
        }
      }
      System.out.println(TrackerPerformanceProbe.COMPLETE);
    }

    private static void measure(String density, int generations) throws Exception {
      int segments = density.equals("sparse") ? 1024 : 16;
      int words = density.equals("sparse") ? 1 : 512;
      int bits = density.equals("all-bits-set") ? 64 : 1;
      var tracker = new ChangedPageTracker();
      for (int generation = 0; generation < generations; generation++) {
        for (int segment = 0; segment < segments; segment++) {
          for (int word = 0; word < words; word++) {
            for (int bit = 0; bit < bits; bit++) {
              tracker.mark(1, segment * 32768L + word * 64L + bit);
            }
          }
        }
        if (generation + 1 < generations) {
          assertNotNull(tracker.beginBackup());
        }
      }
      assertWorkload(tracker, density, generations);
      var directory = Files.createTempDirectory("tracker-publication");
      var path = directory.resolve(ChangedPageTrackerFile.FILE_NAME);
      try {
        var timing = new PublicationTiming();
        assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
            ChangedPageTrackerFile.save(path, tracker, COVERAGE));
        long wire = Files.size(path);
        long nonzero = (long) words * segments * generations;
        assertTrue(wire >= nonzero * 20 && wire < nonzero * 20 + 128);
        var series = new TrackerPerformanceProbe.Series(density + " generations=" + generations
            + " segmentsPerGeneration=" + segments + " nonzeroWords=" + nonzero
            + " payloadBytes=" + (long) segments * generations * ChangedPageTracker.SEGMENT_BYTES
            + " fileBytes=" + wire + " barrier="
            + (IOUtils.isOsWindows() ? "windows-move" : "folder-force"),
            "encodeWriteNs", "totalNs", "fileForceNs", "publicationBarrierNs", "allocatedBytes");
        for (int sample = -3; sample < TrackerPerformanceProbe.samples(); sample++) {
          timing.calls = 0;
          long before = TrackerPerformanceProbe.allocated();
          long start = System.nanoTime();
          var result = ChangedPageTrackerFile.save(path, tracker, COVERAGE, timing);
          long elapsed = System.nanoTime() - start;
          long allocated = TrackerPerformanceProbe.allocated() - before;
          assertEquals(ChangedPageTrackerFile.SaveResult.SAVED, result);
          assertEquals(3, timing.calls);
          assertEquals(wire, Files.size(path));
          if (sample >= 0) {
            series.add(timing.encode, elapsed, timing.fileForce, timing.barrier, allocated);
          }
        }
        var loaded = ChangedPageTrackerFile.load(path);
        assertEquals(COVERAGE, loaded.coverageLsn());
        assertWorkload(loaded.tracker(), density, generations);
        series.report();
      } finally {
        Files.deleteIfExists(ChangedPageTrackerFile.temporaryFile(path));
        Files.deleteIfExists(path);
        Files.delete(directory);
      }
    }
  }

  // Word positions and masks come from the label, not the setup loop's bit-count variable.
  // Check both the input and decoded publication outside the measured save calls.
  private static void assertWorkload(ChangedPageTracker tracker, String density, int generations) {
    var state = capture(tracker);
    assertDensity(state.active(), density);
    if (generations == 2) {
      assertNotNull(state.sealed());
      assertDensity(state.sealed().pages(), density);
    } else {
      assertNull(state.sealed());
    }
  }

  private static void assertDensity(ChangedPageTracker.Generation generation, String density) {
    long[] counts = new long[2];
    generation.forEachFile((file, bitmap) -> {
      assertEquals(1, file.intValue());
      counts[0]++;
      bitmap.forEachWord((index, value) -> {
        assertEquals(density.equals("sparse") ? counts[1] * 512 : counts[1], index);
        assertEquals(density.equals("all-bits-set") ? -1L : 1L, value);
        counts[1]++;
      });
    });
    assertEquals(1, counts[0]);
    assertEquals(density.equals("sparse") ? 1024 : 8192, counts[1]);
  }

  private static final class PublicationTiming extends ChangedPageTrackerFile.FileOperations {
    long encode;
    long fileForce;
    long barrier;
    int calls;

    @Override
    void write(FileChannel channel, ChangedPageTracker.SaveState state, LogSequenceNumber coverage)
        throws IOException {
      long start = System.nanoTime();
      super.write(channel, state, coverage);
      encode = System.nanoTime() - start;
      calls++;
    }

    @Override
    void forceFile(FileChannel channel) throws IOException {
      long start = System.nanoTime();
      super.forceFile(channel);
      fileForce = System.nanoTime() - start;
      calls++;
    }

    @Override
    void forceFolder(Path parent) throws IOException {
      long start = System.nanoTime();
      super.forceFolder(parent);
      barrier = System.nanoTime() - start;
      calls++;
    }

    @Override
    void windowsMove(Path temporary, Path target) throws IOException {
      long start = System.nanoTime();
      super.windowsMove(temporary, target);
      barrier = System.nanoTime() - start;
      calls++;
    }
  }

  // The shared exact-name rule matches the real publisher temporary sibling. It does not claim
  // unrelated files that happen to use the same extension or a longer suffix.
  @Test
  public void sharedNamesMatchPublisherAndSuccessfulSaveRecordsDurableVersion() throws Exception {
    var path = folder.getRoot().toPath().resolve(ChangedPageTrackerFile.FILE_NAME);
    assertTrue(ChangedPageTrackerFile.isTrackerFile(path.getFileName().toString()));
    var temporary = ChangedPageTrackerFile.temporaryFile(path);
    assertEquals(ChangedPageTrackerFile.TEMPORARY_FILE_NAME, temporary.getFileName().toString());
    assertTrue(ChangedPageTrackerFile.isTrackerFile(temporary.getFileName().toString()));
    assertFalse(ChangedPageTrackerFile.isTrackerFile("unrelated.cpt"));
    assertFalse(ChangedPageTrackerFile.isTrackerFile(ChangedPageTrackerFile.FILE_NAME + ".bak"));
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 7);
    assertTrue(hasUnsavedChanges(tracker, COVERAGE));
    assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
        ChangedPageTrackerFile.save(path, tracker, COVERAGE));
    assertFalse(hasUnsavedChanges(tracker, COVERAGE));
    assertEquals(COVERAGE, tracker.durableCoverageLsn());
    var loaded = ChangedPageTrackerFile.load(path).tracker();
    assertEquals(COVERAGE, loaded.durableCoverageLsn());
    assertFalse(hasUnsavedChanges(loaded, COVERAGE));
    loaded.mark(1, 7);
    assertFalse(hasUnsavedChanges(loaded, COVERAGE));
    loaded.mark(1, 8);
    assertTrue(hasUnsavedChanges(loaded, COVERAGE));
    assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
        ChangedPageTrackerFile.save(path, loaded, COVERAGE));
    var failedCoverage = new LogSequenceNumber(18, 32);
    var operations = new ChangedPageTrackerFile.FileOperations() {
      @Override
      void write(FileChannel channel, ChangedPageTracker.SaveState state,
          LogSequenceNumber coverage) throws IOException {
        throw new IOException("injected write failure");
      }
    };
    assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED,
        ChangedPageTrackerFile.save(path, loaded, failedCoverage, operations));
    assertEquals(COVERAGE, loaded.durableCoverageLsn());
    assertTrue(hasUnsavedChanges(loaded, COVERAGE));
    assertTrue(hasUnsavedChanges(loaded, failedCoverage));
  }

  private static boolean hasUnsavedChanges(ChangedPageTracker tracker,
      LogSequenceNumber coverage) {
    tracker.saveOrderLock().lock();
    try {
      return tracker.hasUnsavedChanges(coverage);
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  // Real NIO publication replaces an old file, ignores crash-left temporary content and preserves
  // both generations, continuity identifiers and the supplied retained-record coverage LSN.
  @Test
  public void durablePublicationRoundTripsThroughRealFilesystemInForceOrder() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    var path = folder.getRoot().toPath().resolve("tracker");
    var tracker = trustedTracker();
    var expected = capture(tracker);
    Files.writeString(path, "old side file");
    Files.writeString(temporary(path), "crash-left temporary file");
    var operations = new RecordingOperations(path);
    tracker.saveOrderLock().lock();
    try {
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations));
      // The save is reentrant. A future checkpoint can keep ordering through its associated cut.
      assertTrue(tracker.saveOrderLock().isHeldByCurrentThread());
    } finally {
      tracker.saveOrderLock().unlock();
    }
    assertEquals(List.of("delete temporary", "write", "file force", "move", "folder force"),
        operations.events);
    assertFalse(Files.exists(temporary(path)));
    var loaded = ChangedPageTrackerFile.load(path);
    var actual = capture(loaded.tracker());
    assertTrue(loaded.tracker().isTrusted());
    assertEquals(COVERAGE, loaded.coverageLsn());
    assertEquals(expected.trackerIdentifier(), actual.trackerIdentifier());
    assertEquals(expected.lastCompletedIdentifier(), actual.lastCompletedIdentifier());
    assertEquals(expected.sealed().identifier(), actual.sealed().identifier());
    assertPages(actual.active(), 1, 0, 1);
    assertPages(actual.sealed().pages(), 1, 32770);
    assertTrue(ChangedPageTrackerFile.SaveResult.SAVED.allowsWalCut());
  }

  // Checkpoint publication records unknown history as untrusted. It installs a fresh identifier
  // but cannot repair missing history, including after a save failure and durable invalidation.
  @Test
  public void checkpointSavesCannotRecoverUntrustedHistory() throws Exception {
    var path = folder.getRoot().toPath().resolve("tracker");
    var tracker = trustedTracker();
    var old = capture(tracker).trackerIdentifier();
    tracker.invalidate();
    assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
        ChangedPageTrackerFile.save(path, tracker, COVERAGE));
    var loaded = ChangedPageTrackerFile.load(path);
    var saved = capture(loaded.tracker());
    assertFalse(saved.trusted());
    assertFalse(tracker.isTrusted());
    assertNull(saved.lastCompletedIdentifier());
    assertFalse(old.equals(saved.trackerIdentifier()));
    assertEquals(saved.trackerIdentifier(), capture(tracker).trackerIdentifier());
    assertEquals(COVERAGE, loaded.coverageLsn());
    assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
        ChangedPageTrackerFile.save(path, tracker, new LogSequenceNumber(18, 32)));
    assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
  }

  // Every publication-stage I/O failure removes old authority and forces that removal. Atomic
  // move and folder force lack of support are failures too. No non-atomic retry is permitted.
  @Test
  public void publicationFailuresDurablyInvalidateOldAuthorityWithoutFallback() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    for (String stage : List.of("write", "file force", "move", "folder force",
        "atomic unsupported", "folder unsupported", "delete temporary")) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE));
      var operations = new RecordingOperations(path) {
        private boolean failed;

        @Override
        void event(String operation) throws IOException {
          super.event(operation);
          if (!failed && (operation.equals(stage)
              || operation.equals("move") && stage.equals("atomic unsupported")
              || operation.equals("folder force") && stage.equals("folder unsupported"))) {
            failed = true;
            if (stage.equals("atomic unsupported")) {
              throw new AtomicMoveNotSupportedException("temporary", "target", "injected");
            }
            if (stage.equals("folder unsupported")) {
              throw new UnsupportedOperationException("injected folder force unsupported");
            }
            throw new IOException("injected " + stage);
          }
        }
      };
      var result = ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations);
      assertEquals(stage, ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED, result);
      assertTrue(result.allowsWalCut());
      assertFalse(tracker.isTrusted());
      assertFalse(Files.exists(path));
      assertNull(ChangedPageTrackerFile.load(path).coverageLsn());
      int size = operations.events.size();
      assertEquals(List.of("delete side", "folder force"),
          operations.events.subList(size - 2, size));
      assertTrue(operations.events.stream().filter("move"::equals).count() <= 1);
      // A failed attempt can leave a temporary file. Only the real side-file path has authority.
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE));
      assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
      assertFalse(Files.exists(temporary(path)));
    }
  }

  // Delete failure and folder-force failure are distinct unsafe outcomes. Even when the name
  // disappeared, a failed folder force must stop the cut. Successful invalidation is idempotent.
  @Test
  public void invalidationRequiresDurableDeletionAndRejectsBothFailureStages() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    for (String stage : List.of("delete side", "folder force")) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE));
      var operations = new RecordingOperations(path) {
        @Override
        void event(String operation) throws IOException {
          super.event(operation);
          if (operation.equals(stage)) {
            throw new IOException("injected invalidation " + stage);
          }
        }
      };
      var invalidation = ChangedPageTrackerFile.invalidate(path, tracker, operations);
      assertEquals(ChangedPageTrackerFile.InvalidationResult.FAILED, invalidation);
      assertFalse(invalidation.allowsWalCut());
      assertFalse(tracker.isTrusted());
      assertEquals(stage.equals("delete side"), Files.exists(path));
      assertEquals(ChangedPageTrackerFile.InvalidationResult.INVALIDATED,
          ChangedPageTrackerFile.invalidate(path, tracker));
      assertFalse(Files.exists(path));
      var retry = new RecordingOperations(path);
      assertTrue(ChangedPageTrackerFile.invalidate(path, tracker, retry).allowsWalCut());
      assertEquals(List.of("delete side", "folder force"), retry.events);
    }
  }

  // A save failure cannot allow a cut when deletion or its force also fails. The old file may
  // survive, but the caller sees the unsafe result and must retain WAL for startup coverage proof.
  @Test
  public void saveAndInvalidationDoubleFailuresNeverPermitWalCut() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    for (String stage : List.of("delete side", "folder force")) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE));
      var operations = new RecordingOperations(path) {
        @Override
        void event(String operation) throws IOException {
          super.event(operation);
          if (operation.equals("write") || operation.equals(stage)) {
            throw new IOException("injected double failure " + operation);
          }
        }
      };
      var result = ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations);
      assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATION_FAILED, result);
      assertFalse(result.allowsWalCut());
      assertFalse(tracker.isTrusted());
      assertEquals(stage.equals("delete side"), Files.exists(path));
      assertEquals(ChangedPageTrackerFile.InvalidationResult.INVALIDATED,
          ChangedPageTrackerFile.invalidate(path, tracker));
    }
  }

  // Pause each durability boundary, including Windows replacement. The newer save must queue
  // until completion. Both workers are joined and the final disk and memory identities must agree.
  @Test
  public void slowOlderSaveCannotOvertakeNewerCaptureAndPublication() throws Exception {
    for (String stage : List.of("file force", "folder force", "windows move")) {
      if (!stage.equals("windows move") && IOUtils.isOsWindows()) {
        continue;
      }
      assertSaveOrdering(folder.newFolder().toPath().resolve("tracker"), stage);
    }
  }

  private void assertSaveOrdering(Path path, String stage) throws Exception {
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 0);
    var entered = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var newerStarted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    var operations = new WindowsOperations(path) {
      @Override
      boolean windows() {
        return stage.equals("windows move");
      }

      @Override
      void event(String operation) throws IOException {
        super.event(operation);
        if (operation.equals(stage)) {
          entered.countDown();
          await(released);
        }
      }
    };
    var older = executor.submit(() -> ChangedPageTrackerFile.save(path, tracker, COVERAGE,
        operations));
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      tracker.mark(1, 1);
      assertNotNull(tracker.beginBackup());
      var newer = executor.submit(() -> {
        newerStarted.countDown();
        return ChangedPageTrackerFile.save(path, tracker, new LogSequenceNumber(18, 32),
            stage.equals("windows move") ? new WindowsOperations(path)
                : new RecordingOperations(path));
      });
      assertTrue(newerStarted.await(10, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!tracker.saveOrderLock().hasQueuedThreads() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertTrue(tracker.saveOrderLock().hasQueuedThreads());
      assertFalse(newer.isDone());
      released.countDown();
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED, older.get(10, TimeUnit.SECONDS));
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED, newer.get(10, TimeUnit.SECONDS));
      var loaded = ChangedPageTrackerFile.load(path);
      assertEquals(new LogSequenceNumber(18, 32), loaded.coverageLsn());
      var disk = capture(loaded.tracker());
      assertPages(disk.sealed().pages(), 1, 0, 1);
      assertEquals(disk.trackerIdentifier(), capture(tracker).trackerIdentifier());
    } finally {
      released.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  // Real failed marks at each side of rename invalidate a previously trusted capture. The first
  // fence blocks rename, while the saveSucceeded fence invalidates a file already published.
  @Test
  public void markFailureDuringEitherForceCannotCertifyTrustedCapture() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    for (String stage : List.of("file force", "folder force")) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE));
      var entered = new CountDownLatch(1);
      var released = new CountDownLatch(1);
      var operations = new RecordingOperations(path) {
        private boolean paused;

        @Override
        void event(String operation) throws IOException {
          super.event(operation);
          if (!paused && operation.equals(stage)) {
            paused = true;
            entered.countDown();
            await(released);
          }
        }
      };
      var executor = Executors.newSingleThreadExecutor();
      var saved = executor.submit(() -> ChangedPageTrackerFile.save(path, tracker, COVERAGE,
          operations));
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> tracker.mark(1, -1));
        assertFalse(tracker.isTrusted());
        released.countDown();
        assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED,
            saved.get(10, TimeUnit.SECONDS));
        assertEquals(stage.equals("folder force"), operations.events.contains("move"));
        assertFalse(Files.exists(path));
        assertNull(ChangedPageTrackerFile.load(path).coverageLsn());
      } finally {
        released.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }

  // Errors before and after rename revoke trust, remove authority and preserve throwable identity.
  @Test
  public void allocationErrorsInvalidateAuthorityBeforePropagating() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    for (String stage : List.of("write", "file force", "folder force")) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      Files.write(path, encode(capture(tracker)));
      var failure = new OutOfMemoryError("injected " + stage);
      var operations = new RecordingOperations(path) {
        private boolean failed;

        @Override
        void event(String operation) throws IOException {
          super.event(operation);
          if (!failed && operation.equals(stage)) {
            failed = true;
            throw failure;
          }
        }
      };
      assertSame(failure, assertThrows(OutOfMemoryError.class,
          () -> ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations)));
      assertFalse(tracker.isTrusted());
      assertFalse(Files.exists(path));
      assertFalse(tracker.saveOrderLock().isLocked());
      assertEquals(stage.equals("folder force"), operations.events.contains("move"));
      assertEquals(1, operations.warnings.size());
      assertSame(failure, operations.warnings.getFirst().cause());
    }
  }

  // A secondary cleanup failure is suppressed on the original Error. Direct invalidation also
  // retries its cleanup on Error, but still propagates that Error and never returns a cut result.
  @Test
  public void errorsPreserveSecondaryCleanupCausesAndDirectInvalidationRetries() throws Exception {
    var path = folder.getRoot().toPath().resolve("tracker");
    var tracker = trustedTracker();
    Files.write(path, encode(capture(tracker)));
    var primary = new OutOfMemoryError("write failure");
    var secondary = new IOException("tombstone move failure");
    var operations = new WindowsOperations(path) {
      @Override
      void write(FileChannel channel, ChangedPageTracker.SaveState state,
          LogSequenceNumber coverage) {
        throw primary;
      }

      @Override
      void windowsMove(Path temporary, Path target) throws IOException {
        throw secondary;
      }
    };
    assertSame(primary, assertThrows(OutOfMemoryError.class,
        () -> ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations)));
    assertArrayEquals(new Throwable[] {secondary}, primary.getSuppressed());
    assertFalse(tracker.isTrusted());
    var directError = new OutOfMemoryError("invalidation failure");
    var retry = new WindowsOperations(path) {
      private boolean failed;

      @Override
      void forceFile(FileChannel channel) throws IOException {
        if (!failed) {
          failed = true;
          throw directError;
        }
        super.forceFile(channel);
      }
    };
    assertSame(directError, assertThrows(OutOfMemoryError.class,
        () -> ChangedPageTrackerFile.invalidate(path, tracker, retry)));
    assertEquals(0, Files.size(path));
    assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
  }

  // Cleanup Errors never become safe results. Direct invalidation preserves an Error even if its
  // retry fails. Reusing a throwable in logging must not attempt illegal self-suppression.
  @Test
  public void cleanupErrorsPropagateAndInvalidationRetryFailuresStaySuppressed() throws Exception {
    var path = folder.getRoot().toPath().resolve("tracker");
    var tracker = trustedTracker();
    var original = new IOException("save failed");
    var cleanup = new OutOfMemoryError("cleanup failed");
    var operations = new WindowsOperations(path) {
      @Override
      void write(FileChannel channel, ChangedPageTracker.SaveState state,
          LogSequenceNumber coverage) throws IOException {
        throw original;
      }

      @Override
      void windowsMove(Path temporary, Path target) {
        throw cleanup;
      }

      @Override
      void warn(String message, Throwable cause) {
        throw (Error) (cause instanceof Error ? cause : cleanup);
      }
    };
    assertSame(cleanup, assertThrows(OutOfMemoryError.class,
        () -> ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations)));
    assertTrue(Arrays.asList(cleanup.getSuppressed()).contains(original));
    assertFalse(tracker.isTrusted());
    assertFalse(tracker.saveOrderLock().isLocked());
    var direct = new AssertionError("direct invalidation failed");
    var retry = new IOException("retry failed");
    var retryOperations = new WindowsOperations(path) {
      private boolean failed;

      @Override
      void windowsMove(Path temporary, Path target) throws IOException {
        if (!failed) {
          failed = true;
          throw direct;
        }
        throw retry;
      }
    };
    assertSame(direct, assertThrows(AssertionError.class,
        () -> ChangedPageTrackerFile.invalidate(path, tracker, retryOperations)));
    assertArrayEquals(new Throwable[] {retry}, direct.getSuppressed());
  }

  // Locally owned diagnostics capture both messages and original causes. A throwing logger cannot
  // skip cleanup, alter a safe or unsafe outcome, or replace a propagating allocation Error.
  @Test
  public void diagnosticsCannotChangeDurabilityOutcomesOrHideOriginalCauses() throws Exception {
    for (boolean cleanupFails : List.of(false, true)) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      Files.write(path, encode(capture(tracker)));
      var primary = new IOException("encoding failed");
      var secondary = new IOException("replacement failed");
      var loggerFailure = new IllegalStateException("logger failed");
      var operations = new WindowsOperations(path) {
        @Override
        void write(FileChannel channel, ChangedPageTracker.SaveState state,
            LogSequenceNumber coverage) throws IOException {
          throw primary;
        }

        @Override
        void windowsMove(Path temporary, Path target) throws IOException {
          assertFalse(tracker.isTrusted());
          if (cleanupFails) {
            throw secondary;
          }
          super.windowsMove(temporary, target);
        }

        @Override
        void warn(String message, Throwable failure) {
          assertFalse(tracker.isTrusted());
          if (!cleanupFails) {
            assertEquals(0, size(path));
          }
          super.warn(message, failure);
          throw loggerFailure;
        }
      };
      assertEquals(cleanupFails ? ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATION_FAILED
          : ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations));
      assertEquals(cleanupFails ? 2 : 1, operations.warnings.size());
      var saveWarning = operations.warnings.getLast();
      assertEquals("Failed to save changed-page tracker side file: " + path,
          saveWarning.message());
      assertSame(primary, saveWarning.cause());
      assertTrue(Arrays.asList(primary.getSuppressed()).contains(loggerFailure));
      if (cleanupFails) {
        assertEquals("Failed to invalidate changed-page tracker side file: " + path,
            operations.warnings.getFirst().message());
        assertSame(secondary, operations.warnings.getFirst().cause());
        assertEquals(ChangedPageTrackerFile.InvalidationResult.FAILED,
            ChangedPageTrackerFile.invalidate(path, tracker, operations));
      }
    }
    var path = folder.newFolder().toPath().resolve("tracker");
    var primary = new OutOfMemoryError("encoding error");
    var logging = new AssertionError("logging error");
    var operations = new WindowsOperations(path) {
      @Override
      void write(FileChannel channel, ChangedPageTracker.SaveState state,
          LogSequenceNumber coverage) {
        throw primary;
      }

      @Override
      void warn(String message, Throwable failure) {
        throw logging;
      }
    };
    assertSame(primary, assertThrows(OutOfMemoryError.class,
        () -> ChangedPageTrackerFile.save(path, trustedTracker(), COVERAGE, operations)));
    assertArrayEquals(new Throwable[] {logging}, primary.getSuppressed());
  }

  // Interrupted channel I/O leaves the flag set. Cleanup clears it only for its required barriers
  // and restores it before returning a durably invalidated, safe tracker result.
  @Test
  public void interruptedSaveClearsFlagForCleanupAndRestoresItOnReturn() throws Exception {
    Assume.assumeFalse(IOUtils.isOsWindows());
    var path = folder.getRoot().toPath().resolve("tracker");
    var tracker = trustedTracker();
    Files.write(path, encode(capture(tracker)));
    var operations = new RecordingOperations(path) {
      @Override
      void write(FileChannel channel, ChangedPageTracker.SaveState state,
          LogSequenceNumber coverage) throws IOException {
        Thread.currentThread().interrupt();
        // Exercise the JDK's actual interruptible force rather than a fabricated exception.
        channel.force(true);
      }

      @Override
      void forceFolder(Path parent) throws IOException {
        assertFalse(Thread.currentThread().isInterrupted());
        super.forceFolder(parent);
      }
    };
    try {
      assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations));
      assertTrue(Thread.currentThread().isInterrupted());
      assertFalse(Files.exists(path));
      assertFalse(tracker.isTrusted());
    } finally {
      Thread.interrupted();
    }
  }

  // Observe below the stage labels: the default implementations must call force(true) on each
  // channel, close the directory channel, and request both atomic replacement move options.
  @Test
  public void defaultPrimitivesForceActualChannelsAndRequestAtomicReplacement() throws Exception {
    var file = mock(FileChannel.class);
    var directory = mock(FileChannel.class);
    var source = folder.getRoot().toPath().resolve("source");
    var target = folder.getRoot().toPath().resolve("target");
    var observed = new ArrayList<CopyOption>();
    var operations = new ChangedPageTrackerFile.FileOperations() {
      @Override
      FileChannel openFolder(Path parent) {
        assertEquals(target.getParent(), parent);
        return directory;
      }

      @Override
      void move(Path from, Path to, CopyOption... options) {
        assertEquals(source, from);
        assertEquals(target, to);
        observed.addAll(Arrays.asList(options));
      }
    };
    operations.forceFile(file);
    operations.forceFolder(target.getParent());
    operations.move(source, target);
    verify(file).force(true);
    verify(directory).force(true);
    verify(directory).close();
    assertEquals(List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
        observed);
  }

  // The Windows recipe uses one write-through replacement and no directory channel. Invalidation
  // publishes an empty forced tombstone even when the authoritative side name was absent.
  @Test
  public void windowsPublicationAndTombstoneUseWriteThroughReplacementWithoutFolderForce()
      throws Exception {
    var path = folder.getRoot().toPath().resolve("tracker");
    var tracker = trustedTracker();
    var operations = new WindowsOperations(path);
    assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
        ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations));
    assertEquals(List.of("delete temporary", "write", "file force", "windows move"),
        operations.events);
    assertTrue(ChangedPageTrackerFile.load(path).tracker().isTrusted());
    for (boolean absent : List.of(false, true)) {
      if (absent) {
        Files.delete(path);
      }
      operations.events.clear();
      assertEquals(ChangedPageTrackerFile.InvalidationResult.INVALIDATED,
          ChangedPageTrackerFile.invalidate(path, tracker, operations));
      assertEquals(List.of("delete temporary", "file force", "windows move"), operations.events);
      assertEquals(0, Files.size(path));
      assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
    }
  }

  // Every Windows publication-stage failure publishes a forced empty tombstone. A replacement
  // failure during invalidation has an unsafe result, both directly and after a failed save.
  @Test
  public void windowsStageFailuresInvalidateAndDoubleFailuresRejectWalCut() throws Exception {
    for (String stage : List.of("write", "file force", "windows move", "delete temporary")) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      Files.write(path, encode(capture(tracker)));
      var operations = new WindowsOperations(path) {
        private boolean failed;

        @Override
        void event(String operation) throws IOException {
          super.event(operation);
          if (!failed && operation.equals(stage)) {
            failed = true;
            throw new IOException("injected " + stage);
          }
        }
      };
      assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations));
      assertFalse(tracker.isTrusted());
      assertEquals(0, Files.size(path));
      assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
    }
    var path = folder.newFolder().toPath().resolve("tracker");
    var tracker = trustedTracker();
    Files.write(path, encode(capture(tracker)));
    var operations = new WindowsOperations(path) {
      @Override
      void windowsMove(Path temporary, Path target) throws IOException {
        throw new IOException("persistent replacement failure");
      }
    };
    assertEquals(ChangedPageTrackerFile.InvalidationResult.FAILED,
        ChangedPageTrackerFile.invalidate(path, tracker, operations));
    var result = ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations);
    assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATION_FAILED, result);
    assertFalse(result.allowsWalCut());
  }

  // Without the helper, no publication occurs. Force existing content empty without replacing its
  // name. An absent side file stays absent. Either case permits a cut with untrusted history.
  @Test
  public void windowsWithoutHelperTruncatesOldAuthorityAndKeepsAbsentNameAbsent() throws Exception {
    for (boolean absent : List.of(false, true)) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      if (!absent) {
        Files.write(path, encode(capture(tracker)));
      }
      var operations = new WindowsOperations(path) {
        @Override
        boolean windowsMoveAvailable() {
          return false;
        }

        @Override
        void windowsMove(Path temporary, Path target) throws IOException {
          throw new IOException("helper unavailable");
        }
      };
      assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations));
      assertFalse(tracker.isTrusted());
      assertEquals(absent, Files.notExists(path));
      if (!absent) {
        assertEquals(0, Files.size(path));
      }
      assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
    }
  }

  // Failed marks on either side of Windows publication hit the same capture fences as POSIX.
  @Test
  public void windowsMarkFailuresCannotCertifyTrustedCapture() throws Exception {
    for (String stage : List.of("file force", "windows move")) {
      var path = folder.newFolder().toPath().resolve("tracker");
      var tracker = trustedTracker();
      var operations = new WindowsOperations(path) {
        private boolean failed;

        @Override
        void event(String operation) throws IOException {
          super.event(operation);
          if (!failed && operation.equals(stage)) {
            failed = true;
            assertThrows(IllegalArgumentException.class, () -> tracker.mark(1, -1));
          }
        }
      };
      assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED,
          ChangedPageTrackerFile.save(path, tracker, COVERAGE, operations));
      assertFalse(tracker.isTrusted());
      assertEquals(0, Files.size(path));
      assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
    }
  }

  // Windows CI exercises the real native helper and a forced tombstone on its own file system.
  @Test
  public void windowsRealPublicationAndInvalidationLoseLoadedTrust() throws Exception {
    Assume.assumeTrue(IOUtils.isOsWindows());
    assertTrue(FileUtils.windowsWriteThroughMoveAvailable());
    var path = folder.getRoot().toPath().resolve("tracker");
    var tracker = trustedTracker();
    assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
        ChangedPageTrackerFile.save(path, tracker, COVERAGE));
    assertTrue(ChangedPageTrackerFile.load(path).tracker().isTrusted());
    assertEquals(ChangedPageTrackerFile.InvalidationResult.INVALIDATED,
        ChangedPageTrackerFile.invalidate(path, tracker));
    assertEquals(0, Files.size(path));
    assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
  }

  private record Warning(String message, Throwable cause) {
  }

  private static long size(Path path) {
    try {
      return Files.size(path);
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private static class WindowsOperations extends RecordingOperations {
    WindowsOperations(Path path) {
      super(path);
    }

    @Override
    boolean windows() {
      return true;
    }

    @Override
    boolean windowsMoveAvailable() {
      return true;
    }

    @Override
    void windowsMove(Path temporary, Path target) throws IOException {
      event("windows move");
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static ChangedPageTracker trustedTracker() {
    var builder = ChangedPageTracker.restoration(UUID.randomUUID(), UUID.randomUUID(), true,
        UUID.randomUUID());
    builder.activeWord(1, 0, 3);
    builder.sealedWord(1, 512, 4);
    return builder.build();
  }

  private static Path temporary(Path path) {
    return path.resolveSibling(path.getFileName() + ".tmp");
  }

  private static void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IOException("Timed out waiting for publication test");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException(failure);
    }
  }

  private static class RecordingOperations extends ChangedPageTrackerFile.FileOperations {
    private final Path sideFile;
    final List<String> events = new ArrayList<>();
    final List<Warning> warnings = new ArrayList<>();

    RecordingOperations(Path sideFile) {
      this.sideFile = sideFile;
    }

    void event(String operation) throws IOException {
      events.add(operation);
    }

    @Override
    boolean windows() {
      return false;
    }

    @Override
    void warn(String message, Throwable failure) {
      warnings.add(new Warning(message, failure));
    }

    @Override
    void write(FileChannel channel, ChangedPageTracker.SaveState state, LogSequenceNumber coverage)
        throws IOException {
      event("write");
      super.write(channel, state, coverage);
    }

    @Override
    void forceFile(FileChannel channel) throws IOException {
      event("file force");
      assertTrue(channel.isOpen());
      super.forceFile(channel);
    }

    @Override
    void move(Path temporary, Path target) throws IOException {
      event("move");
      // Force has completed and the stream owner has closed the temporary channel.
      assertNotNull(ChangedPageTrackerFile.load(temporary).coverageLsn());
      super.move(temporary, target);
    }

    @Override
    void forceFolder(Path folder) throws IOException {
      event("folder force");
      super.forceFolder(folder);
    }

    @Override
    void delete(Path path) throws IOException {
      event(path.equals(sideFile) ? "delete side" : "delete temporary");
      super.delete(path);
    }
  }

  // Both generations, extreme indices and continuity survive decoding and the startup merge.
  @Test
  public void roundTripPreservesIdentifiersCoverageAndBothGenerations() throws Exception {
    var builder = ChangedPageTracker.restoration(UUID.randomUUID(), UUID.randomUUID(), true,
        UUID.randomUUID());
    builder.activeWord(1, 0, 3);
    builder.activeWord(Integer.MAX_VALUE, Long.MAX_VALUE >>> 6, Long.MIN_VALUE);
    builder.sealedWord(1, 512, 4);
    var source = capture(builder.build());
    var loaded = read(encode(source));
    assertTrue(loaded.tracker().isTrusted());
    assertEquals(COVERAGE, loaded.coverageLsn());
    var state = capture(loaded.tracker());
    assertEquals(source.trackerIdentifier(), state.trackerIdentifier());
    assertEquals(source.lastCompletedIdentifier(), state.lastCompletedIdentifier());
    assertEquals(source.sealed().identifier(), state.sealed().identifier());
    assertPages(state.active(), 1, 0, 1);
    assertPages(state.active(), Integer.MAX_VALUE, Long.MAX_VALUE);
    assertPages(state.sealed().pages(), 1, 32770);
    loaded.tracker().mergeLoadedSealed();
    state = capture(loaded.tracker());
    assertNull(state.sealed());
    assertPages(state.active(), 1, 0, 1, 32770);
    assertEquals(source.lastCompletedIdentifier(), state.lastCompletedIdentifier());
    var empty = read(encode(capture(new ChangedPageTracker())));
    assertFalse(empty.tracker().isTrusted());
    assertNotNull(empty.coverageLsn());
  }

  // Every shorter byte length and every one-bit byte mutation loses authority. No bad content
  // escapes as an exception. A missing path has the same fail-closed result.
  @Test
  public void missingTruncatedAndBitFlippedInputsFailClosed() throws Exception {
    var builder = ChangedPageTracker.restoration(UUID.randomUUID(), UUID.randomUUID(), true,
        UUID.randomUUID());
    builder.activeWord(1, 0, 1);
    builder.sealedWord(1, 1, 2);
    var bytes = encode(capture(builder.build()));
    for (int length = 0; length < bytes.length; length++) {
      assertUntrusted(Arrays.copyOf(bytes, length));
    }
    for (int index = 0; index < bytes.length; index++) {
      var damaged = bytes.clone();
      damaged[index] ^= 1;
      assertUntrusted(damaged);
    }
    var directory = Files.createTempDirectory("tracker-codec");
    try {
      var missing = ChangedPageTrackerFile.load(directory.resolve("absent"));
      assertFalse(missing.tracker().isTrusted());
      assertNull(missing.coverageLsn());
      Files.write(directory.resolve("valid"), bytes);
      assertEquals(COVERAGE, ChangedPageTrackerFile.load(directory.resolve("valid")).coverageLsn());
    } finally {
      Files.deleteIfExists(directory.resolve("valid"));
      Files.delete(directory);
    }
  }

  // Recompute checksums so structural validation, not CRC failure, rejects malformed fields.
  // The untrusted header is 37 bytes. Two active records are 20 bytes each, followed by a terminator.
  @Test
  public void validChecksumsCannotAuthorizeMalformedHeadersIndicesOrOrdering() throws Exception {
    int[] offsets = {0, 4, 8, 9, 17, 37, 37, 57, 41, 41, 61, 61, 49};
    long[] values = {0, 2, 8, -1, -1, -1, 2, 0, -1, 2, Long.MAX_VALUE, 0, 0};
    for (int index = 0; index < offsets.length; index++) {
      var bytes = sample();
      var buffer = ByteBuffer.wrap(bytes);
      int offset = offsets[index];
      if (offset == 8) {
        buffer.put(offset, (byte) values[index]);
      } else if (offset == 9 || offset == 41 || offset == 61 || offset == 49) {
        buffer.putLong(offset, values[index]);
      } else {
        buffer.putInt(offset, (int) values[index]);
      }
      repairChecksum(bytes);
      assertUntrusted(bytes);
    }
    var bytes = sample();
    // Duplicate the full first record, including its file identifier and word index.
    System.arraycopy(bytes, 37, bytes, 57, 20);
    repairChecksum(bytes);
    assertUntrusted(bytes);
    var trailing = Arrays.copyOf(sample(), sample().length + 1);
    assertUntrusted(trailing);
  }

  // Transport failures are checked I/O errors, not damaged-content results. Writer callbacks
  // preserve the original error. Invalid coverage cannot be encoded as authoritative content.
  @Test
  public void transportFailuresRemainDistinctAndStreamsStayOpen() throws Exception {
    var failure = new IOException("injected transport failure");
    assertSame(failure, assertThrows(IOException.class, () -> ChangedPageTrackerFile.read(
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw failure;
          }
        })));
    var source = capture(new ChangedPageTracker());
    assertThrows(IllegalArgumentException.class, () -> ChangedPageTrackerFile.write(
        OutputStream.nullOutputStream(), source, LogSequenceNumber.NOT_TRACKED));
    var tracker = new ChangedPageTracker();
    for (int word = 0; word < 1024; word++) {
      tracker.mark(1, word * 64L);
    }
    assertSame(failure, assertThrows(IOException.class, () -> ChangedPageTrackerFile.write(
        new OutputStream() {
          @Override
          public void write(int value) throws IOException {
            throw failure;
          }
        }, capture(tracker), COVERAGE)));
    var out = new ByteArrayOutputStream() {
      @Override
      public void close() {
        throw new AssertionError("Codec closed caller stream");
      }
    };
    ChangedPageTrackerFile.write(out, source, COVERAGE);
    ChangedPageTrackerFile.read(new ByteArrayInputStream(out.toByteArray()) {
      @Override
      public void close() {
        throw new AssertionError("Codec closed caller stream");
      }
    });
  }

  // Transport EOF exceptions at the header, inside a word and during the final end check keep
  // their identity. Only a returned -1 before a complete record is damaged content.
  @Test
  public void transportEofExceptionsPropagateAtHeaderRecordAndEndCheck() throws Exception {
    var bytes = sample();
    for (int failureOffset : new int[] {0, 45, bytes.length}) {
      var failure = new EOFException("injected transport EOF at " + failureOffset);
      var stream = new InputStream() {
        private int position;

        @Override
        public int read() throws IOException {
          if (position == failureOffset) {
            throw failure;
          }
          return position == bytes.length ? -1 : bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
          if (position == failureOffset) {
            throw failure;
          }
          int count = Math.min(length, failureOffset - position);
          System.arraycopy(bytes, position, target, offset, count);
          position += count;
          return count;
        }
      };
      assertSame(failure, assertThrows(EOFException.class,
          () -> ChangedPageTrackerFile.read(stream)));
    }
  }

  // Pause the encoder at its first buffer write. Writers publish words and new segments without
  // save order. Every one-shot mark completed before capture remains in the decoded state.
  @Test
  public void concurrentMarkingCannotLoseMarksCompletedBeforeCapture() throws Exception {
    var tracker = new ChangedPageTracker();
    for (int word = 0; word < 1024; word++) {
      tracker.mark(1, word * 64L);
    }
    var entered = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var out = new ByteArrayOutputStream() {
      @Override
      public synchronized void write(byte[] bytes, int offset, int length) {
        entered.countDown();
        try {
          assertTrue(released.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
          throw new AssertionError(failure);
        }
        super.write(bytes, offset, length);
      }
    };
    var executor = Executors.newSingleThreadExecutor();
    var future = executor.submit(() -> {
      tracker.saveOrderLock().lock();
      try {
        ChangedPageTrackerFile.write(out, tracker.capture(), COVERAGE);
      } finally {
        tracker.saveOrderLock().unlock();
      }
      return null;
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      for (int word = 0; word < 2048; word++) {
        tracker.mark(1, word * 64L + 1);
      }
      released.countDown();
      future.get(10, TimeUnit.SECONDS);
      var pages = new TreeSet<Long>();
      capture(read(out.toByteArray()).tracker()).active().forEachCandidate(1, pages::add);
      for (int word = 0; word < 1024; word++) {
        assertTrue(pages.contains(word * 64L));
      }
    } finally {
      released.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  // A child JVM excludes VMLens instrumentation. Sparse input rejects a full extra bitmap.
  // Dense input rejects whole-file buffering. Both budgets include all transient objects.
  @Test
  public void encodingAndLoadingAllocateOnlyBoundedScratchBeyondLoadedBitmaps() throws Exception {
    Assume.assumeTrue(!IOUtils.isOsWindows() || FileUtils.windowsWriteThroughMoveAvailable());
    var process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx128m",
        "-XX:-DoEscapeAnalysis", "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        AllocationProbe.class.getName()).redirectErrorStream(true).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      var output = new String(process.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(output, 0, process.exitValue());
      assertTrue(output, output.contains("codec allocation"));
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  public static final class AllocationProbe {
    public static void main(String[] args) throws Exception {
      var tracker = new ChangedPageTracker();
      int segments = 2048;
      for (int generation = 0; generation < 2; generation++) {
        for (int segment = 0; segment < segments / 2; segment++) {
          tracker.mark(1, segment * 32768L);
        }
        if (generation == 0) {
          tracker.beginBackup();
        }
      }
      long payload = (long) segments * ChangedPageTracker.SEGMENT_BYTES;
      measureAllocation(tracker, payload, payload / 4, "sparse");

      var dense = new ChangedPageTracker();
      int denseSegments = 16;
      for (int segment = 0; segment < denseSegments; segment++) {
        for (int word = 0; word < 512; word++) {
          dense.mark(1, segment * 32768L + word * 64L);
        }
      }
      // Dense wire records exceed the 16 KiB scratch allowance. Buffering the whole file
      // cannot fit, even though the restored bit payload is only 64 KiB.
      measureAllocation(dense, (long) denseSegments * ChangedPageTracker.SEGMENT_BYTES,
          16384, "dense");
    }

    private static void measureAllocation(ChangedPageTracker tracker, long payload,
        long scratchAllowance, String label) throws IOException {
      // Warm the real publisher and channel adapter. Only setup owns a wire array.
      var bytes = encode(capture(tracker));
      read(bytes);
      var directory = Files.createTempDirectory("tracker-allocation");
      var path = directory.resolve("tracker");
      try {
        for (int warmup = 0; warmup < 3; warmup++) {
          assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
              ChangedPageTrackerFile.save(path, tracker, COVERAGE));
        }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        var result = ChangedPageTrackerFile.save(path, tracker, COVERAGE);
        long encoding = bean.getThreadAllocatedBytes(thread) - before;
        before = bean.getThreadAllocatedBytes(thread);
        var loaded = read(bytes);
        long loading = bean.getThreadAllocatedBytes(thread) - before;
        long budget = payload + scratchAllowance;
        System.out.println("codec allocation: " + label + ", save=" + encoding
            + ", loading=" + loading + ", wire=" + bytes.length + ", loading budget=" + budget);
        assertEquals(ChangedPageTrackerFile.SaveResult.SAVED, result);
        assertTrue(label + " save allocated " + encoding, encoding < 65536);
        assertTrue(label + " loading allocated " + loading + " with budget " + budget,
            loading < budget);
        assertNotNull(loaded.coverageLsn());
      } finally {
        Files.deleteIfExists(temporary(path));
        Files.deleteIfExists(path);
        Files.delete(directory);
      }
    }
  }

  private static byte[] sample() throws IOException {
    var tracker = new ChangedPageTracker();
    tracker.mark(1, 0);
    tracker.mark(1, 64);
    return encode(capture(tracker));
  }

  private static ChangedPageTracker.SaveState capture(ChangedPageTracker tracker) {
    tracker.saveOrderLock().lock();
    try {
      return tracker.capture();
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static byte[] encode(ChangedPageTracker.SaveState state) throws IOException {
    var out = new ByteArrayOutputStream();
    ChangedPageTrackerFile.write(out, state, COVERAGE);
    return out.toByteArray();
  }

  private static ChangedPageTrackerFile.Loaded read(byte[] bytes) throws IOException {
    return ChangedPageTrackerFile.read(new ByteArrayInputStream(bytes));
  }

  private static void assertUntrusted(byte[] bytes) throws IOException {
    var loaded = read(bytes);
    assertFalse(loaded.tracker().isTrusted());
    assertNull(loaded.coverageLsn());
  }

  private static void repairChecksum(byte[] bytes) {
    var checksum = new CRC32C();
    checksum.update(bytes, 0, bytes.length - 4);
    ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) checksum.getValue());
  }

  private static void assertPages(ChangedPageTracker.Generation generation, int file,
      long... expected) {
    var actual = new TreeSet<Long>();
    generation.forEachCandidate(file, actual::add);
    var pages = new TreeSet<Long>();
    for (long page : expected) {
      pages.add(page);
    }
    assertEquals(pages, actual);
  }
}
