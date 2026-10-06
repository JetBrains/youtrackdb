package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;
import org.junit.Test;

public class ChangedPageTrackerTest {

  // Every writer reaches allocation before any can publish the first segment. Expected bits are
  // computed from worker indices, not from tracker output. Repeated marks exercise the read path.
  @Test
  public void concurrentFirstPublicationAndSameWordContentionPreserveAllMarks() throws Exception {
    for (int round = 0; round < 6; round++) {
      int writers = 32;
      var publication = new CyclicBarrier(writers);
      var firstSegment = ThreadLocal.withInitial(() -> true);
      var tracker = new ChangedPageTracker(kind -> {
        if (kind == ChangedPageTracker.Allocation.SEGMENT && firstSegment.get()) {
          firstSegment.set(false);
          await(publication);
        }
      });
      runWorkers(writers, worker -> tracker.mark(Integer.MAX_VALUE, worker));
      var expected = new TreeSet<Long>();
      for (long worker = 0; worker < writers; worker++) {
        expected.add(worker);
      }
      // Check before retries can repair a bit written into an unpublished losing segment.
      assertCandidates(capture(tracker).active(), Integer.MAX_VALUE, expected);
      runWorkers(writers, worker -> {
        for (int repeat = 0; repeat < 200; repeat++) {
          tracker.mark(Integer.MAX_VALUE, worker);
          tracker.mark(Integer.MAX_VALUE, 32768L + worker);
        }
      });
      for (long worker = 0; worker < writers; worker++) {
        expected.add(32768 + worker);
      }
      assertCandidates(capture(tracker).active(), Integer.MAX_VALUE, expected);
    }
  }

  // Distinct sparse IDs publish competing immutable roots. Deletion prunes branches while
  // surviving readers keep their exact marks. Reused IDs start empty and iteration stays sorted.
  @Test
  public void concurrentPrimitiveFilePublicationAndPruningPreserveLiveEntries() throws Exception {
    int writers = 32;
    var identities = new CyclicBarrier(writers);
    var files = new CyclicBarrier(writers);
    var armed = new AtomicBoolean(true);
    var tracker = new ChangedPageTracker(kind -> {
      if (armed.get() && kind == ChangedPageTracker.Allocation.IDENTITY) {
        await(identities);
      } else if (armed.get() && kind == ChangedPageTracker.Allocation.FILE) {
        await(files);
      }
    });
    runWorkers(writers, worker -> tracker.mark(1 + (worker << 24), worker));
    armed.set(false);
    var active = capture(tracker).active();
    assertEquals(63, retainedFileEntries(field(tracker, "identities")));
    assertEquals(63, retainedFileEntries(field(active, "files")));
    for (int worker = 0; worker < writers; worker++) {
      assertCandidates(active, 1 + (worker << 24), Set.of((long) worker));
    }
    runWorkers(writers, worker -> {
      int file = 1 + (worker << 24);
      if ((worker & 1) == 0) {
        tracker.deleteFile(file);
      } else {
        tracker.mark(file, worker + 64);
      }
    });
    assertEquals(31, retainedFileEntries(field(tracker, "identities")));
    assertEquals(31, retainedFileEntries(field(active, "files")));
    for (int worker = writers - 1; worker >= 0; worker--) {
      int file = 1 + (worker << 24);
      if ((worker & 1) == 0) {
        assertCandidates(active, file, Set.of());
        tracker.mark(file, worker + 128);
        assertCandidates(active, file, Set.of((long) worker + 128));
      } else {
        assertCandidates(active, file, Set.of((long) worker, (long) worker + 64));
      }
    }
    var actualFiles = new ArrayList<Integer>();
    var expectedFiles = new ArrayList<Integer>();
    active.forEachFile((file, bitmap) -> actualFiles.add(file));
    for (int worker = 0; worker < writers; worker++) {
      expectedFiles.add(1 + (worker << 24));
    }
    assertEquals(expectedFiles, actualFiles);
  }

  // A mix of sparse file IDs and page ranges covers every radix level and word/segment boundary.
  // The extreme page index proves that iteration does not overflow its ascending long keys.
  @Test
  public void sparseFilesAndBoundariesReturnSortedUniqueCandidates() {
    var tracker = new ChangedPageTracker();
    long[] pages = {Long.MAX_VALUE, 65536, 32768, 32767, 64, 63, 0, 1L << 39, 1L << 55};
    int[] files = {1, 255, 256, 65535, 65536, 1 << 24, Integer.MAX_VALUE};
    var expected = new TreeSet<Long>();
    for (long page : pages) {
      expected.add(page);
    }
    for (int file : files) {
      for (long page : pages) {
        tracker.mark(file, page);
        tracker.mark(file, page);
      }
    }
    var active = capture(tracker).active();
    for (int file : files) {
      assertCandidates(active, file, expected);
    }
    assertCandidates(active, 2, Set.of());
    assertCandidates(active, 254, Set.of());
    var visited = new ArrayList<Integer>();
    active.forEachFile((file, bitmap) -> visited.add(file));
    assertEquals(List.of(1, 255, 256, 65535, 65536, 1 << 24, Integer.MAX_VALUE), visited);
  }

