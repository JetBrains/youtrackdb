package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.INVALIDATED;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.NO_SAVE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.PREFLIGHT_UNAVAILABLE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.SAVED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointResult;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.CutPreflight;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Direct storage bridge contracts, independent of ordinary checkpoint caller routing. */
public class DiskStorageChangedPageCheckpointTest {

  private static final LogSequenceNumber COVERAGE = new LogSequenceNumber(2, 32);

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private YouTrackDBImpl manager;
  private DatabaseSessionEmbedded session;
  private DiskStorage storage;
  private ScalableRWLock stateLock;
  private WriteAheadLog realWal;
  private WriteAheadLog wal;
  private ChangedPageTracker tracker;
  private Path sideFile;

  @Before
  public void openStorage() throws Exception {
    var config = new BaseConfiguration();
    // Keep periodic work away from the scoped WAL replacement used by these direct bridge tests.
    config.setProperty(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT.getKey(), false);
    config.setProperty(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.getKey(), 3600);
    config.setProperty(GlobalConfiguration.STORAGE_COLLECTION_GC_PAUSE_INTERVAL.getKey(), 3600);
    manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString(), config);
    manager.create("bridge", DatabaseType.DISK, "admin", "admin", "admin");
    session = manager.open("bridge", "admin", "admin");
    storage = (DiskStorage) session.getStorage();
    sideFile = folder.getRoot().toPath().resolve("bridge")
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    assertFalse(Files.exists(sideFile));
    stateLock = (ScalableRWLock) field(AbstractStorage.class, storage, "stateLock");
    tracker = (ChangedPageTracker) field(DiskStorage.class, storage, "changedPageTracker");
    realWal = storage.getWALInstance();
    wal = mock(WriteAheadLog.class);
    when(wal.preflightCut(3)).thenReturn(new CutPreflight(true, 2, COVERAGE));
    when(wal.cutAllSegmentsSmallerThan(2)).thenReturn(true);
    replaceWal(wal);
  }

  @After
  public void closeStorage() throws Exception {
    // Clear only test cancellation before normal lifecycle teardown forces real storage channels.
    Thread.interrupted();
    if (storage != null && realWal != null) {
      replaceWal(realWal);
    }
    try {
      if (session != null) {
        session.close();
      }
    } finally {
      if (manager != null) {
        manager.close();
      }
    }
  }

  /** The real publisher completes before cutting, and cache file events invalidate the same state. */
  @Test
  public void publicationPrecedesBoundedCutAndUsesWriteCacheTracker() throws Exception {
    assertSame(tracker, field(WOWCache.class, storage.getWriteCache(), "changedPageTracker"));
    doAnswer(call -> {
      assertTrue(stateLock.isReadLockedByCurrentThread());
      assertPublishedCoverage();
      return true;
    }).when(wal).cutAllSegmentsSmallerThan(2);

    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    var order = inOrder(wal);
    order.verify(wal).preflightCut(3);
    order.verify(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(NO_SAVE, true), checkpoint());

    // WOWCache owns this file-identity change. Reusing another tracker would miss it.
    storage.getWriteCache().addFile("bridge-event.bin");
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
  }

  /** Both inventory availability failures report a retryable result without writing or cutting. */
  @Test
  public void unavailablePreflightLeavesNoSideFileOrCutAndLaterCallRetries() throws Exception {
    for (var failure : List.of(new IllegalStateException("closed inventory"),
        new NoSuchElementException("changed inventory"))) {
      Files.deleteIfExists(sideFile);
      doThrow(failure).when(wal).preflightCut(3);
      assertEquals(new CheckpointResult(PREFLIGHT_UNAVAILABLE, false), checkpoint());
      assertFalse(Files.exists(sideFile));
      assertFalse(
          Files.exists(sideFile.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME)));
      verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
    }
    doReturn(new CutPreflight(true, 2, COVERAGE)).when(wal).preflightCut(3);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** A non-empty authority directory fails publication and invalidation, so no cut is permitted. */
  @Test
  public void failedDurableInvalidationReportsIOExceptionAndRetryCanPublish() throws Exception {
    Files.createDirectory(sideFile);
    var blocker = sideFile.resolve("blocker");
    Files.writeString(blocker, "do not remove");

    var failure = assertThrows(IOException.class, this::checkpoint);
    assertTrue(failure.getMessage().contains("side-file invalidation failed"));
    assertTrue(Files.exists(blocker));
    verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());

    Files.delete(blocker);
    Files.delete(sideFile);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** Cancellation can invalidate a failed save, but the bounded cutter never inherits its flag. */
  @Test
  public void interruptedPublicationPreservesFlagAndLaterCheckpointRetries() throws Exception {
    doAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      assertFalse(Files.exists(sideFile));
      return true;
    }).when(wal).cutAllSegmentsSmallerThan(2);
    Thread.currentThread().interrupt();
    try {
      assertEquals(new CheckpointResult(INVALIDATED, true), checkpoint());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    doReturn(true).when(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** A cut error follows publication without rollback, preserves interruption, and allows retry. */
  @Test
  public void cutterFailureKeepsPublicationAndInterruptStatusForCaller() throws Exception {
    assertEquals(SAVED, checkpoint().outcome());
    var failure = new IOException("cut failed");
    doAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      assertPublishedCoverage();
      throw failure;
    }).when(wal).cutAllSegmentsSmallerThan(2);
    Thread.currentThread().interrupt();
    try {
      assertSame(failure, assertThrows(IOException.class, this::checkpoint));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertPublishedCoverage();
    doReturn(true).when(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(NO_SAVE, true), checkpoint());
  }

  /** Null prediction is a contract error, and an I/O preflight failure retains its identity. */
  @Test
  public void invalidOrFailedPreflightDoesNotSaveOrCut() throws Exception {
    when(wal.preflightCut(3)).thenReturn(null);
    assertThrows(IllegalStateException.class, this::checkpoint);
    var failure = new IOException("preflight failed");
    doThrow(failure).when(wal).preflightCut(3);
    assertSame(failure, assertThrows(IOException.class, this::checkpoint));
    assertFalse(Files.exists(sideFile));
    verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
  }

  private CheckpointResult checkpoint() throws IOException {
    stateLock.readLock().lock();
    try {
      return storage.checkpointChangedPages(3);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void assertPublishedCoverage() throws Exception {
    assertTrue(Files.isRegularFile(sideFile));
    assertFalse(Files.exists(sideFile.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME)));
    try (var in = new DataInputStream(Files.newInputStream(sideFile))) {
      assertEquals(0x59544350, in.readInt());
      assertEquals(1, in.readInt());
      in.readUnsignedByte();
      assertEquals(COVERAGE, new LogSequenceNumber(in));
      assertEquals(field(ChangedPageTracker.class, tracker, "trackerId"),
          new UUID(in.readLong(), in.readLong()));
    }
  }

  private void replaceWal(WriteAheadLog replacement) throws Exception {
    stateLock.writeLock().lock();
    try {
      var field = AbstractStorage.class.getDeclaredField("writeAheadLog");
      field.setAccessible(true);
      field.set(storage, replacement);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private static Object field(Class<?> owner, Object target, String name) throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
