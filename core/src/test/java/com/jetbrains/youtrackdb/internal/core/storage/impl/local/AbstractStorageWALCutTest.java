package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

/** Verifies that full checkpoints retain the earliest operation-owned or cache-owned WAL. */
public class AbstractStorageWALCutTest {

  private AbstractStorage storage;
  private WriteAheadLog writeAheadLog;
  private WriteCache writeCache;
  private AtomicOperationsTable atomicOperationsTable;
  private LogSequenceNumber checkpointLsn;

  @Before
  public void setUp() throws IOException {
    storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    writeAheadLog = mock(WriteAheadLog.class);
    writeCache = mock(WriteCache.class);
    atomicOperationsTable = mock(AtomicOperationsTable.class);
    checkpointLsn = new LogSequenceNumber(20, 1);

    storage.writeAheadLog = writeAheadLog;
    storage.writeCache = writeCache;
    storage.atomicOperationsTable = atomicOperationsTable;

    when(writeAheadLog.log(any())).thenReturn(checkpointLsn);
    when(atomicOperationsTable.getSegmentEarliestOperationInProgress()).thenReturn(-1L);
  }

  /** A failed cache page keeps its WAL segment and leaves the storage dirty. */
  @Test
  public void cacheOnlyProtectionCutsToCacheBoundaryAndKeepsDirtyMarker() throws Exception {
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(7L);

    storage.flushAllData();

    verify(writeAheadLog).cutAllSegmentsSmallerThan(7);
    verify(writeAheadLog, never()).cutTill(any());
    verify(storage, never()).clearStorageDirty();
  }

  /** An unresolved committed operation keeps its WAL segment when the cache has no boundary. */
  @Test
  public void operationOnlyProtectionCutsToOperationBoundaryAndKeepsDirtyMarker()
      throws Exception {
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(5L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);

    storage.flushAllData();

    verify(writeAheadLog).cutAllSegmentsSmallerThan(5);
    verify(writeAheadLog, never()).cutTill(any());
    verify(storage, never()).clearStorageDirty();
  }

  /** The earlier operation boundary wins when both owners retain different WAL segments. */
  @Test
  public void operationBoundaryWinsWhenEarlierThanCacheBoundary() throws Exception {
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(3L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(9L);

    storage.flushAllData();

    verify(writeAheadLog).cutAllSegmentsSmallerThan(3);
    verify(writeAheadLog, never()).cutTill(any());
    verify(storage, never()).clearStorageDirty();
  }

  /** With no owner, a full checkpoint cuts to its record and clears the dirty marker. */
  @Test
  public void noProtectionPerformsNormalCutAndClearsDirtyMarker() throws Exception {
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);

    storage.flushAllData();

    verify(writeAheadLog).cutTill(checkpointLsn);
    verify(writeAheadLog, never())
        .cutAllSegmentsSmallerThan(org.mockito.ArgumentMatchers.anyLong());
    verify(storage).clearStorageDirty();
  }

  /** A failed cache flush prevents both WAL deletion branches and dirty-marker cleanup. */
  @Test
  public void cacheFlushFailurePreventsWalCutAndDirtyMarkerChange() throws Exception {
    doThrow(new RuntimeException("latched page write failure")).when(writeCache).flush();

    assertThatThrownBy(storage::flushAllData)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("latched page write failure");

    verify(writeAheadLog, never()).cutTill(any());
    verify(writeAheadLog, never())
        .cutAllSegmentsSmallerThan(org.mockito.ArgumentMatchers.anyLong());
    verify(storage, never()).clearStorageDirty();
    verify(atomicOperationsTable, never()).getSegmentEarliestNotPersistedOperation();
    verify(writeCache, never()).getMinimalNotFlushedSegment();
  }

  /** A failed data-file force in the full flush cannot delete WAL or clear the dirty marker. */
  @Test
  public void fullCheckpointSyncFailurePreventsEveryWalCut() throws Exception {
    doThrow(new RuntimeException("injected file force failure")).when(writeCache).flush();

    assertThatThrownBy(storage::flushAllData)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("injected file force failure");
    verify(writeAheadLog, never()).cutTill(any());
    verify(writeAheadLog, never())
        .cutAllSegmentsSmallerThan(org.mockito.ArgumentMatchers.anyLong());
    verify(storage, never()).clearStorageDirty();
  }

  /** A vacuum force failure ends the attempt before WAL cleanup. */
  @Test
  public void vacuumSyncFailurePreventsFurtherCleanup() throws Exception {
    setPrivateField(storage, "stateLock", new ScalableRWLock());
    setPrivateField(storage, "walVacuumInProgress", new AtomicBoolean(true));
    storage.status = Storage.STATUS.OPEN;
    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {3L});
    when(writeAheadLog.activeSegment()).thenReturn(4L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    doThrow(new IOException("injected file force failure")).when(writeCache).syncDataFiles(4L);

    storage.runWALVacuum();

    verify(writeCache).syncDataFiles(4L);
    verify(writeAheadLog, never()).cutTill(any());
    verify(writeAheadLog, never())
        .cutAllSegmentsSmallerThan(org.mockito.ArgumentMatchers.anyLong());
  }