  // Successful checkpoint saving resets identity but cannot repair missing backup history.
  // Only durable full-read completion establishes continuity, with fresh random seal IDs.
  @Test
  public void identifierResetAndDurableCompletionFollowContinuityRules() {
    var tracker = new ChangedPageTracker();
    assertFalse(tracker.isTrusted());
    var initial = capture(tracker);
    assertTrue(initial.resetsTracker());
    successfulSave(tracker);
    var reset = capture(tracker);
    assertFalse(reset.resetsTracker());
    assertFalse(reset.trusted());
    var seal = tracker.beginBackup();
    assertNotNull(seal);
    assertEquals(reset.trackerIdentifier(), capture(tracker).trackerIdentifier());
    assertEquals(reset.trackerIdentifier(), seal.trackerIdentifier());
    assertSame(initial.active(), seal.pages());
    tracker.mark(1, 12);
    tracker.retire(seal);
    assertTrue(tracker.isTrusted());
    assertEquals(seal.identifier(), capture(tracker).lastCompletedIdentifier());
    assertEquals(reset.trackerIdentifier(), capture(tracker).trackerIdentifier());
    successfulSave(tracker);
    assertEquals(seal.identifier(), capture(tracker).lastCompletedIdentifier());
    assertEquals(reset.trackerIdentifier(), capture(tracker).trackerIdentifier());
    var identifiers = new HashSet<UUID>();
    identifiers.add(seal.identifier());
    for (int i = 0; i < 10; i++) {
      var next = tracker.beginBackup();
      assertTrue(identifiers.add(next.identifier()));
      tracker.retire(next);
      assertEquals(reset.trackerIdentifier(), capture(tracker).trackerIdentifier());
    }
    tracker.invalidate();
    assertFalse(tracker.isTrusted());
    var proposed = capture(tracker);
    assertNotEquals(reset.trackerIdentifier(), proposed.trackerIdentifier());
    assertNull(proposed.lastCompletedIdentifier());
    assertFalse(proposed.trusted());
    tracker.saveOrderLock().lock();
    try {
      assertTrue(tracker.saveSucceeded(proposed));
    } finally {
      tracker.saveOrderLock().unlock();
    }
    var saved = capture(tracker);
    assertEquals(proposed.trackerIdentifier(), saved.trackerIdentifier());
    assertNull(saved.lastCompletedIdentifier());
    assertFalse(tracker.isTrusted());
  }

  // Failed backups and Q-f outputs preserve both generations and leave completed continuity alone.
  // Loaded-sealed startup uses the same bounded merge, without consuming its loaded identifier.
  @Test
  public void mergeBackAndLoadedSealPreserveMarksWithoutAdvancingContinuity() {
    var tracker = trustedTracker();
    var original = capture(tracker);
    var completed = original.lastCompletedIdentifier();
    for (int pass = 0; pass < 2; pass++) {
      tracker.mark(1, 63);
      tracker.mark(Integer.MAX_VALUE, Long.MAX_VALUE);
      var seal = tracker.beginBackup();
      tracker.mark(1, 64);
      tracker.mark(2, 32768);
      if (pass == 0) {
        tracker.mergeBack(seal);
      } else {
        tracker.mergeLoadedSealed();
      }
      var state = capture(tracker);
      assertNull(state.sealed());
      assertEquals(completed, state.lastCompletedIdentifier());
      assertEquals(original.trackerIdentifier(), state.trackerIdentifier());
      assertTrue(state.trusted());
      assertCandidates(state.active(), 1, Set.of(63L, 64L));
      assertCandidates(state.active(), 2, Set.of(32768L));
      assertCandidates(state.active(), Integer.MAX_VALUE, Set.of(Long.MAX_VALUE));
    }
    tracker.mergeLoadedSealed();
    assertEquals(completed, capture(tracker).lastCompletedIdentifier());
  }

  // Active writes and sealed merge contend on the same words and first segment publications.
  // Both independently generated page sets must survive, including bits on segment boundaries.
  @Test
  public void concurrentMergeAndActiveMarksPreserveBothExpectedSets() throws Exception {
    var tracker = trustedTracker();
    var expected = new TreeSet<Long>();
    for (long page = 0; page < 2000; page += 2) {
      tracker.mark(1, page);
      tracker.mark(1, 32768 + page);
      expected.add(page);
      expected.add(32768 + page);
    }
    var seal = tracker.beginBackup();
    for (long page = 1; page < 2000; page += 2) {
      expected.add(page);
      expected.add(32768 + page);
    }
    runWorkers(2, worker -> {
      if (worker == 0) {
        tracker.mergeBack(seal);
      } else {
        for (long page = 1; page < 2000; page += 2) {
          tracker.mark(1, page);
          tracker.mark(1, 32768 + page);
        }
      }
    });
    assertCandidates(capture(tracker).active(), 1, expected);
  }

  // Reuse and deletion detach both generations without mutating retained bitmap word storage.
  @Test
  public void fileReuseAndDeletionNeverInheritOldMarks() {
    var tracker = trustedTracker();
    tracker.mark(1, 3);
    var seal = tracker.beginBackup();
    tracker.mark(1, 4);
    tracker.resetFile(1);
    tracker.mark(1, 5);
    tracker.deleteFile(2);
    tracker.mergeBack(seal);
    assertCandidates(capture(tracker).active(), 1, Set.of(5L));
    tracker.deleteFile(1);
    assertCandidates(capture(tracker).active(), 1, Set.of());
    tracker.mark(1, 6);
    assertCandidates(capture(tracker).active(), 1, Set.of(6L));
  }

