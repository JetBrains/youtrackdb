package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.CompletionFacts;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup.Status;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerStartup;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AbstractPageWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.CutPreflight;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Direct bridge contracts plus facade publication against live disk collaborators. */
public class DiskStorageBackupCoverageSourceTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();
  private final WriteAheadLog wal = mock(WriteAheadLog.class);
  private final AtomicOperationsTable table = mock(AtomicOperationsTable.class);
  private final WriteCache cache = mock(WriteCache.class);
  private Fixture storage;
  private DiskStorage.DiskBackupCoverageSource source;
  private Path sideFile;

  @Before
  public void setup() throws Exception {
    YouTrackDBEnginesManager.instance().startUp(false);
    storage = new Fixture(folder.getRoot().toPath());
    storage.install(wal, table, cache);
    source = storage.new DiskBackupCoverageSource();
    sideFile = folder.getRoot().toPath().resolve(ChangedPageTrackerFile.FILE_NAME);
    when(wal.activeSegment()).thenReturn(17L);
    when(table.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(cache.getMinimalNotFlushedSegment()).thenReturn(null);
    when(wal.preflightCut(17)).thenReturn(new CutPreflight(false, 17,
        new LogSequenceNumber(17, 32)));
  }

  @After
  public void clearInterrupt() {
    Thread.interrupted();
  }

  /** Literal independent answers cover absent, lower, equal and above-anchor protections. */
  @Test
  public void samplesInOrderAndUsesEveryPresentMinimumWithoutOtherWork() throws Exception {
    Long[][] cases = {{-1L, null, 17L}, {3L, null, 3L}, {-1L, 5L, 5L},
        {3L, 5L, 3L}, {5L, 3L, 3L}, {5L, 5L, 5L}, {19L, 23L, 17L},
        {0L, 8L, 0L}};
    for (var row : cases) {
      when(table.getSegmentEarliestNotPersistedOperation()).thenReturn(row[0]);
      when(cache.getMinimalNotFlushedSegment()).thenReturn(row[1]);
      clearInvocations(wal, table, cache);
      assertEquals(row[2].longValue(), sample());
      var order = inOrder(wal, table, cache);
      order.verify(wal).activeSegment();
      order.verify(table).compactTable();
      order.verify(table).getSegmentEarliestNotPersistedOperation();
      order.verify(cache).getMinimalNotFlushedSegment();
      verifyNoMoreInteractions(wal, table, cache);
    }
  }

  /** Registration after the table read cannot raise the original anchor after cache waiting. */
  @Test
  public void registrationAndRotationAfterTableReadRemainCappedAtFirstAnchor() throws Exception {
    raceSample(false);
  }

  /** A sampled earlier operation still protects WAL after persistence clears both live terms. */
  @Test
  public void earlierOperationRemainsProtectedAfterItPersistsDuringCacheWait() throws Exception {
    raceSample(true);
  }

  private void raceSample(boolean earlier) throws Exception {
    var operations = new AtomicOperationsTable(100, 0);
    if (earlier) {
      operations.startOperation(1, 3);
      operations.commitOperation(1);
    }
    var observedTable = org.mockito.Mockito.spy(operations);
    storage.install(wal, observedTable, cache);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(call -> {
      entered.countDown();
      await(release);
      return null;
    }).when(cache).getMinimalNotFlushedSegment();
    var pool = Executors.newSingleThreadExecutor();
    try {
      var result = pool.submit(this::sample);
      await(entered);
      verify(observedTable).getSegmentEarliestNotPersistedOperation();
      if (earlier) {
        operations.persistOperation(1);
      } else {
        operations.startOperation(1, 23);
      }
      when(wal.activeSegment()).thenReturn(23L);
      release.countDown();
      assertEquals(earlier ? 3L : 17L, result.get(10, TimeUnit.SECONDS).longValue());
      verify(wal, times(1)).activeSegment();
      verify(cache, times(1)).getMinimalNotFlushedSegment();
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /** Preflight delegates each exact fixed value, preserving identity, null and no-removal results. */
  @Test
  public void preflightIsCurrentExactAndNonDestructive() throws Exception {
    var prediction = new CutPreflight(false, 9, new LogSequenceNumber(12, 32));
    when(wal.preflightCut(13)).thenReturn(prediction, (CutPreflight) null);
    assertSame(prediction, source.preflightCut(13));
    assertNull(source.preflightCut(13));
    verify(wal, times(2)).preflightCut(13);
    verifyNoMoreInteractions(wal, table, cache);
  }

  /** Every collaborator failure escapes unchanged. Facade ordinary failures revoke coverage. */
  @Test
  public void failuresPreserveIdentityAndNeverInventCoverage() throws Exception {
    for (int stage = 0; stage < 5; stage++) {
      for (var failure : List.of(new IllegalStateException("unavailable"),
          new AssertionError("fatal"), new IOException("I/O"))) {
        // Only preflight declares IOException at these collaborator seams.
        if (failure instanceof IOException && stage != 4) {
          continue;
        }
        org.mockito.Mockito.reset(wal, table, cache);
        when(wal.activeSegment()).thenReturn(17L);
        when(table.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
        when(cache.getMinimalNotFlushedSegment()).thenReturn(null);
        switch (stage) {
          case 0 -> doThrow(failure).when(wal).activeSegment();
          case 1 -> doThrow(failure).when(table).compactTable();
          case 2 -> doThrow(failure).when(table).getSegmentEarliestNotPersistedOperation();
          case 3 -> doThrow(failure).when(cache).getMinimalNotFlushedSegment();
          case 4 -> doThrow(failure).when(wal).preflightCut(17);
          default -> throw new AssertionError(stage);
        }
        int selectedStage = stage;
        assertSame(failure, assertThrows(Throwable.class, () -> {
          if (selectedStage == 4) {
            source.preflightCut(17);
          } else {
            sample();
          }
        }));
        var tracker = new ChangedPageTracker();
        var context = ChangedPageTrackerBackup.begin(tracker, null, true);
        Files.deleteIfExists(sideFile);
        if (failure instanceof Error) {
          assertSame(failure, assertThrows(Error.class,
              () -> complete(tracker, context)));
        } else {
          assertEquals(Status.COVERAGE_UNAVAILABLE, complete(tracker, context));
        }
        assertFalse(Files.exists(sideFile));
        assertFalse(order(tracker).isLocked());
      }
    }
  }

  /** Inherited cancellation is cleared only at cache entry and restored on every exit. */
  @Test
  public void inheritedInterruptSurvivesSuccessRuntimeAndError() throws Exception {
    for (var failure : new Throwable[] {null, new IllegalStateException("cache"),
        new AssertionError("cache")}) {
      doAnswer(call -> {
        assertTrue(Thread.currentThread().isInterrupted());
        return 17L;
      }).when(wal).activeSegment();
      doAnswer(call -> {
        assertTrue(Thread.currentThread().isInterrupted());
        return -1L;
      }).when(table).getSegmentEarliestNotPersistedOperation();
      doAnswer(call -> {
        assertFalse(Thread.currentThread().isInterrupted());
        if (failure != null) {
          throw failure;
        }
        return null;
      }).when(cache).getMinimalNotFlushedSegment();
      Thread.currentThread().interrupt();
      try {
        if (failure == null) {
          assertEquals(17, sample());
        } else {
          assertSame(failure, assertThrows(Throwable.class, this::sample));
        }
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  /** Preflight passes cancellation and the exact IOException through without clearing either. */
  @Test
  public void preflightDoesNotClearInheritedInterruptOnIoFailure() throws Exception {
    var failure = new IOException("preflight");
    doAnswer(call -> {
      assertTrue(Thread.currentThread().isInterrupted());
      throw failure;
    }).when(wal).preflightCut(17);
    Thread.currentThread().interrupt();
    try {
      assertSame(failure, assertThrows(IOException.class, () -> source.preflightCut(17)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  /** A genuinely blocked Future wait succeeds with inherited interruption, also through complete. */
  @Test
  public void inheritedInterruptAllowsWaitAndNormalPublication() throws Exception {
    interruptWait(true, false);
    interruptWait(true, true);
  }

  /** A new interrupt fails the blocked wait once, preserves failure identity and restores the flag. */
  @Test
  public void newInterruptFailsClosedWithoutRetryDirectlyAndThroughFacade() throws Exception {
    interruptWait(false, false);
    interruptWait(false, true);
  }

  private void interruptWait(boolean inherited, boolean facade) throws Exception {
    var waiting = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var task = new FutureTask<Long>(() -> {
      await(release);
      return null;
    });
    var failure = new AtomicReference<IllegalStateException>();
    doAnswer(call -> {
      waiting.countDown();
      try {
        return task.get(10, TimeUnit.SECONDS);
      } catch (Exception cause) {
        var thrown = new IllegalStateException(new RuntimeException(cause));
        failure.set(thrown);
        throw thrown;
      }
    }).when(cache).getMinimalNotFlushedSegment();
    var tracker = new ChangedPageTracker();
    var context = ChangedPageTrackerBackup.begin(tracker, null, true);
    var sampler = new AtomicReference<Thread>();
    var pool = Executors.newFixedThreadPool(2);
    clearInvocations(cache);
    try {
      pool.submit(task);
      var result = pool.submit(() -> {
        sampler.set(Thread.currentThread());
        if (inherited) {
          Thread.currentThread().interrupt();
        }
        try {
          if (facade) {
            assertEquals(inherited ? Status.SAVED_CONTINUITY : Status.COVERAGE_UNAVAILABLE,
                complete(tracker, context));
          } else if (inherited) {
            assertEquals(17, sample());
          } else {
            var thrown = assertThrows(IllegalStateException.class, this::sample);
            assertSame(failure.get(), thrown);
            assertTrue(thrown.getCause().getCause() instanceof InterruptedException);
          }
          assertTrue(Thread.currentThread().isInterrupted());
        } finally {
          Thread.interrupted();
        }
        return null;
      });
      await(waiting);
      // Observe the actual Future.get wait, not just entry to the collaborator.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (sampler.get().getState() != Thread.State.TIMED_WAITING
          && !result.isDone() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(Thread.State.TIMED_WAITING, sampler.get().getState());
      if (!inherited) {
        sampler.get().interrupt();
      }
      if (inherited) {
        release.countDown();
      }
      result.get(10, TimeUnit.SECONDS);
      verify(cache, times(1)).getMinimalNotFlushedSegment();
      assertFalse(order(tracker).isLocked());
    } finally {
      release.countDown();
      try {
        // Drain the helper normally before shutdown can interrupt its latch wait.
        assertNull(task.get(10, TimeUnit.SECONDS));
      } finally {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }

  /** Cache waiting leaves save order free, while the later preflight owns that same domain. */
  @Test
  public void facadeWaitLeavesSaveOrderFreeAndPreflightOwnsIt() throws Exception {
    var tracker = new ChangedPageTracker();
    var context = ChangedPageTrackerBackup.begin(tracker, null, true);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(call -> {
      assertFalse(order(tracker).isHeldByCurrentThread());
      entered.countDown();
      await(release);
      return null;
    }).when(cache).getMinimalNotFlushedSegment();
    doAnswer(call -> {
      assertTrue(order(tracker).isHeldByCurrentThread());
      return new CutPreflight(false, 17, new LogSequenceNumber(17, 32));
    }).when(wal).preflightCut(17);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var completion = pool.submit(() -> complete(tracker, context));
      await(entered);
      pool.submit(() -> {
        assertTrue(order(tracker).tryLock(5, TimeUnit.SECONDS));
        order(tracker).unlock();
        return null;
      }).get(10, TimeUnit.SECONDS);
      release.countDown();
      assertEquals(Status.SAVED_CONTINUITY, completion.get(10, TimeUnit.SECONDS));
      assertEquals(new LogSequenceNumber(17, 32),
          ChangedPageTrackerStartup.load(sideFile).coverageLsn());
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * A real ordered checkpoint overtakes the fixed sample. A committed page change made between
   * sample and capture survives a fresh physical open with coverage proved by the real startup path.
   * Real rotation, retention clamps, a discarded sampled segment and lock progress are observed.
   */
  @Test
  public void checkpointOvertakesSampleAndFreshOpenKeepsPersistedPageMarks() throws Exception {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT.getKey(), false);
    config.setProperty(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.getKey(), 3600);
    config.setProperty(GlobalConfiguration.STORAGE_COLLECTION_GC_PAUSE_INTERVAL.getKey(), 3600);
    var root = folder.newFolder("database").toPath();
    var manager = (YouTrackDBImpl) YourTracks.instance(root.toString(), config);
    manager.create("coverage", DatabaseType.DISK, "admin", "admin", "admin");
    var session = manager.open("coverage", "admin", "admin");
    var actual = (DiskStorage) session.getStorage();
    var firstStorage = actual;
    var pool = Executors.newFixedThreadPool(2);
    var sampled = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    try {
      var realWal = actual.getWALInstance();
      var realCache = actual.getWriteCache();
      var realTable = (AtomicOperationsTable) field(AbstractStorage.class, actual,
          "atomicOperationsTable");
      var tracker = (ChangedPageTracker) field(DiskStorage.class, actual, "changedPageTracker");
      var state = (ScalableRWLock) field(AbstractStorage.class, actual, "stateLock");
      storage.install(realWal, realTable, realCache);
      var path = root.resolve("coverage").resolve(ChangedPageTrackerFile.FILE_NAME);
      var writingSession = session;
      writingSession.executeInTx(tx -> writingSession.command("create class CoveragePage"));
      // Supplier backup remains dormant: baseline output has no continuity and consumes no seal.
      var context = ChangedPageTrackerBackup.begin(tracker, null, true);
      var seal = field(ChangedPageTracker.class, tracker, "sealed");
      var output = new java.io.ByteArrayOutputStream();
      var unit = actual.fullBackup(java.util.Collections::emptyIterator, name -> output,
          name -> {
            throw new AssertionError("No old output");
          });
      assertSame(seal, field(ChangedPageTracker.class, tracker, "sealed"));
      var metadata = DiskStorage.inspectBackupUnit(unit, "coverage", actual.getUuid(),
          new java.io.ByteArrayInputStream(output.toByteArray()), null).metadata();
      assertNotNull(metadata);
      assertNull(metadata.continuity());
      realRotationDuringCacheWait(realWal);
      storage.install(realWal, realTable, realCache);
      // Start with a written anchor and no earlier dirty page or operation protection.
      realCache.flush();
      realWal.appendNewSegment();
      realWal.log(new EmptyWALRecord());
      realWal.flush();
      long originalAnchor = realWal.activeSegment();
      var fixed = new AtomicReference<Long>();
      var prediction = new AtomicReference<CutPreflight>();
      var delegated = new ChangedPageTrackerBackup.CoverageSource() {
        public long sampleBoundary() throws IOException {
          long boundary = source.sampleBoundary();
          fixed.set(boundary);
          sampled.countDown();
          await(resume);
          return boundary;
        }

        public CutPreflight preflightCut(long boundary) throws IOException {
          assertEquals(fixed.get().longValue(), boundary);
          assertTrue(orderHeld(tracker));
          var current = source.preflightCut(boundary);
          prediction.set(current);
          return current;
        }
      };
      var completion = pool.submit(() -> {
        state.readLock().lock();
        try {
          return ChangedPageTrackerBackup.complete(tracker, context, path,
              new CompletionFacts(true, true), delegated);
        } finally {
          state.readLock().unlock();
        }
      });
      await(sampled);
      assertEquals(originalAnchor, fixed.get().longValue());
      writingSession.executeInTx(
          tx -> writingSession.command("insert into CoveragePage set value = 42"));
      realCache.flush();
      realWal.flush();
      var updates = new ArrayList<AbstractPageWALRecord>();
      var records = realWal.read(realWal.begin(originalAnchor), 10000);
      while (!records.isEmpty()) {
        for (var record : records) {
          if (record instanceof AbstractPageWALRecord update) {
            updates.add(update);
          }
        }
        records = realWal.next(records.getLast().getLsn(), 10000);
      }
      assertFalse("Real page changes must precede capture", updates.isEmpty());
      // Retention lowers a requested cut boundary regardless of background writer progress.
      var retained = realWal.end();
      realWal.addCutTillLimit(retained);
      realWal.appendNewSegment();
      assertTrue(realWal.activeSegment() > originalAnchor);
      realWal.flush();
      // Physical inventory is stable after flush, unlike the writtenUpTo-filtered segment view.
      var before = walInventory(root.resolve("coverage"));
      assertTrue("Preflight must have older inventory available to cut", before.size() > 1);
      var clamped = source.preflightCut(realWal.activeSegment());
      assertTrue(clamped.removesSegments());
      assertTrue(clamped.effectiveBoundary() <= retained.getSegment());
      assertEquals(before, walInventory(root.resolve("coverage")));
      realWal.log(new EmptyWALRecord());
      realWal.flush();
      // Now the writer is newer than the registered retention limit. Retention alone clamps it.
      assertEquals(retained.getSegment(),
          source.preflightCut(realWal.activeSegment()).effectiveBoundary());
      realWal.removeCutTillLimit(retained);
      // Flush has drained earlier writes. Holding the writer lock keeps the next segment unwritten
      // even if preflight is delayed, without changing the live WAL's scheduling configuration.
      var writerLock = (ReentrantLock) field(CASDiskWriteAheadLog.class, realWal,
          "recordsWriterLock");
      assertTrue(writerLock.tryLock(10, TimeUnit.SECONDS));
      try {
        long writtenSegment = realWal.end().getSegment();
        realWal.appendNewSegment();
        assertTrue(realWal.activeSegment() > writtenSegment);
        // With retention removed, only the written boundary protects this unwritten segment.
        assertEquals(writtenSegment,
            source.preflightCut(realWal.activeSegment()).effectiveBoundary());
      } finally {
        writerLock.unlock();
      }
      realWal.log(new EmptyWALRecord());
      realWal.flush();
      long checkpointBoundary = realWal.activeSegment();
      var checkpoint = pool.submit(() -> {
        state.readLock().lock();
        try {
          return tracker.checkpoint(path, realWal, checkpointBoundary);
        } finally {
          state.readLock().unlock();
        }
      }).get(10, TimeUnit.SECONDS);
      assertTrue("The overtaking checkpoint must actually remove sampled inventory",
          checkpoint.segmentsRemoved());
      assertTrue(realWal.begin().getSegment() > originalAnchor);
      resume.countDown();
      assertEquals(Status.SAVED_CONTINUITY, completion.get(10, TimeUnit.SECONDS));
      assertNotNull(prediction.get().coverageLsn());
      assertFalse("No-removal coverage remains usable", prediction.get().removesSegments());
      assertTrue(prediction.get().coverageLsn().getSegment() > fixed.get());
      assertEquals(prediction.get().coverageLsn(),
          ChangedPageTrackerStartup.load(path).coverageLsn());
      // A second thread can acquire both WAL locks after preflight, including after a failure.
      pool.submit(() -> {
        realWal.appendNewSegment();
        realWal.preflightCut(checkpointBoundary);
        return null;
      }).get(10, TimeUnit.SECONDS);
      var coverage = prediction.get().coverageLsn();
      realWal.addCutTillLimit(coverage);
      byte[] saved = Files.readAllBytes(path);
      // Normal close tears down caches. Preserve the completion file across close-time checkpoints.
      session.close();
      session = null;
      manager.close();
      assertThrows(IllegalStateException.class, () -> source.preflightCut(checkpointBoundary));
      pool.submit(() -> assertThrows(IllegalStateException.class,
          () -> source.preflightCut(checkpointBoundary))).get(10, TimeUnit.SECONDS);
      Files.write(path, saved);
      manager = (YouTrackDBImpl) YourTracks.instance(root.toString(), config);
      session = manager.open("coverage", "admin", "admin");
      actual = (DiskStorage) session.getStorage();
      assertNotSame(firstStorage, actual);
      var reopened = (ChangedPageTracker) field(DiskStorage.class, actual, "changedPageTracker");
      assertTrue("Real startup validation must retain completion trust", reopened.isTrusted());
      for (var update : updates) {
        assertTrue("Persisted page mark must survive capture and fresh open",
            candidates(reopened, realCache.internalFileId(update.getFileId()))
                .contains(update.getPageIndex()));
      }
    } finally {
      resume.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      Thread.interrupted();
      if (session != null) {
        session.close();
      }
      manager.close();
      for (var name : List.of("fuzzyCheckpointTask", "recordsGcTask")) {
        var task = (java.util.concurrent.Future<?>) field(DiskStorage.class, firstStorage, name);
        if (task != null) {
          task.cancel(false);
        }
      }
    }
  }

  private static List<Path> walInventory(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      return files.filter(path -> path.getFileName().toString().endsWith(".wal"))
          .sorted().toList();
    }
  }

  private void realRotationDuringCacheWait(WriteAheadLog realWal) throws Exception {
    var observed = mock(WriteAheadLog.class, org.mockito.AdditionalAnswers.delegatesTo(realWal));
    var operations = new AtomicOperationsTable(100, 0);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(call -> {
      entered.countDown();
      await(release);
      return null;
    }).when(cache).getMinimalNotFlushedSegment();
    storage.install(observed, operations, cache);
    long anchor = realWal.activeSegment();
    var worker = Executors.newSingleThreadExecutor();
    try {
      var sample = worker.submit(this::sample);
      await(entered);
      realWal.appendNewSegment();
      realWal.log(new EmptyWALRecord());
      realWal.flush();
      long rotated = realWal.activeSegment();
      assertTrue(rotated > anchor);
      operations.startOperation(1, rotated);
      release.countDown();
      assertEquals(anchor, sample.get(10, TimeUnit.SECONDS).longValue());
      verify(observed, times(1)).activeSegment();
    } finally {
      release.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private static boolean orderHeld(ChangedPageTracker tracker) {
    try {
      return order(tracker).isHeldByCurrentThread();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static List<Long> candidates(ChangedPageTracker tracker, int file) throws Exception {
    var active = field(ChangedPageTracker.class, tracker, "active");
    var collect = active.getClass().getDeclaredMethod("forEachCandidate", int.class,
        java.util.function.LongConsumer.class);
    collect.setAccessible(true);
    var pages = new ArrayList<Long>();
    collect.invoke(active, file, (java.util.function.LongConsumer) pages::add);
    return pages;
  }

  private long sample() throws IOException {
    storage.readLock();
    try {
      return source.sampleBoundary();
    } finally {
      storage.readUnlock();
    }
  }

  private Status complete(ChangedPageTracker tracker, ChangedPageTrackerBackup.Context context) {
    storage.readLock();
    try {
      return ChangedPageTrackerBackup.complete(tracker, context, sideFile,
          new CompletionFacts(true, true), source);
    } finally {
      storage.readUnlock();
    }
  }

  private static ReentrantLock order(ChangedPageTracker tracker) throws Exception {
    return (ReentrantLock) field(ChangedPageTracker.class, tracker, "saveOrder");
  }

  private static Object field(Class<?> owner, Object target, String name) throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue("bounded latch", latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  /** No open database is needed for controlled schedules. Only inherited collaborators are set. */
  private static final class Fixture extends DiskStorage {
    Fixture(Path path) {
      super("coverage", path.toString(), 1, null, null, 16 * 1024 * 1024, 16 * 1024 * 1024,
          null);
    }

    void install(WriteAheadLog wal, AtomicOperationsTable table, WriteCache cache) {
      writeAheadLog = wal;
      atomicOperationsTable = table;
      writeCache = cache;
    }

    void readLock() {
      stateLock.readLock().lock();
    }

    void readUnlock() {
      stateLock.readLock().unlock();
    }
  }
}
