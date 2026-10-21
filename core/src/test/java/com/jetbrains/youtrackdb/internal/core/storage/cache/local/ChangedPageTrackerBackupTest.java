package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Status.COVERAGE_UNAVAILABLE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Status.MERGED_NOT_CONSUMED;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Status.NO_BOUNDARY;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Status.SAVED_CONTINUITY;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Status.SAVED_UNTRUSTED;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Status.SAVE_FAILED_INVALIDATED;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Status.SAVE_FAILED_INVALIDATION_FAILED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ChangedPageTrackerBackupTest {

  private static final UUID ID = new UUID(1, 2);
  private static final UUID COMPLETED = new UUID(3, 4);
  private static final LogSequenceNumber SAFE = new LogSequenceNumber(17, 32);
  private static final ChangedPageTrackerBackup.CompletionFacts DURABLE =
      new ChangedPageTrackerBackup.CompletionFacts(true, true);
  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  // 1. Every field and both generation owners come from the same boundary, not live getters.
  @Test
  public void consistentBoundarySeparatesOldAndNewMarks() {
    var tracker = trusted();
    tracker.mark(1, 3);
    var before = capture(tracker);
    var context = begin(tracker);
    tracker.mark(1, 4);
    assertEquals(ID, context.trackerIdentifier());
    assertEquals(COMPLETED, context.lastCompletedIdentifier());
    assertNotNull(context.consumedIdentifier());
    assertNotNull(context.sealHandle());
    assertEquals(ChangedPageTrackerBackup.BeginOutcome.NEW_BOUNDARY, context.outcome());
    assertTrue(context.trustedAtBoundary());
    assertTrue(context.selectionEligible());
    assertTrue(context.durabilityCapable());
    var after = capture(tracker);
    assertSame(before.active(), after.sealed().pages());
    assertEquals(context.consumedIdentifier(), after.sealed().identifier());
    assertEquals(context.trackerIdentifier(), after.sealed().trackerIdentifier());
    assertEquals(Set.of(3L), pages(after.sealed().pages()));
    assertEquals(Set.of(4L), pages(after.active()));
    tracker.invalidate();
    assertTrue(context.trustedAtBoundary());
  }

  // 2. Both UUIDs and trusted completed history are mandatory. A missing head is not a match.
  @Test
  public void eligibilityRequiresBothIdentifiersAndTrustedCompletion() {
    ChangedPageTrackerBackup.Continuity[] heads = {null,
        new ChangedPageTrackerBackup.Continuity(UUID.randomUUID(), COMPLETED),
        new ChangedPageTrackerBackup.Continuity(ID, UUID.randomUUID()), pair()};
    for (int i = 0; i < heads.length; i++) {
      assertEquals(i == 3, ChangedPageTrackerBackup.begin(trusted(), heads[i], true)
          .selectionEligible());
    }
    var missing = ChangedPageTracker.restoration(ID, null, true, null).build();
    assertFalse(ChangedPageTrackerBackup.begin(missing, pair(), true).selectionEligible());
    var untrusted = trusted();
    untrusted.invalidate();
    assertFalse(begin(untrusted).selectionEligible());
    assertThrows(NullPointerException.class,
        () -> new ChangedPageTrackerBackup.Continuity(null, COMPLETED));
    assertThrows(NullPointerException.class,
        () -> new ChangedPageTrackerBackup.Continuity(ID, null));
  }

  // 3. Q-f can match input history but cannot consume its sealed marks or change completion.
  @Test
  public void incapableAndUnconfirmedOutputsMergeWithoutPublication() {
    for (boolean capable : new boolean[] {false, true}) {
      var tracker = trusted();
      tracker.mark(1, 3);
      var context = ChangedPageTrackerBackup.begin(tracker, pair(), capable);
      assertTrue(context.selectionEligible());
      tracker.mark(1, 4);
      var files = new RecordingFiles();
      assertEquals(MERGED_NOT_CONSUMED, complete(tracker, context,
          new ChangedPageTrackerBackup.CompletionFacts(true, !capable), source(tracker), files));
      assertEquals(0, files.writes);
      assertEquals(COMPLETED, capture(tracker).lastCompletedIdentifier());
      assertEquals(Set.of(3L, 4L), pages(capture(tracker).active()));
    }
  }

  // 4. Pending fallback creates no third generation and carries no authority over the first seal.
  @Test
  public void pendingContextCannotResolveEarlierSeal() {
    var tracker = trusted();
    var first = begin(tracker);
    var before = capture(tracker);
    var second = begin(tracker);
    assertEquals(ChangedPageTrackerBackup.BeginOutcome.PENDING_SEAL, second.outcome());
    assertNull(second.sealHandle());
    assertNull(second.consumedIdentifier());
    assertFalse(second.selectionEligible());
    assertFalse(tracker.isTrusted());
    assertSame(before.active(), capture(tracker).active());
    assertSame(before.sealed(), capture(tracker).sealed());
    assertEquals(NO_BOUNDARY, ChangedPageTrackerBackup.abandon(tracker, second));
    assertEquals(NO_BOUNDARY, complete(tracker, second, DURABLE, source(tracker),
        new RecordingFiles()));
    assertSame(before.sealed(), capture(tracker).sealed());
    ChangedPageTrackerBackup.abandon(tracker, first);
  }

  // 5. Begin must finish while an older publisher is blocked in its file force.
  @Test
  public void beginNeverWaitsForSlowPublisher() throws Exception {
    var tracker = trusted();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var save = pool.submit(() -> ChangedPageTrackerFile.save(path(), tracker, SAFE,
          blockingForce(entered, release)));
      await(entered);
      var boundary = pool.submit(() -> begin(tracker));
      assertNotNull(boundary.get(5, TimeUnit.SECONDS).sealHandle());
      release.countDown();
      assertEquals(ChangedPageTrackerFile.SaveResult.SAVED, save.get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  // 6. Fresh bootstrap needs exactly one publication and stores the exact exported pair.
  @Test
  public void freshBootstrapPublishesOneExactPairAndReloads() throws Exception {
    var tracker = new ChangedPageTracker();
    assertTrue(capture(tracker).resetsTracker());
    var context = begin(tracker);
    assertFalse(context.trustedAtBoundary());
    var files = new RecordingFiles();
    assertEquals(SAVED_CONTINUITY, complete(tracker, context, DURABLE, source(tracker), files));
    assertEquals(1, files.writes);
    assertPair(tracker, context);
    var pending = ChangedPageTrackerStartup.load(path());
    assertFalse(pending.tracker().isTrusted());
    assertTrue(prove(pending, WriteAheadLog.ProofStatus.REACHED_END).coverageProved());
    assertPair(pending.tracker(), context);
  }

  // 7. A prior invalidation, with or without an ordinary reset publication, permits full bootstrap.
  @Test
  public void invalidatedBootstrapUsesNewIdentity() throws Exception {
    for (boolean resetFirst : new boolean[] {false, true}) {
      var tracker = trusted();
      tracker.invalidate();
      if (resetFirst) {
        ChangedPageTrackerFile.save(path(), tracker, SAFE);
      }
      var context = begin(tracker);
      assertNotEquals(ID, context.trackerIdentifier());
      assertNull(context.lastCompletedIdentifier());
      assertEquals(SAVED_CONTINUITY,
          complete(tracker, context, DURABLE, source(tracker), new RecordingFiles()));
      assertPair(ChangedPageTrackerFile.load(path()).tracker(), context);
    }
  }

  // 8. Missing full read, a new mark failure, or startup gating each forbids trusted bootstrap.
  @Test
  public void bootstrapRefusesIncompleteOrChangedOrPendingHistory() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var tracker = new ChangedPageTracker();
      var context = begin(tracker);
      if (mode == 1) {
        failMark(tracker);
      } else if (mode == 2) {
        tracker.startupVerificationPending(true);
      }
      assertEquals(SAVED_UNTRUSTED, complete(tracker, context,
          new ChangedPageTrackerBackup.CompletionFacts(mode != 0, true), source(tracker),
          new RecordingFiles()));
      assertFalse(tracker.isTrusted());
      var saved = capture(ChangedPageTrackerFile.load(path()).tracker());
      assertFalse(saved.trusted());
      assertNull(saved.lastCompletedIdentifier());
    }
  }

  // 9. Old durable and loaded coverage cannot replace a new retained-record preflight position.
  @Test
  public void completionDerivesFreshCoverageDespiteInvalidatedOrUnprovedOldCoverage()
      throws Exception {
    for (boolean loaded : new boolean[] {false, true}) {
      var tracker = trusted();
      var old = new LogSequenceNumber(2, 8);
      ChangedPageTrackerFile.save(path(), tracker, old);
      if (loaded) {
        var pending = ChangedPageTrackerStartup.load(path());
        assertFalse(prove(pending, WriteAheadLog.ProofStatus.COVERAGE_NOT_RETAINED)
            .coverageProved());
        tracker = pending.tracker();
      } else {
        var failedSave = new RecordingFiles() {
          @Override
          void write(FileChannel channel, ChangedPageTracker.SaveState state,
              LogSequenceNumber coverage) throws IOException {
            throw new IOException("failed checkpoint publication before cut");
          }
        };
        assertEquals(ChangedPageTrackerFile.SaveResult.FAILED_INVALIDATED,
            ChangedPageTrackerFile.save(path(), tracker, old, failedSave));
        assertFalse(tracker.isTrusted());
      }
      assertEquals(old, tracker.durableCoverageLsn());
      var context = begin(tracker);
      assertEquals(SAVED_CONTINUITY,
          complete(tracker, context, DURABLE, source(tracker), new RecordingFiles()));
      assertEquals(SAFE, ChangedPageTrackerFile.load(path()).coverageLsn());
      var reloaded = ChangedPageTrackerStartup.load(path());
      assertTrue(prove(reloaded, WriteAheadLog.ProofStatus.REACHED_END).historyTrusted());
    }
  }

  // 9. Every unavailable source path warns, invalidates and merges exact marks before fallback.
  @Test
  public void unavailableCoverageMergesAndAllowsLaterBoundary() {
    for (int mode = 0; mode < 9; mode++) {
      var tracker = trusted();
      tracker.mark(1, 3);
      var context = begin(tracker);
      tracker.mark(1, 4);
      int failure = mode;
      var unavailable = new ChangedPageTrackerBackup.CoverageSource() {
        public long sampleBoundary() throws IOException {
          assertFalse(tracker.saveOrderLock().isHeldByCurrentThread());
          if (failure == 0) {
            throw new IOException("sample unavailable");
          }
          return failure == 5 ? -1 : 17;
        }

        public WriteAheadLog.CutPreflight preflightCut(long fixed) throws IOException {
          assertTrue(tracker.saveOrderLock().isHeldByCurrentThread());
          if (failure == 1) {
            throw new IOException("preflight unavailable");
          }
          if (failure == 2) {
            return null;
          }
          return new WriteAheadLog.CutPreflight(false,
              failure == 8 ? -1 : failure == 4 ? 18 : 17,
              failure == 3 ? null : failure == 6 ? new LogSequenceNumber(-1, 0)
                  : failure == 7 ? new LogSequenceNumber(17, -1) : SAFE);
        }
      };
      var files = new RecordingFiles();
      assertEquals(COVERAGE_UNAVAILABLE, complete(tracker, context, DURABLE, unavailable, files));
      assertTrue(files.warnings > 0);
      assertEquals(0, files.writes);
      assertFalse(tracker.isTrusted());
      assertNull(capture(tracker).sealed());
      assertEquals(Set.of(3L, 4L), pages(capture(tracker).active()));
      assertNotNull(begin(tracker).sealHandle());
    }
  }

  // 10. Completion publishes even unchanged empty marks and never calls the checkpoint cutter.
  @Test
  public void unchangedCompletionStillPublishesExactlyOnce() {
    var tracker = trusted();
    var context = begin(tracker);
    var files = new RecordingFiles();
    assertEquals(SAVED_CONTINUITY, complete(tracker, context, DURABLE, source(tracker), files));
    assertEquals(1, files.writes);
    assertEquals(1, files.forces);
    assertEquals(1, files.moves);
  }

  // 11. Each filesystem failure site is reached. Publisher and cleanup Errors map only here.
  @Test
  public void publicationFailureMatrixWarnsAndReturnsAuthorityStatus() {
    for (int site = 0; site < 6; site++) {
      for (boolean cleanupFails : new boolean[] {false, true}) {
        var tracker = trusted();
        var context = begin(tracker);
        int fault = site;
        var reached = new AtomicBoolean();
        var files = new RecordingFiles() {
          private void fail(int at) throws IOException {
            if (fault == at) {
              reached.set(true);
              if (at == 4) {
                throw new AssertionError("publisher error");
              }
              throw new IOException("publisher fault");
            }
          }

          @Override
          void write(FileChannel channel, ChangedPageTracker.SaveState state,
              LogSequenceNumber coverage) throws IOException {
            fail(0);
            if (fault == 4) {
              fail(4);
            }
            super.write(channel, state, coverage);
          }

          @Override
          void forceFile(FileChannel channel) throws IOException {
            fail(1);
            super.forceFile(channel);
          }

          @Override
          void move(Path temporary, Path target) throws IOException {
            fail(2);
            super.move(temporary, target);
          }

          @Override
          void forceFolder(Path directory) throws IOException {
            if (!reached.get()) {
              fail(3);
              fail(5);
            } else if (cleanupFails) {
              if (fault == 5) {
                throw new AssertionError("cleanup error");
              }
              throw new IOException("cleanup failure");
            }
            super.forceFolder(directory);
          }
        };
        assertEquals(cleanupFails ? SAVE_FAILED_INVALIDATION_FAILED : SAVE_FAILED_INVALIDATED,
            complete(tracker, context, DURABLE, source(tracker), files));
        assertTrue(reached.get());
        assertTrue(files.warnings > 0);
        assertFalse(tracker.isTrusted());
      }
    }
  }

  // 12. A real failing mark during either publication barrier cannot install captured trust.
  @Test
  public void failedMarkDuringForceOrReplacementRevokesCapturedCompletion() {
    for (boolean duringForce : new boolean[] {false, true}) {
      var tracker = trusted();
      var files = new RecordingFiles() {
        @Override
        void forceFile(FileChannel channel) throws IOException {
          if (duringForce) {
            failMark(tracker);
          }
          super.forceFile(channel);
        }

        @Override
        void move(Path temporary, Path target) throws IOException {
          super.move(temporary, target);
          if (!duringForce) {
            failMark(tracker);
          }
        }
      };
      assertEquals(SAVE_FAILED_INVALIDATED,
          complete(tracker, begin(tracker), DURABLE, source(tracker), files));
      assertFalse(tracker.isTrusted());
      assertTrue(files.warnings > 0);
    }
  }

  // 13. Foreign, repeated and stale handles cannot consume another boundary. Reuse drops old bits.
  @Test
  public void abandonPreservesOwnershipAndFileIdentity() {
    var tracker = trusted();
    tracker.mark(1, 3);
    var context = begin(tracker);
    tracker.resetFile(1);
    tracker.mark(1, 5);
    tracker.mark(2, 7);
    var foreign = trusted();
    var foreignContext = begin(foreign);
    var foreignSeal = capture(foreign).sealed();
    assertThrows(IllegalArgumentException.class,
        () -> ChangedPageTrackerBackup.abandon(foreign, context));
    assertSame(foreignSeal, capture(foreign).sealed());
    assertFalse(foreign.isTrusted());
    assertEquals(MERGED_NOT_CONSUMED, ChangedPageTrackerBackup.abandon(tracker, context));
    assertEquals(Set.of(5L), pages(capture(tracker).active()));
    assertEquals(COMPLETED, capture(tracker).lastCompletedIdentifier());
    assertThrows(IllegalStateException.class,
        () -> ChangedPageTrackerBackup.abandon(tracker, context));
    var next = begin(tracker);
    var seal = capture(tracker).sealed();
    assertThrows(IllegalStateException.class,
        () -> complete(tracker, context, DURABLE, source(tracker), new RecordingFiles()));
    assertSame(seal, capture(tracker).sealed());
    ChangedPageTrackerBackup.abandon(tracker, next);
    ChangedPageTrackerBackup.abandon(foreign, foreignContext);
  }

  // 13. Failed bitmap allocation leaves the original seal reachable, even during coverage cleanup.
  @Test
  public void mergeFailureKeepsSealReachableAndForbidsThirdGeneration() {
    for (boolean unavailable : new boolean[] {false, true}) {
      var armed = new AtomicBoolean();
      var tracker = new ChangedPageTracker(kind -> {
        if (armed.get() && kind == ChangedPageTracker.Allocation.FILE) {
          throw new OutOfMemoryError("merge allocation");
        }
      });
      tracker.mark(1, 3);
      var context = begin(tracker);
      var seal = capture(tracker).sealed();
      armed.set(true);
      assertThrows(OutOfMemoryError.class, () -> {
        if (unavailable) {
          complete(tracker, context, DURABLE, unavailableSource(), new RecordingFiles());
        } else {
          ChangedPageTrackerBackup.abandon(tracker, context);
        }
      });
      assertFalse(tracker.isTrusted());
      assertSame(seal, capture(tracker).sealed());
      assertNull(begin(tracker).sealHandle());
    }
  }

  // 14. Neither terminal operation can overtake an older publication that owns save order.
  @Test
  public void completionAndAbandonWaitForOlderPublication() throws Exception {
    for (boolean consume : new boolean[] {false, true}) {
      var tracker = trusted();
      tracker.mark(1, 3);
      var context = begin(tracker);
      tracker.mark(1, 4);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var requested = new CountDownLatch(1);
      var terminal = new AtomicInteger();
      var pool = Executors.newFixedThreadPool(2);
      try {
        var older = pool.submit(() -> ChangedPageTrackerFile.save(path(), tracker, SAFE,
            blockingForce(entered, release)));
        await(entered);
        var files = new RecordingFiles();
        var operation = pool.submit(() -> {
          requested.countDown();
          var result = consume ? complete(tracker, context, DURABLE, source(tracker), files)
              : ChangedPageTrackerBackup.abandon(tracker, context);
          terminal.incrementAndGet();
          return result;
        });
        await(requested);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!tracker.saveOrderLock().hasQueuedThreads() && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertTrue("terminal operation must wait in save order",
            tracker.saveOrderLock().hasQueuedThreads());
        assertEquals(0, terminal.get());
        assertNotNull(context.internalSeal());
        release.countDown();
        assertEquals(ChangedPageTrackerFile.SaveResult.SAVED, older.get(10, TimeUnit.SECONDS));
        assertEquals(consume ? SAVED_CONTINUITY : MERGED_NOT_CONSUMED,
            operation.get(10, TimeUnit.SECONDS));
        assertEquals(consume ? 1 : 0, files.writes);
        if (consume) {
          var decoded = ChangedPageTrackerFile.load(path()).tracker();
          assertPair(decoded, context);
          assertEquals(Set.of(4L), pages(capture(decoded).active()));
        } else {
          assertEquals(Set.of(3L, 4L), pages(capture(tracker).active()));
        }
      } finally {
        release.countDown();
        pool.shutdownNow();
      }
    }
  }

  // 15. Restart merges a saved pending seal without consuming it. Unsaved output cannot match it.
  @Test
  public void restartDistinguishesPendingUnsavedAndPublishedCompletion() throws Exception {
    var tracker = trusted();
    tracker.mark(1, 3);
    var context = begin(tracker);
    tracker.mark(1, 4);
    ChangedPageTrackerFile.save(path(), tracker, SAFE);
    var pending = ChangedPageTrackerStartup.load(path());
    assertNull(capture(pending.tracker()).sealed());
    assertEquals(Set.of(3L, 4L), pages(capture(pending.tracker()).active()));
    pending.keepSavedTrust();
    var head = new ChangedPageTrackerBackup.Continuity(context.trackerIdentifier(),
        context.consumedIdentifier());
    assertFalse(ChangedPageTrackerBackup.begin(pending.tracker(), head, true).selectionEligible());
    assertEquals(COMPLETED, capture(pending.tracker()).lastCompletedIdentifier());
    assertEquals(SAVED_CONTINUITY,
        complete(tracker, context, DURABLE, source(tracker), new RecordingFiles()));
    var saved = ChangedPageTrackerStartup.load(path());
    saved.keepSavedTrust();
    assertTrue(ChangedPageTrackerBackup.begin(saved.tracker(), head, true).selectionEligible());
  }

  // 16 and 20. A real failure after retirement but before capture must save untrusted successfully.
  // Capture's own boundary-version comparison is required even with publisher revalidation active.
  @Test
  public void failureBetweenValidationAndCaptureReturnsSavedUntrusted() throws Exception {
    for (boolean initiallyTrusted : new boolean[] {false, true}) {
      var staged = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var tracker = new ChangedPageTracker(new ChangedPageTracker.AllocationObserver() {
        public void beforeAllocation(ChangedPageTracker.Allocation kind) {
        }

        public void beforeCompletionCapture() {
          staged.countDown();
          await(release);
        }
      });
      if (initiallyTrusted) {
        // Install ordinary trusted history through the same production projection mechanism.
        var first = begin(tracker);
        tracker.saveOrderLock().lock();
        try {
          tracker.stageCompletion(first.internalSeal(), true);
          assertTrue(tracker.saveSucceeded(tracker.capture(), SAFE));
          tracker.clearCompletion();
        } finally {
          tracker.saveOrderLock().unlock();
        }
        assertTrue(tracker.isTrusted());
      }
      var context = begin(tracker);
      var pool = Executors.newSingleThreadExecutor();
      try {
        var result = pool.submit(() -> complete(tracker, context, DURABLE, source(tracker),
            new RecordingFiles()));
        await(staged);
        failMark(tracker);
        release.countDown();
        assertEquals(SAVED_UNTRUSTED, result.get(10, TimeUnit.SECONDS));
        var decoded = capture(ChangedPageTrackerFile.load(path()).tracker());
        assertFalse(decoded.trusted());
        assertNull(decoded.lastCompletedIdentifier());
        assertFalse(tracker.isTrusted());
      } finally {
        release.countDown();
        pool.shutdownNow();
      }
    }
  }

  // 17. Same-version checkpoint reset and repeated captures share the boundary's proposed UUID.
  @Test
  public void checkpointDuringBootstrapSharesResetIdentityWithoutGrantingTrust() throws Exception {
    for (boolean invalidated : new boolean[] {false, true}) {
      var tracker = invalidated ? trusted() : new ChangedPageTracker();
      if (invalidated) {
        tracker.invalidate();
      }
      var context = begin(tracker);
      assertEquals(context.trackerIdentifier(), capture(tracker).trackerIdentifier());
      assertEquals(context.trackerIdentifier(), capture(tracker).trackerIdentifier());
      var wal = org.mockito.Mockito.mock(WriteAheadLog.class);
      org.mockito.Mockito.when(wal.preflightCut(17))
          .thenReturn(new WriteAheadLog.CutPreflight(true, 17, SAFE));
      tracker.checkpoint(path(), wal, 17);
      assertEquals(context.trackerIdentifier(),
          capture(ChangedPageTrackerFile.load(path()).tracker()).trackerIdentifier());
      assertFalse(tracker.isTrusted());
      var files = new RecordingFiles();
      assertEquals(SAVED_CONTINUITY, complete(tracker, context, DURABLE, source(tracker), files));
      assertEquals(1, files.writes);
      assertPair(tracker, context);
      failMark(tracker);
      var reset = capture(tracker);
      assertNotEquals(context.trackerIdentifier(), reset.trackerIdentifier());
      assertEquals(reset.trackerIdentifier(), capture(tracker).trackerIdentifier());
      assertNull(reset.lastCompletedIdentifier());
    }
  }

  // 18. Every construction failure precedes reference publication and leaves a recoverable owner.
  @Test
  public void constructionFailuresLeaveOriginalGenerationOwnership() {
    for (var fault : ChangedPageTracker.BoundaryAllocation.values()) {
      var armed = new AtomicBoolean();
      var tracker = new ChangedPageTracker(new ChangedPageTracker.AllocationObserver() {
        public void beforeAllocation(ChangedPageTracker.Allocation kind) {
        }

        public void beforeBoundaryAllocation(ChangedPageTracker.BoundaryAllocation kind) {
          if (armed.get() && kind == fault) {
            throw new OutOfMemoryError("boundary preparation");
          }
        }
      });
      var original = capture(tracker).active();
      armed.set(true);
      assertThrows(OutOfMemoryError.class, () -> begin(tracker));
      assertSame(original, capture(tracker).active());
      assertNull(capture(tracker).sealed());
      assertFalse(tracker.isTrusted());
      armed.set(false);
      assertNotNull(begin(tracker).sealHandle());
    }
  }

  // 19. A blocked cache sample holds no save order. An overtaking cut is resolved by preflight
  // at the original boundary, not by resampling or consulting the obsolete durable coverage.
  @Test
  public void blockedSampleAllowsOrderedCutAndPreflightUsesFixedBoundary() throws Exception {
    var tracker = trusted();
    var context = begin(tracker);
    var sampling = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var samples = new AtomicInteger();
    var overtaken = new AtomicBoolean();
    var source = new ChangedPageTrackerBackup.CoverageSource() {
      public long sampleBoundary() {
        samples.incrementAndGet();
        sampling.countDown();
        await(release);
        return 17;
      }

      public WriteAheadLog.CutPreflight preflightCut(long fixed) {
        assertTrue(overtaken.get());
        assertTrue(tracker.saveOrderLock().isHeldByCurrentThread());
        assertEquals(17, fixed);
        assertNotNull(capture(tracker).sealed());
        return new WriteAheadLog.CutPreflight(false, 17, SAFE);
      }
    };
    var pool = Executors.newFixedThreadPool(2);
    try {
      var completion = pool.submit(() -> complete(tracker, context, DURABLE, source,
          new RecordingFiles()));
      await(sampling);
      var cut = pool.submit(() -> {
        var wal = org.mockito.Mockito.mock(WriteAheadLog.class);
        org.mockito.Mockito.when(wal.preflightCut(18))
            .thenReturn(new WriteAheadLog.CutPreflight(true, 18,
                new LogSequenceNumber(18, 32)));
        org.mockito.Mockito.when(wal.cutAllSegmentsSmallerThan(18)).thenAnswer(call -> {
          overtaken.set(true);
          return true;
        });
        assertTrue(tracker.checkpoint(path(), wal, 18).segmentsRemoved());
        return null;
      });
      cut.get(5, TimeUnit.SECONDS);
      release.countDown();
      assertEquals(SAVED_CONTINUITY, completion.get(10, TimeUnit.SECONDS));
      assertEquals(1, samples.get());
      assertEquals(SAFE, ChangedPageTrackerFile.load(path()).coverageLsn());
      assertTrue(prove(ChangedPageTrackerStartup.load(path()),
          WriteAheadLog.ProofStatus.REACHED_END).historyTrusted());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  // The exported overload uses the same production publisher without package-private test inputs.
  @Test
  public void publicCompletionEntryPointEstablishesContinuity() {
    var tracker = new ChangedPageTracker();
    var context = ChangedPageTrackerBackup.begin(tracker, null, true);
    assertEquals(SAVED_CONTINUITY, ChangedPageTrackerBackup.complete(tracker, context, path(),
        DURABLE, source(tracker)));
    assertPair(tracker, context);
  }

  private static ChangedPageTrackerStartup.StartupResult prove(
      ChangedPageTrackerStartup.PendingHistory pending, WriteAheadLog.ProofStatus status) {
    var wal = org.mockito.Mockito.mock(WriteAheadLog.class);
    org.mockito.Mockito.when(wal.preOpenExtent())
        .thenReturn(new WriteAheadLog.PreOpenExtent(java.util.List.of()));
    WriteAheadLog.ProofReader reader = count -> new WriteAheadLog.ProofBatch(
        java.util.List.of(), status, pending.coverageLsn(), false, null);
    org.mockito.Mockito.when(wal.openProofReader(pending.coverageLsn(), 16 * 1024 * 1024))
        .thenReturn(reader);
    return ChangedPageTrackerStartup.verify(pending, wal,
        ChangedPageTrackerStartup.ShutdownState.CLEAN, new ChangedPageTrackerStartup.FileIds() {
          public int internalFileId(long externalId) {
            return (int) externalId;
          }

          public String currentName(int internalId) {
            return "file";
          }
        });
  }

  private Path path() {
    return folder.getRoot().toPath().resolve(ChangedPageTrackerFile.FILE_NAME);
  }

  private ChangedPageTrackerBackup.Status complete(ChangedPageTracker tracker,
      ChangedPageTrackerBackup.Context context, ChangedPageTrackerBackup.CompletionFacts facts,
      ChangedPageTrackerBackup.CoverageSource source, ChangedPageTrackerFile.FileOperations files) {
    return ChangedPageTrackerBackup.complete(tracker, context, path(), facts, source, files);
  }

  private static ChangedPageTrackerBackup.Context begin(ChangedPageTracker tracker) {
    return ChangedPageTrackerBackup.begin(tracker, pair(), true);
  }

  private static ChangedPageTrackerBackup.Continuity pair() {
    return new ChangedPageTrackerBackup.Continuity(ID, COMPLETED);
  }

  private static ChangedPageTracker trusted() {
    return ChangedPageTracker.restoration(ID, COMPLETED, true, null).build();
  }

  private static ChangedPageTracker.SaveState capture(ChangedPageTracker tracker) {
    tracker.saveOrderLock().lock();
    try {
      return tracker.capture();
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static Set<Long> pages(ChangedPageTracker.Generation generation) {
    var pages = new TreeSet<Long>();
    generation.forEachCandidate(1, pages::add);
    return pages;
  }

  private static void assertPair(ChangedPageTracker tracker,
      ChangedPageTrackerBackup.Context context) {
    var state = capture(tracker);
    assertTrue(tracker.isTrusted());
    assertTrue(state.trusted());
    assertEquals(context.trackerIdentifier(), state.trackerIdentifier());
    assertEquals(context.consumedIdentifier(), state.lastCompletedIdentifier());
    assertNull(state.sealed());
  }

  private static ChangedPageTrackerBackup.CoverageSource source(ChangedPageTracker tracker) {
    return new ChangedPageTrackerBackup.CoverageSource() {
      public long sampleBoundary() {
        assertFalse(tracker.saveOrderLock().isHeldByCurrentThread());
        return 17;
      }

      public WriteAheadLog.CutPreflight preflightCut(long fixed) {
        assertEquals(17, fixed);
        assertTrue(tracker.saveOrderLock().isHeldByCurrentThread());
        assertNotNull(capture(tracker).sealed());
        return new WriteAheadLog.CutPreflight(false, 17, SAFE);
      }
    };
  }

  private static ChangedPageTrackerBackup.CoverageSource unavailableSource() {
    return new ChangedPageTrackerBackup.CoverageSource() {
      public long sampleBoundary() throws IOException {
        throw new IOException("no sample");
      }

      public WriteAheadLog.CutPreflight preflightCut(long fixed) {
        throw new AssertionError("must not preflight missing sample");
      }
    };
  }

  private static void failMark(ChangedPageTracker tracker) {
    assertThrows(IllegalArgumentException.class, () -> tracker.mark(0, 0));
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue("bounded latch", latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static RecordingFiles blockingForce(CountDownLatch entered, CountDownLatch release) {
    return new RecordingFiles() {
      @Override
      void forceFile(FileChannel channel) throws IOException {
        entered.countDown();
        await(release);
        super.forceFile(channel);
      }
    };
  }

  private static class RecordingFiles extends ChangedPageTrackerFile.FileOperations {
    int writes;
    int forces;
    int moves;
    int warnings;

    @Override
    boolean windows() {
      return false;
    }

    @Override
    void write(FileChannel channel, ChangedPageTracker.SaveState state,
        LogSequenceNumber coverage) throws IOException {
      writes++;
      super.write(channel, state, coverage);
    }

    @Override
    void forceFile(FileChannel channel) throws IOException {
      forces++;
      super.forceFile(channel);
    }

    @Override
    void move(Path temporary, Path target) throws IOException {
      moves++;
      super.move(temporary, target);
    }

    @Override
    void warn(String message, Throwable failure) {
      warnings++;
    }
  }
}