  // A defensive second switch keeps the same two references, loses trust, and schedules an ID reset.
  @Test
  public void pendingSealFailsClosedWithoutThirdGeneration() {
    var tracker = trustedTracker();
    tracker.mark(1, 1);
    var seal = tracker.beginBackup();
    tracker.mark(1, 2);
    var before = capture(tracker);
    assertNull(tracker.beginBackup());
    var after = capture(tracker);
    assertSame(before.active(), after.active());
    assertSame(seal, after.sealed());
    assertTrue(after.resetsTracker());
    assertFalse(tracker.isTrusted());
    tracker.mergeBack(seal);
    assertCandidates(capture(tracker).active(), 1, Set.of(1L, 2L));
    successfulSave(tracker);
    assertNotEquals(before.trackerIdentifier(), capture(tracker).trackerIdentifier());
    assertNull(capture(tracker).lastCompletedIdentifier());
  }

  // All allocation kinds can fail on the mark path. Trust must be lost before the error reaches
  // the caller, and a capture made before failure must not certify subsequent save success.
  @Test
  public void allocationFailuresInvalidateTrustAndInFlightCaptures() {
    for (var kind : ChangedPageTracker.Allocation.values()) {
      var armed = new AtomicBoolean();
      var failure = new OutOfMemoryError("Injected " + kind);
      var tracker = new ChangedPageTracker(allocated -> {
        if (armed.get() && allocated == kind) {
          throw failure;
        }
      });
      successfulSave(tracker);
      tracker.retire(tracker.beginBackup());
      var before = capture(tracker);
      armed.set(true);
      assertSame(failure, assertThrows(OutOfMemoryError.class, () -> tracker.mark(1, 0)));
      assertFalse(tracker.isTrusted());
      tracker.saveOrderLock().lock();
      try {
        assertFalse(tracker.isCaptureValid(before));
        assertFalse(tracker.saveSucceeded(before));
      } finally {
        tracker.saveOrderLock().unlock();
      }
      armed.set(false);
      successfulSave(tracker);
      var after = capture(tracker);
      assertNotEquals(before.trackerIdentifier(), after.trackerIdentifier());
      assertNull(after.lastCompletedIdentifier());
      assertFalse(after.trusted());
    }
  }

  // A failed first segment allocation retains its seal. Retrying preserves all source marks.
  @Test
  public void failedMergeRetainsSealAndCanBeRetried() {
    var armed = new AtomicBoolean();
    var tracker = new ChangedPageTracker(kind -> {
      if (armed.get() && kind == ChangedPageTracker.Allocation.SEGMENT) {
        throw new IllegalStateException("Injected merge allocation failure");
      }
    });
    successfulSave(tracker);
    tracker.retire(tracker.beginBackup());
    tracker.mark(1, 32768);
    var seal = tracker.beginBackup();
    armed.set(true);
    assertThrows(IllegalStateException.class, () -> tracker.mergeBack(seal));
    assertSame(seal, capture(tracker).sealed());
    assertFalse(tracker.isTrusted());
    armed.set(false);
    tracker.mergeBack(seal);
    assertCandidates(capture(tracker).active(), 1, Set.of(32768L));
  }

  // A merge pauses after finding old identity and before publishing its active file bitmap.
  // Deletion and reuse must proceed under generation state alone and retain only new-file marks.
  @Test
  public void deletionAndReuseDuringMergeCannotResurrectOrReplaceMarks() throws Exception {
    var mergeReached = new CountDownLatch(1);
    var resumeMerge = new CountDownLatch(1);
    var blockMerge = new AtomicBoolean();
    var tracker = new ChangedPageTracker(kind -> {
      if (kind == ChangedPageTracker.Allocation.FILE && blockMerge.compareAndSet(true, false)) {
        mergeReached.countDown();
        await(resumeMerge);
      }
    });
    tracker.mark(1, 3);
    var seal = tracker.beginBackup();
    blockMerge.set(true);
    runWorkers(2, worker -> {
      if (worker == 0) {
        tracker.mergeBack(seal);
      } else {
        await(mergeReached);
        try {
          tracker.deleteFile(1);
          tracker.mark(1, 9);
        } finally {
          resumeMerge.countDown();
        }
      }
    });
    assertCandidates(capture(tracker).active(), 1, Set.of(9L));
  }