  /** A failed initial shutdown checkpoint leaves OPEN so a later shutdown can retry. */
  @Test
  public void shutdownCheckpointFailureReturnsToOpenForRetry() throws Exception {
    setPrivateField(storage, "shutdownDuration",
        com.jetbrains.youtrackdb.internal.common.profiler.metrics.Stopwatch.NOOP);
    doAnswer(invocation -> false).when(storage).isInError();
    setPrivateField(storage, "indexEngines", new java.util.ArrayList<>());
    storage.status = Storage.STATUS.OPEN;
    doThrow(new RuntimeException("injected initial force failure"))
        .when(storage).flushAllData();

    assertThatThrownBy(storage::doShutdown)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("injected initial force failure");
    assertThat(storage.status).isEqualTo(Storage.STATUS.OPEN);
    // The retry reaches the next checkpoint rather than failing its status guard.
    assertThatThrownBy(storage::doShutdown)
        .hasMessageContaining("injected initial force failure");
    assertThat(storage.status).isEqualTo(Storage.STATUS.OPEN);
    verify(storage, times(2)).flushAllData();
  }

  /** A histogram-bearing storage remains usable after a failed checkpoint and closes on retry. */
  @Test(timeout = 10_000)
  public void shutdownCheckpointFailureRestoresHistogramAndCompletesRetry() throws Exception {
    prepareShutdownTeardown();
    doAnswer(invocation -> false).when(storage).isInError();
    final var monitor = mock(StaleTransactionMonitor.class);
    setPrivateField(storage, "staleTransactionMonitor", monitor);
    final var histogramCache = new ConcurrentHashMap<Integer,
        com.jetbrains.youtrackdb.internal.core.index.engine.HistogramSnapshot>();
    final var manager = new IndexHistogramManager(storage, "test-index", 1, true,
        histogramCache, IntegerSerializer.INSTANCE,
        BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION),
        IntegerSerializer.ID);
    final var fileId = IndexHistogramManager.class.getDeclaredField("fileId");
    fileId.setAccessible(true);
    fileId.setLong(manager, 42L);
    final var rebalanceGuard = IndexHistogramManager.class.getDeclaredField("rebalanceInProgress");
    rebalanceGuard.setAccessible(true);
    final var guard = (AtomicBoolean) rebalanceGuard.get(manager);
    final var engine = mock(BTreeSingleValueIndexEngine.class);
    when(engine.getHistogramManager()).thenReturn(manager);
    setPrivateField(storage, "indexEngines", new java.util.ArrayList<>(java.util.List.of(engine)));
    doThrow(new RuntimeException("injected initial force failure"))
        .doNothing().when(storage).flushAllData();