  // Saving holds save order across arbitrary waits, but marking, switching and deletion do not
  // wait for it. A retiring backup does wait until the captured generation has been released.
  @Test
  public void slowSaveDoesNotBlockMarksSwitchesOrFileEventsButOrdersRetirement() throws Exception {
    var saving = new CountDownLatch(1);
    var releaseSave = new CountDownLatch(1);
    var attemptingRetire = new CountDownLatch(1);
    var retired = new AtomicBoolean();
    var tracker = trustedTracker();
    runWorkers(3, worker -> {
      if (worker == 0) {
        tracker.saveOrderLock().lock();
        try {
          var state = tracker.capture();
          saving.countDown();
          await(releaseSave);
          assertTrue(tracker.isCaptureValid(state));
          assertFalse(retired.get());
        } finally {
          tracker.saveOrderLock().unlock();
        }
      } else if (worker == 1) {
        await(saving);
        tracker.mark(1, 4);
        var seal = tracker.beginBackup();
        tracker.deleteFile(1);
        tracker.mark(1, 5);
        attemptingRetire.countDown();
        tracker.retire(seal);
        retired.set(true);
      } else {
        try {
          await(attemptingRetire);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          while (!tracker.saveOrderLock().hasQueuedThreads() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
          }
          assertTrue("Retirement did not queue behind save",
              tracker.saveOrderLock().hasQueuedThreads());
          assertFalse(retired.get());
        } finally {
          releaseSave.countDown();
        }
      }
    });
    assertEquals(0, attemptingRetire.getCount());
    assertTrue(retired.get());
    assertCandidates(capture(tracker).active(), 1, Set.of(5L));
  }