    assertThatThrownBy(storage::doShutdown)
        .hasMessageContaining("injected initial force failure");
    assertThat(storage.status).isEqualTo(Storage.STATUS.OPEN);
    assertThat(guard.get()).isFalse();
    assertThat(fileId.getLong(manager)).isEqualTo(42L);
    verify(monitor, never()).stop();
    storage.doShutdown();
    assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
    assertThat(guard.get()).isTrue();
    assertThat(fileId.getLong(manager)).isEqualTo(-1L);
    verify(monitor).stop();
    verify(storage, times(2)).flushAllData();
  }

  /** A histogram-statistics flush error is logged without skipping the checkpoint or shutdown. */
  @Test
  public void shutdownContinuesAfterHistogramStatisticsFlushFailure() throws Exception {
    prepareShutdownTeardown();
    doAnswer(invocation -> false).when(storage).isInError();
    final var manager = mock(IndexHistogramManager.class);
    when(manager.getName()).thenReturn("failing-stats");
    doThrow(new RuntimeException("injected statistics flush failure"))
        .when(manager).flushIfDirty();
    final var engine = mock(BTreeSingleValueIndexEngine.class);
    when(engine.getHistogramManager()).thenReturn(manager);
    setPrivateField(storage, "indexEngines", new java.util.ArrayList<>(java.util.List.of(engine)));

    storage.doShutdown();

    verify(manager).blockRebalancesForStorageShutdown();
    verify(manager).flushIfDirty();
    verify(storage).flushAllData();
    verify(manager).closeStatsFileAfterStorageCheckpoint();
    assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
  }

  /** A histogram file close error does not undo a successful checkpoint or halt shutdown. */
  @Test
  public void shutdownContinuesAfterHistogramStatisticsCloseFailure() throws Exception {
    prepareShutdownTeardown();
    doAnswer(invocation -> false).when(storage).isInError();
    final var manager = mock(IndexHistogramManager.class);
    when(manager.getName()).thenReturn("failing-stats");
    doThrow(new RuntimeException("injected statistics close failure"))
        .when(manager).closeStatsFileAfterStorageCheckpoint();
    final var engine = mock(BTreeSingleValueIndexEngine.class);
    when(engine.getHistogramManager()).thenReturn(manager);
    setPrivateField(storage, "indexEngines", new java.util.ArrayList<>(java.util.List.of(engine)));

    storage.doShutdown();

    verify(manager).flushIfDirty();
    verify(storage).flushAllData();
    verify(manager).closeStatsFileAfterStorageCheckpoint();
    verify(writeAheadLog).close();
    assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
  }

  /** A failed final file force preserves dirty metadata and reports after teardown. */
  @Test
  public void shutdownFinalCacheForceFailureMarksDirtyAndReports() throws Exception {
    prepareShutdownTeardown();
    // The last pre-close branch skips configuration teardown in this minimal mock fixture.
    doAnswer(invocation -> true).when(storage).isInError();
    storage.status = Storage.STATUS.OPEN;
    storage.readCache = mock(com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class);
    doThrow(new IOException("injected final force failure"))
        .when(storage.readCache).closeStorage(writeCache);
    assertThatThrownBy(storage::doShutdown)
        .hasMessageContaining("Error during closing of disk cache");
    verify(storage).makeStorageDirty();
    verify(storage).postCloseSteps(false, true, 0L);
    assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
  }

  /** A failed dirty-marker write cannot mask the cache error or skip remaining teardown. */
  @Test
  public void shutdownDirtyMarkerFailurePreservesOriginalCacheError() throws Exception {
    prepareShutdownTeardown();
    // Even without another storage error, metadata teardown must see the failed cache close.
    doAnswer(invocation -> false).when(storage).isInError();
    storage.status = Storage.STATUS.OPEN;
    storage.readCache = mock(com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class);
    final var cacheFailure = new IOException("injected final force failure");
    final var dirtyFailure = new IOException("injected metadata write failure");
    final var metadataCloseFailure = new IOException("injected metadata close failure");
    doThrow(cacheFailure).when(storage.readCache).closeStorage(writeCache);
    doThrow(dirtyFailure).when(storage).makeStorageDirty();
    doThrow(metadataCloseFailure).when(storage).postCloseSteps(false, true, 0L);

    assertThatThrownBy(storage::doShutdown)
        .hasMessageContaining("Error during closing of disk cache")
        .satisfies(failure -> {
          assertThat(failure.getCause()).isSameAs(cacheFailure);
          assertThat(cacheFailure.getSuppressed())
              .containsExactly(dirtyFailure, metadataCloseFailure);
        });
    verify(writeAheadLog).close();
    verify(storage).postCloseSteps(false, true, 0L);
    verify(storage, never()).postCloseSteps(false, false, 0L);
    assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
  }

  /** A full checkpoint rejects in-progress operations before making any deletion decision. */
  @Test
  public void inProgressOperationPreventsEveryWalCutAndDirtyMarkerChange() throws Exception {
    when(atomicOperationsTable.getSegmentEarliestOperationInProgress()).thenReturn(4L);

    assertThatThrownBy(storage::flushAllData)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("atomic operations in progress");

    verify(writeAheadLog, never()).cutTill(any());
    verify(writeAheadLog, never())
        .cutAllSegmentsSmallerThan(org.mockito.ArgumentMatchers.anyLong());
    verify(storage, never()).clearStorageDirty();
  }

  /** A failed vacuum flush ends the invocation without synchronizing or deleting WAL. */
  @Test(timeout = 10_000)
  public void vacuumStopsWhenFlushCannotProgress() throws Exception {
    setPrivateField(storage, "stateLock", new ScalableRWLock());
    setPrivateField(storage, "walVacuumInProgress", new AtomicBoolean(true));
    storage.status = Storage.STATUS.OPEN;

    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {3L});
    when(writeAheadLog.activeSegment()).thenReturn(4L);
    doThrow(new RuntimeException("latched page write failure"))
        .when(writeCache).flushTillSegment(4L);

    storage.runWALVacuum();

    verify(writeCache).getMinimalNotFlushedSegment();
    verify(writeCache).flushTillSegment(4L);
    verify(writeCache, never()).syncDataFiles(org.mockito.ArgumentMatchers.anyLong());
  }

  /** A tracker-only boundary stops vacuum without synchronization and releases the state lock. */
  @Test(timeout = 10_000)
  public void vacuumStopsWhenRetainedBoundaryCannotProgress() throws Exception {
    final var stateLock = new ScalableRWLock();
    final var flushCalls = new AtomicInteger();
    setPrivateField(storage, "stateLock", stateLock);
    final var vacuumInProgress = new AtomicBoolean(true);
    setPrivateField(storage, "walVacuumInProgress", vacuumInProgress);
    storage.status = Storage.STATUS.OPEN;

    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {4L, 8L});
    when(writeAheadLog.activeSegment()).thenReturn(9L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(4L);
    doAnswer(
        invocation -> {
          if (flushCalls.incrementAndGet() > 1) {
            throw new AssertionError("vacuum retried an unchanged tracker-only boundary");
          }
          return null;
        })
        .when(writeCache)
        .flushTillSegment(6L);

    storage.runWALVacuum();

    verify(writeCache).flushTillSegment(6L);
    verify(writeCache, never()).syncDataFiles(org.mockito.ArgumentMatchers.anyLong());
    verify(atomicOperationsTable, never()).compactTable();
    assertThat(vacuumInProgress).isFalse();
    assertThat(stateLock.writeLock().tryLock(1, TimeUnit.SECONDS)).isTrue();
    stateLock.writeLock().unlock();
  }

  /** A changing cache boundary keeps vacuum flushing until it reaches the requested segment. */
  @Test
  public void vacuumContinuesWhileRetainedBoundaryProgresses() throws Exception {
    setPrivateField(storage, "stateLock", new ScalableRWLock());
    setPrivateField(storage, "walVacuumInProgress", new AtomicBoolean(true));
    storage.status = Storage.STATUS.OPEN;

    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {4L, 8L});
    when(writeAheadLog.activeSegment()).thenReturn(9L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(4L, 5L, 6L, 6L);
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);

    storage.runWALVacuum();

    verify(writeCache, times(2)).flushTillSegment(6L);
    verify(atomicOperationsTable).compactTable();
    verify(writeCache).syncDataFiles(6L);
  }

  /** A concurrently published earlier boundary receives another flush opportunity. */
  @Test
  public void vacuumRetriesWhenRetainedBoundaryMovesBackward() throws Exception {
    setPrivateField(storage, "stateLock", new ScalableRWLock());
    setPrivateField(storage, "walVacuumInProgress", new AtomicBoolean(true));
    storage.status = Storage.STATUS.OPEN;

    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {4L, 8L});
    when(writeAheadLog.activeSegment()).thenReturn(9L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(5L, 4L, 6L, 6L);
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);

    storage.runWALVacuum();

    verify(writeCache, times(2)).flushTillSegment(6L);
    verify(writeCache).syncDataFiles(6L);
  }

  /** Operation-first sampling observes a requirement transferred into cache during the sample. */
  @Test
  public void operationToCacheTransferCannotEscapeBothOwnershipSamples() throws Exception {
    var cacheRequirementPublished = new AtomicBoolean();
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenAnswer(invocation -> {
      cacheRequirementPublished.set(true);
      return -1L;
    });
    when(writeCache.getMinimalNotFlushedSegment())
        .thenAnswer(invocation -> cacheRequirementPublished.get() ? 6L : null);

    storage.flushAllData();

    var ownershipOrder = inOrder(atomicOperationsTable, writeCache);
    ownershipOrder.verify(atomicOperationsTable).getSegmentEarliestNotPersistedOperation();
    ownershipOrder.verify(writeCache).getMinimalNotFlushedSegment();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(6);
    verify(writeAheadLog, never()).cutTill(any());
    verify(storage, never()).clearStorageDirty();
  }

  /** Installs the fields needed to run shutdown through metadata teardown in this mock fixture. */
  private void prepareShutdownTeardown() throws Exception {
    setPrivateField(storage, "shutdownDuration",
        com.jetbrains.youtrackdb.internal.common.profiler.metrics.Stopwatch.NOOP);
    setPrivateField(storage, "indexEngines", new java.util.ArrayList<>());
    for (final var field : new String[] {"linkCollectionsBTreeManager", "collections",
        "collectionMap", "indexEngineNameMap", "sharedSnapshotIndex", "visibilityIndex",
        "snapshotIndexSize", "sharedEdgeSnapshotIndex", "edgeVisibilityIndex",
        "edgeSnapshotIndexSize", "sharedIndexesSnapshot", "indexesSnapshotVisibilityIndex",
        "sharedNullIndexesSnapshot", "nullIndexSnapshotVisibilityIndex",
        "indexesSnapshotEntriesCount", "idGen", "atomicOperationsManager"}) {
      final var declared = AbstractStorage.class.getDeclaredField(field);
      setPrivateField(storage, field, mock(declared.getType()));
    }
    storage.status = Storage.STATUS.OPEN;
  }

  /** Sets a constructor-initialized field on a Mockito real-method mock. */
  private static void setPrivateField(
      final AbstractStorage target, final String name, final Object value) throws Exception {
    final Field field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