  // Invalid arguments fail closed on the mark path. Domain and stale seal misuse are explicit.
  @Test
  public void invalidArgumentsAndWrongDomainOrSealAreRejected() {
    var tracker = trustedTracker();
    assertThrows(IllegalStateException.class, tracker::capture);
    var state = capture(tracker);
    assertThrows(IllegalStateException.class, () -> tracker.saveSucceeded(state));
    assertThrows(IllegalStateException.class, () -> tracker.isCaptureValid(state));
    assertThrows(IllegalArgumentException.class, () -> tracker.mark(0, 1));
    assertThrows(IllegalArgumentException.class, () -> tracker.mark(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> tracker.mark(1, -1));
    assertFalse(tracker.isTrusted());
    assertThrows(IllegalArgumentException.class, () -> tracker.deleteFile(0));
    assertThrows(IllegalArgumentException.class,
        () -> state.active().forEachCandidate(0, page -> {
        }));
    var seal = tracker.beginBackup();
    tracker.retire(seal);
    assertFalse(tracker.isTrusted());
    assertThrows(IllegalStateException.class, () -> tracker.retire(seal));
    assertThrows(IllegalStateException.class, () -> tracker.mergeBack(seal));
  }

  // Counts cover bitmap metadata and payload. The separate ThreadMXBean test includes all
  // allocations, including lookup keys. First marks after a switch use one segment node per file.
  @Test
  public void allocationsStayBoundedForSparseIdsRepeatedMarksAndSwitches() {
    Map<ChangedPageTracker.Allocation, Integer> counts =
        new EnumMap<>(ChangedPageTracker.Allocation.class);
    var tracker = new ChangedPageTracker(kind -> counts.merge(kind, 1, Integer::sum));
    assertTrue(counts.isEmpty());
    tracker.mark(Integer.MAX_VALUE, 0);
    for (var kind : ChangedPageTracker.Allocation.values()) {
      assertEquals(1, (int) counts.get(kind));
    }
    var first = new EnumMap<>(counts);
    for (int i = 0; i < 1000; i++) {
      tracker.mark(Integer.MAX_VALUE, 0);
    }
    assertEquals(first, counts);
    tracker.mark(Integer.MAX_VALUE, 32768);
    assertEquals(1, (int) counts.get(ChangedPageTracker.Allocation.NODE));
    assertEquals(2, (int) counts.get(ChangedPageTracker.Allocation.SEGMENT));
    var beforeSwitch = new EnumMap<>(counts);
    tracker.beginBackup();
    assertEquals(beforeSwitch, counts);
    tracker.mark(Integer.MAX_VALUE, 0);
    assertEquals(2, (int) counts.get(ChangedPageTracker.Allocation.NODE));
    assertEquals(2, (int) counts.get(ChangedPageTracker.Allocation.FILE));
    assertEquals(1, (int) counts.get(ChangedPageTracker.Allocation.IDENTITY));
    assertEquals(3, (int) counts.get(ChangedPageTracker.Allocation.SEGMENT));
    assertEquals(4096, ChangedPageTracker.SEGMENT_BYTES);
    assertEquals(512, ChangedPageTracker.SEGMENT_WORDS);
  }

  // Warm marks cover uncached Integer IDs and every positive int key bit. A clean child JVM
  // prevents VMLens instrumentation allocations from contaminating ThreadMXBean measurements.
  // Escape analysis is disabled so scalar replacement cannot conceal boxed lookup keys.
  // The fixed allowance rejects even one byte per call and includes any lookup-key allocation.
  @Test
  public void steadyStateMarksAllocateNothingForLargePrimitiveFileIds() throws Exception {
    var process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx128m",
        "-XX:-DoEscapeAnalysis", "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        SteadyMarkAllocationProbe.class.getName()).redirectErrorStream(true).start();
    try {
      assertTrue("Allocation probe did not finish", process.waitFor(30, TimeUnit.SECONDS));
      var output = new String(process.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(output, 0, process.exitValue());
      assertTrue("Probe did not report its measurement: " + output,
          output.contains("3400000 marks allocated"));
    } finally {
      process.destroyForcibly();
    }
  }

  public static final class SteadyMarkAllocationProbe {

    public static void main(String[] args) {
      var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      assertTrue(bean.isThreadAllocatedMemorySupported());
      bean.setThreadAllocatedMemoryEnabled(true);
      var tracker = new ChangedPageTracker();
      int[] files = new int[34];
      files[0] = 128;
      files[1] = 1000;
      files[2] = 70000;
      for (int bit = 0; bit < 31; bit++) {
        files[bit + 3] = Integer.MAX_VALUE - (1 << bit);
      }
      for (int file : files) {
        tracker.mark(file, 0);
      }
      repeatMarks(tracker, files, 100000);
      long thread = Thread.currentThread().threadId();
      long before = bean.getThreadAllocatedBytes(thread);
      repeatMarks(tracker, files, 100000);
      long allocated = bean.getThreadAllocatedBytes(thread) - before;
      assertTrue("Steady marks allocated " + allocated + " bytes", allocated <= 1024);
      System.out.println("3400000 marks allocated " + allocated + " bytes");
    }

    private static void repeatMarks(ChangedPageTracker tracker, int[] files, int rounds) {
      for (int round = 0; round < rounds; round++) {
        for (int file : files) {
          tracker.mark(file, 0);
        }
      }
    }
  }

  // A mark failure after sealing followed by a successful reset save must not be certified by
  // completion of the older backup. A later full-read boundary can establish fresh continuity.
  @Test
  public void resetSaveAfterFailedActiveMarkCannotCertifyOlderSeal() {
    var armed = new AtomicBoolean();
    var tracker = new ChangedPageTracker(kind -> {
      if (armed.get() && kind == ChangedPageTracker.Allocation.SEGMENT) {
        throw new OutOfMemoryError("Injected active mark failure");
      }
    });
    successfulSave(tracker);
    tracker.retire(tracker.beginBackup());
    var original = capture(tracker);
    var seal = tracker.beginBackup();
    armed.set(true);
    assertThrows(OutOfMemoryError.class, () -> tracker.mark(5, 7));
    armed.set(false);
    successfulSave(tracker);
    var reset = capture(tracker);
    assertNotEquals(original.trackerIdentifier(), reset.trackerIdentifier());
    assertFalse(reset.trusted());
    tracker.retire(seal);
    var completed = capture(tracker);
    assertFalse(tracker.isTrusted());
    assertFalse(completed.trusted());
    assertNull(completed.lastCompletedIdentifier());
    assertCandidates(completed.active(), 5, Set.of());
    tracker.retire(tracker.beginBackup());
    assertTrue(tracker.isTrusted());
    assertEquals(reset.trackerIdentifier(), capture(tracker).trackerIdentifier());
  }

  // Saving the initial reset during a backup changes its history even without a mark failure.
  // The seal's tracker identifier fence must therefore also prevent continuity certification.
  @Test
  public void trackerResetDuringBackupCannotCertifyItsSeal() {
    var tracker = new ChangedPageTracker();
    var seal = tracker.beginBackup();
    successfulSave(tracker);
    assertNotEquals(seal.trackerIdentifier(), capture(tracker).trackerIdentifier());
    tracker.retire(seal);
    assertFalse(tracker.isTrusted());
    assertNull(capture(tracker).lastCompletedIdentifier());
  }

  // Restore streaming words into a genuinely new tracker, including the same file in both
  // generations. Startup merge keeps T1/C1, discards S1, and preserves the exact union of marks.
  @Test
  public void restoredStateSharesIdentitiesAndPreservesContinuityAcrossStartupMerge() {
    var source = trustedTracker();
    source.mark(1, 63);
    source.mark(Integer.MAX_VALUE, Long.MAX_VALUE);
    source.beginBackup();
    source.mark(1, 64);
    source.mark(2, 32768);
    var saved = capture(source);
    var loader = ChangedPageTracker.restoration(saved.trackerIdentifier(),
        saved.lastCompletedIdentifier(), saved.trusted(), saved.sealed().identifier());
    saved.active().forEachFile((file, bitmap) -> bitmap.forEachWord(
        (word, bits) -> loader.activeWord(file, word, bits)));
    saved.sealed().pages().forEachFile((file, bitmap) -> bitmap.forEachWord(
        (word, bits) -> loader.sealedWord(file, word, bits)));
    var restored = loader.build();
    var loaded = capture(restored);
    assertNotSameGeneration(saved.active(), loaded.active());
    assertEquals(saved.sealed().identifier(), loaded.sealed().identifier());
    assertEquals(saved.trackerIdentifier(), loaded.trackerIdentifier());
    assertEquals(saved.lastCompletedIdentifier(), loaded.lastCompletedIdentifier());
    assertFalse(loaded.resetsTracker());
    assertTrue(loaded.trusted());
    assertSame(field(bitmap(loaded.active(), 1), "identity"),
        field(bitmap(loaded.sealed().pages(), 1), "identity"));
    restored.mergeLoadedSealed();
    var merged = capture(restored);
    assertNull(merged.sealed());
    assertEquals(saved.trackerIdentifier(), merged.trackerIdentifier());
    assertEquals(saved.lastCompletedIdentifier(), merged.lastCompletedIdentifier());
    assertTrue(merged.trusted());
    assertCandidates(merged.active(), 1, Set.of(63L, 64L));
    assertCandidates(merged.active(), 2, Set.of(32768L));
    assertCandidates(merged.active(), Integer.MAX_VALUE, Set.of(Long.MAX_VALUE));
    successfulSave(restored);
    assertEquals(saved.trackerIdentifier(), capture(restored).trackerIdentifier());
    restored.deleteFile(1);
    restored.mark(1, 9);
    assertCandidates(capture(restored).active(), 1, Set.of(9L));
    // The source's independent heap must not be changed by restored deletion or marking.
    assertCandidates(saved.active(), 1, Set.of(64L));
    assertCandidates(saved.sealed().pages(), 1, Set.of(63L));
    assertThrows(IllegalStateException.class, loader::build);
    assertThrows(IllegalStateException.class, () -> loader.activeWord(1, 0, 1));
  }

  // Empty and untrusted loaded state is valid input. Malformed word bounds and a missing seal
  // are rejected before loading marks, and an explicit coverage failure schedules an ID reset.
  @Test
  public void restorationRejectsMalformedWordsAndSupportsEmptyUntrustedState() {
    var identifier = UUID.randomUUID();
    var loader = ChangedPageTracker.restoration(identifier, null, false, null);
    assertThrows(IllegalStateException.class, () -> loader.sealedWord(1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> loader.activeWord(0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> loader.activeWord(1, -1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> loader.activeWord(1, (Long.MAX_VALUE >>> 6) + 1, 1));
    loader.activeWord(1, 0, 0);
    var tracker = loader.build();
    assertFalse(tracker.isTrusted());
    assertEquals(identifier, capture(tracker).trackerIdentifier());
    assertCandidates(capture(tracker).active(), 1, Set.of());
    tracker.mergeLoadedSealed();
    tracker.invalidate();
    successfulSave(tracker);
    assertNotEquals(identifier, capture(tracker).trackerIdentifier());
  }

  // Holding save order must delay merge-back, not just retirement. While queued, the seal and
  // both generations are unchanged. After release the union survives with stable identifiers.
  @Test
  public void mergeBackQueuesBehindSaveAndPreservesCapturedGenerations() throws Exception {
    var tracker = trustedTracker();
    tracker.mark(1, 3);
    var seal = tracker.beginBackup();
    tracker.mark(1, 4);
    var original = capture(tracker);
    var saving = new CountDownLatch(1);
    var attemptingMerge = new CountDownLatch(1);
    var releaseSave = new CountDownLatch(1);
    var merged = new AtomicBoolean();
    runWorkers(3, worker -> {
      if (worker == 0) {
        tracker.saveOrderLock().lock();
        try {
          var state = tracker.capture();
          saving.countDown();
          await(releaseSave);
          assertSame(seal, tracker.capture().sealed());
          assertSame(state.active(), tracker.capture().active());
          assertCandidates(state.active(), 1, Set.of(4L));
          assertCandidates(state.sealed().pages(), 1, Set.of(3L));
          assertFalse(merged.get());
        } finally {
          tracker.saveOrderLock().unlock();
        }
      } else if (worker == 1) {
        await(saving);
        attemptingMerge.countDown();
        tracker.mergeBack(seal);
        merged.set(true);
      } else {
        try {
          await(attemptingMerge);
          assertQueues(tracker.saveOrderLock());
          assertFalse(merged.get());
        } finally {
          releaseSave.countDown();
        }
      }
    });
    var after = capture(tracker);
    assertTrue(merged.get());
    assertNull(after.sealed());
    assertEquals(original.trackerIdentifier(), after.trackerIdentifier());
    assertEquals(original.lastCompletedIdentifier(), after.lastCompletedIdentifier());
    assertCandidates(after.active(), 1, Set.of(3L, 4L));
  }

  // Deletion without immediate reuse can race with a merge publication. The invalid entry must
  // be removed, produce no candidates, and give a later reuse neither inherited marks nor identity.
  @Test
  public void deletionOnlyDuringMergeReclaimsLatePublicationAndAllowsCleanReuse() throws Exception {
    var reached = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var block = new AtomicBoolean();
    var tracker = new ChangedPageTracker(kind -> {
      if (kind == ChangedPageTracker.Allocation.FILE && block.compareAndSet(true, false)) {
        reached.countDown();
        await(resume);
      }
    });
    tracker.mark(1, 3);
    var seal = tracker.beginBackup();
    block.set(true);
    runWorkers(2, worker -> {
      if (worker == 0) {
        tracker.mergeBack(seal);
      } else {
        await(reached);
        try {
          tracker.deleteFile(1);
        } finally {
          resume.countDown();
        }
      }
    });
    var active = capture(tracker).active();
    assertCandidates(active, 1, Set.of());
    assertEquals(0, retainedFileEntries(field(active, "files")));
    tracker.mark(1, 9);
    assertCandidates(active, 1, Set.of(9L));
  }

  // A trust reader must use the same short state domain as reset saving. Force the reader to
  // queue, then complete the reset, so it cannot combine pre-reset trust with post-reset fencing.
  @Test
  public void trustReaderWaitsForConsistentResetSaveState() throws Exception {
    var tracker = trustedTracker();
    var stateLock = (ReentrantLock) field(tracker, "generationState");
    var held = new CountDownLatch(1);
    var attemptingRead = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var observedTrust = new AtomicBoolean(true);
    runWorkers(3, worker -> {
      if (worker == 0) {
        tracker.saveOrderLock().lock();
        stateLock.lock();
        try {
          held.countDown();
          await(release);
          tracker.invalidate();
          successfulSave(tracker);
        } finally {
          stateLock.unlock();
          tracker.saveOrderLock().unlock();
        }
      } else if (worker == 1) {
        await(held);
        attemptingRead.countDown();
        observedTrust.set(tracker.isTrusted());
      } else {
        try {
          await(attemptingRead);
          assertQueues(stateLock);
        } finally {
          release.countDown();
        }
      }
    });
    assertFalse(observedTrust.get());
  }

  // Sparse churn must leave no historical branches in the registry or either generation.
  // Each live index has exactly one leaf per file and one fewer branch, independent of IDs.
  @Test
  public void sparseFileChurnReclaimsPrimitiveIndexBranches() {
    var tracker = new ChangedPageTracker();
    for (int i = 0; i < 10000; i++) {
      int file = 1 + 256 * i;
      tracker.mark(file, 0);
      tracker.deleteFile(file);
    }
    for (int i = 0; i < 10000; i++) {
      tracker.mark(Integer.MAX_VALUE - 256 * i, 0);
    }
    assertEquals(19999, retainedFileEntries(field(tracker, "identities")));
    assertEquals(19999, retainedFileEntries(field(capture(tracker).active(), "files")));
    var seal = tracker.beginBackup();
    for (int i = 0; i < 10000; i++) {
      int file = Integer.MAX_VALUE - 256 * i;
      tracker.mark(file, 1);
      tracker.deleteFile(file);
    }
    for (var index : List.of(field(tracker, "identities"),
        field(capture(tracker).active(), "files"), field(seal.pages(), "files"))) {
      assertEquals("Deleted entries retained index metadata", 0, retainedFileEntries(index));
    }
    tracker.mergeBack(seal);
    assertEquals(0, retainedFileEntries(field(tracker, "identities")));
  }

  // The compressed-reference model budgets a leaf plus a branch per live index entry, along
  // with fixed index roots. Single-segment metadata is below payload and dense overhead below 1%.
  @Test
  public void metadataByteBudgetsStayBelowSingleSegmentPayloadAndOnePercentForLargeFiles() {
    Map<ChangedPageTracker.Allocation, Integer> counts =
        new EnumMap<>(ChangedPageTracker.Allocation.class);
    var tracker = new ChangedPageTracker(kind -> counts.merge(kind, 1, Integer::sum));
    tracker.mark(Integer.MAX_VALUE, 0);
    assertEquals(1, retainedFileEntries(field(tracker, "identities")));
    assertEquals(1, retainedFileEntries(field(capture(tracker).active(), "files")));
    assertEquals(1352, metadataBytes(counts));
    assertTrue(metadataBytes(counts) < ChangedPageTracker.SEGMENT_BYTES);
    for (long segment = 1; segment < 4096; segment++) {
      tracker.mark(Integer.MAX_VALUE, segment * 32768);
    }
    assertEquals(17, (int) counts.get(ChangedPageTracker.Allocation.NODE));
    assertEquals(4096, (int) counts.get(ChangedPageTracker.Allocation.SEGMENT));
    long payload = 4096L * ChangedPageTracker.SEGMENT_BYTES;
    long overhead = metadataBytes(counts) + 4096L * 16;
    assertEquals(18632, metadataBytes(counts));
    assertEquals(84168, overhead);
    assertTrue(overhead * 100 < payload);
  }

  // Competing writers grow roots to different heights while publishing unique one-shot marks.
  // Every older root remains reachable, so its preexisting mark and all new branches survive.
  @Test
  public void concurrentRootGrowthPreservesOldSubtreesAndOneShotMarks() throws Exception {
    int writers = 32;
    var growth = new CyclicBarrier(writers);
    var armed = new AtomicBoolean();
    var firstNode = ThreadLocal.withInitial(() -> true);
    var tracker = new ChangedPageTracker(kind -> {
      if (armed.get() && kind == ChangedPageTracker.Allocation.NODE && firstNode.get()) {
        firstNode.set(false);
        await(growth);
      }
    });
    tracker.mark(1, 0);
    armed.set(true);
    runWorkers(writers, worker -> tracker.mark(1,
        (worker % 2 == 0 ? 256L : 65536L) * 32768 + worker));
    var expected = new TreeSet<Long>();
    expected.add(0L);
    for (int worker = 0; worker < writers; worker++) {
      expected.add((worker % 2 == 0 ? 256L : 65536L) * 32768 + worker);
    }
    assertCandidates(capture(tracker).active(), 1, expected);
  }

  // Direct index checks use distinct objects that compare equal. Conditional changes must use
  // reference identity, including after concurrent replacement and distinct-key publication.
  @Test
  public void fileIndexRejectsIdentityMismatchAndPreservesContendedUpdates() throws Exception {
    var type = Class.forName(ChangedPageTracker.class.getName() + "$FileIndex");
    var constructor = type.getDeclaredConstructor();
    constructor.setAccessible(true);
    var index = constructor.newInstance();
    var put = type.getDeclaredMethod("putIfAbsent", int.class, Object.class);
    var get = type.getDeclaredMethod("get", int.class);
    var replace = type.getDeclaredMethod("replace", int.class, Object.class, Object.class);
    var remove = type.getDeclaredMethod("remove", int.class, Object.class);
    for (var method : List.of(put, get, replace, remove)) {
      method.setAccessible(true);
    }
    var original = new String("same value");
    var impostor = new String("same value");
    assertNull(put.invoke(index, 70000, original));
    assertFalse((boolean) replace.invoke(index, 70000, impostor, new Object()));
    assertFalse((boolean) remove.invoke(index, 70000, impostor));
    assertSame(original, get.invoke(index, 70000));
    var winners = new java.util.concurrent.atomic.AtomicInteger();
    var values = new Object[32];
    runWorkers(32, worker -> {
      try {
        values[worker] = new Object();
        assertNull(put.invoke(index, worker + 1, values[worker]));
        if ((boolean) replace.invoke(index, 70000, original, values[worker])) {
          winners.incrementAndGet();
        }
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    });
    assertEquals(1, winners.get());
    assertFalse((boolean) remove.invoke(index, 70000, original));
    for (int worker = 0; worker < values.length; worker++) {
      assertSame(values[worker], get.invoke(index, worker + 1));
      assertTrue((boolean) remove.invoke(index, worker + 1, values[worker]));
    }
    var winner = get.invoke(index, 70000);
    assertTrue((boolean) remove.invoke(index, 70000, winner));
    assertNull(get.invoke(index, 70000));
    assertEquals(0, retainedFileEntries(index));
  }

  private static long metadataBytes(Map<ChangedPageTracker.Allocation, Integer> counts) {
    // Segment node: 24-byte wrapper + 16-byte AtomicReferenceArray + 1040-byte array.
    // Each primitive index entry budgets a 32-byte leaf and a 32-byte branch. Identity adds
    // 16 bytes. File adds a 24-byte bitmap and 40-byte segment tree/root. Two index roots add
    // 64 fixed bytes. Temporary copied paths and losing publications are not retained metadata.
    return 64L + counts.getOrDefault(ChangedPageTracker.Allocation.NODE, 0) * 1080L
        + counts.getOrDefault(ChangedPageTracker.Allocation.IDENTITY, 0) * 80L
        + counts.getOrDefault(ChangedPageTracker.Allocation.FILE, 0) * 128L;
  }

  private static void assertNotSameGeneration(ChangedPageTracker.Generation first,
      ChangedPageTracker.Generation second) {
    assertFalse(first == second);
  }

  private static void assertQueues(ReentrantLock lock) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue("Operation did not queue behind its ordering domain", lock.hasQueuedThreads());
  }

  private static Object field(Object target, String name) {
    try {
      var member = target.getClass().getDeclaredField(name);
      member.setAccessible(true);
      return member.get(target);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static int retainedFileEntries(Object index) {
    return countFileEntries(((AtomicReference<?>) field(index, "root")).get());
  }

  private static int countFileEntries(Object entry) {
    if (entry == null) {
      return 0;
    }
    return 1 + countFileEntries(field(entry, "left")) + countFileEntries(field(entry, "right"));
  }

  private static ChangedPageTracker.FileBitmap bitmap(ChangedPageTracker.Generation generation,
      int fileId) {
    var result = new AtomicReference<ChangedPageTracker.FileBitmap>();
    generation.forEachFile((file, value) -> {
      if (file == fileId) {
        result.set(value);
      }
    });
    return result.get();
  }

  private static ChangedPageTracker trustedTracker() {
    var tracker = new ChangedPageTracker();
    successfulSave(tracker);
    tracker.retire(tracker.beginBackup());
    assertTrue(tracker.isTrusted());
    return tracker;
  }

  private static ChangedPageTracker.SaveState capture(ChangedPageTracker tracker) {
    tracker.saveOrderLock().lock();
    try {
      return tracker.capture();
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static void successfulSave(ChangedPageTracker tracker) {
    tracker.saveOrderLock().lock();
    try {
      assertTrue(tracker.saveSucceeded(tracker.capture()));
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static void assertCandidates(ChangedPageTracker.Generation generation, int file,
      Set<Long> expected) {
    var actual = new ArrayList<Long>();
    generation.forEachCandidate(file, actual::add);
    assertEquals(new ArrayList<>(new TreeSet<>(expected)), actual);
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue("Worker did not reach rendezvous", latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  // Observe every future, even after another worker fails, and await executor termination.
  private static void runWorkers(int count, IntConsumer action) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(count);
    var start = new CyclicBarrier(count);
    List<Future<?>> futures = new ArrayList<>();
    Throwable observed = null;
    try {
      for (int i = 0; i < count; i++) {
        int worker = i;
        futures.add(executor.submit(() -> {
          await(start);
          action.accept(worker);
        }));
      }
      for (var future : futures) {
        try {
          future.get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
          if (observed == null) {
            observed = failure;
          } else {
            observed.addSuppressed(failure);
          }
        }
      }
    } finally {
      executor.shutdownNow();
      assertTrue("Worker pool did not terminate", executor.awaitTermination(10, TimeUnit.SECONDS));
    }
    if (observed != null) {
      throw new AssertionError("Worker failure", observed);
    }
  }
}
