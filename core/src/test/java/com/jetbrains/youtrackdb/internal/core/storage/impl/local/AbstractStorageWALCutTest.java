package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ReadersWriterSpinLock;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.io.YTIOException;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MemoryWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/** Verifies checkpoint WAL retention, force ordering, and maintenance failure handling. */
public class AbstractStorageWALCutTest {

  private AbstractStorage storage;
  private WriteAheadLog writeAheadLog;
  private WriteCache writeCache;
  private AtomicOperationsTable atomicOperationsTable;
  private LogSequenceNumber checkpointLsn;

  @Before
  public void setUp() throws Exception {
    storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    writeAheadLog = mock(WriteAheadLog.class);
    writeCache = mock(WriteCache.class);
    atomicOperationsTable = mock(AtomicOperationsTable.class);
    checkpointLsn = new LogSequenceNumber(20, 1);

    storage.writeAheadLog = writeAheadLog;
    storage.writeCache = writeCache;
    storage.atomicOperationsTable = atomicOperationsTable;
    // CALLS_REAL_METHODS skips constructor field initializers.
    for (var field : new String[] {"idGen", "atomicOperationsManager"}) {
      setPrivateField(storage, field,
          mock(AbstractStorage.class.getDeclaredField(field).getType()));
    }
    setPrivateField(storage, "checkpointFloorTestAction", new AtomicReference<>());
    setPrivateField(storage, "afterCloseAtomicTestAction", new AtomicReference<>());
    setPrivateField(storage, "beforeMaintenanceFloorReadTestAction", new AtomicReference<>());
    setPrivateField(storage, "beforeMaintenanceFloorTestAction", new AtomicReference<>());
    setPrivateField(storage, "afterMaintenanceFloorTestAction", new AtomicReference<>());
    setPrivateField(storage, "afterShutdownRemarkTestAction", new AtomicReference<>());

    when(writeAheadLog.log(any())).thenReturn(checkpointLsn);
    when(writeAheadLog.activeSegment()).thenReturn(checkpointLsn.getSegment());
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

  /** A protected checkpoint saves the floor before pruning WAL and retains the dirty flag. */
  @Test
  public void protectedCheckpointPublishesFloorBeforeProtectedCut() throws Exception {
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(7L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);
    var floorObserved = new AtomicBoolean();
    storage.setCheckpointFloorActionForTesting(ignored -> {
      assertThat(cutCalls("cutAllSegmentsSmallerThan")).isZero();
      floorObserved.set(true);
    });

    storage.flushAllData();

    assertThat(floorObserved).isTrue();
    var floorThenCut = inOrder(storage, writeAheadLog);
    floorThenCut.verify(storage).saveCheckpointFloor(0L);
    floorThenCut.verify(writeAheadLog).cutAllSegmentsSmallerThan(7L);
    verify(storage, never()).clearStorageDirty();
  }

  /** A protected checkpoint cannot cut WAL or clear recovery when publishing the floor fails. */
  @Test
  public void failedProtectedFloorSavePreservesWalAndDirtyFlag() throws Exception {
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(7L);
    doThrow(new IOException("floor unavailable")).when(storage).saveCheckpointFloor(anyLong());

    assertThatThrownBy(storage::flushAllData).hasMessageContaining("checkpoint creation");

    verify(writeAheadLog, never()).cutAllSegmentsSmallerThan(anyLong());
    verify(writeAheadLog, never()).cutTill(any());
    verify(storage, never()).clearStorageDirty();
  }

  /** An unprotected checkpoint saves the floor, cuts the WAL, then clears recovery. */
  @Test
  public void normalCheckpointPublishesFloorBeforeCutAndClear() throws Exception {
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);
    var floorObserved = new AtomicBoolean();
    storage.setCheckpointFloorActionForTesting(ignored -> {
      assertThat(cutCalls("cutTill")).isZero();
      floorObserved.set(true);
    });

    storage.flushAllData();

    assertThat(floorObserved).isTrue();
    var floorCutThenClear = inOrder(storage, writeAheadLog);
    floorCutThenClear.verify(storage).saveCheckpointFloor(0L);
    floorCutThenClear.verify(writeAheadLog).cutTill(checkpointLsn);
    floorCutThenClear.verify(storage).clearStorageDirty();
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

  /** A vacuum force failure logs the error, skips WAL cleanup, and clears vacuum admission. */
  @Test
  public void vacuumSyncFailurePreventsFurtherCleanup() throws Exception {
    prepareFuzzyCheckpoint();
    final var vacuumInProgress = new AtomicBoolean(true);
    setPrivateField(storage, "walVacuumInProgress", vacuumInProgress);
    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {3L});
    when(writeAheadLog.activeSegment()).thenReturn(4L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    doThrow(new IOException("injected file force failure")).when(writeCache).syncDataFiles();

    try (var logs = LogRecordCollector.attachTo(storage.getClass())) {
      storage.runWALVacuum();
      assertThat(logs.messages()).anyMatch(message -> message.startsWith("SEVERE")
          && message.contains("Error during flushing of data for fuzzy checkpoint"));
    }

    verify(writeCache).syncDataFiles();
    verify(writeAheadLog, never()).cutTill(any());
    verify(writeAheadLog, never()).cutAllSegmentsSmallerThan(anyLong());
    assertThat(vacuumInProgress).isFalse();
  }

  /** A fuzzy force failure reaches the caller as YTIOException and skips every WAL cut. */
  @Test
  public void fuzzySyncFailurePreventsWalCutAndReports() throws Exception {
    prepareFuzzyCheckpoint();
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);
    final var failure = new IOException("injected file force failure");
    doThrow(failure).when(writeCache).syncDataFiles();

    assertThatThrownBy(storage::makeFuzzyCheckpoint)
        .isInstanceOf(YTIOException.class).hasCause(failure);

    verify(writeCache).syncDataFiles();
    verify(writeAheadLog, never()).cutAllSegmentsSmallerThan(anyLong());
    verify(writeAheadLog, never()).cutTill(any());
  }

  /** Fuzzy cleanup cuts the sampled boundary after floor, data force, WAL force, and unlock. */
  @Test
  public void fuzzyCutFollowsForceOutsideCacheLocksWithFixedBoundary() throws Exception {
    assertMaintenanceCutOutsideCacheLocks(false);
  }

  /** Vacuum cleanup cuts the sampled boundary after floor, data force, WAL force, and unlock. */
  @Test
  public void vacuumCutFollowsForceOutsideCacheLocksWithFixedBoundary() throws Exception {
    assertMaintenanceCutOutsideCacheLocks(true);
  }

  private void assertMaintenanceCutOutsideCacheLocks(boolean vacuum) throws Exception {
    prepareFuzzyCheckpoint();
    final var stateField = AbstractStorage.class.getDeclaredField("stateLock");
    stateField.setAccessible(true);
    final var stateLock = (ScalableRWLock) stateField.get(storage);
    final var vacuumInProgress = new AtomicBoolean(true);
    setPrivateField(storage, "walVacuumInProgress", vacuumInProgress);
    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {3L});
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(5L);

    // Exercise the real sync method and lock, without a production test hook or timing sleeps.
    final var cache = mock(WOWCache.class, CALLS_REAL_METHODS);
    final var filesLock = org.mockito.Mockito.spy(new ReadersWriterSpinLock());
    final var holdsField = ReadersWriterSpinLock.class.getDeclaredField("lockHolds");
    holdsField.setAccessible(true);
    final var holds = (ThreadLocal<?>) holdsField.get(filesLock);
    final var doubleWrite = mock(DoubleWriteLog.class);
    final var checkpointActive = new AtomicBoolean();
    doAnswer(invocation -> {
      checkpointActive.set(true);
      return null;
    }).when(doubleWrite).startCheckpoint();
    doAnswer(invocation -> {
      checkpointActive.set(false);
      return null;
    }).when(doubleWrite).endCheckpoint();
    final var file = mock(File.class);
    when(file.isOpen()).thenReturn(true);
    final var files = new ClosableLinkedContainer<Long, File>(1);
    files.add(7L, file);
    for (var entry : Map.<String, Object>of("filesLock", filesLock, "files", files,
        "nameIdMap", new ConcurrentHashMap<>(Map.of("data", 7)),
        "nonDurableFileIds", new IntOpenHashSet(), "callFsync", true,
        "writeAheadLog", writeAheadLog, "doubleWriteLog", doubleWrite).entrySet()) {
      final var field = WOWCache.class.getDeclaredField(entry.getKey());
      field.setAccessible(true);
      field.set(cache, entry.getValue());
    }
    doReturn(null).when(cache).getMinimalNotFlushedSegment();
    doNothing().when(cache).flushTillSegment(anyLong());
    storage.writeCache = cache;
    doAnswer(invocation -> {
      assertThat(((ModifiableInteger) holds.get()).intValue()).isEqualTo(1);
      assertThat(checkpointActive).isTrue();
      // Later samples would raise the boundary. Neither caller may resample after forcing.
      when(writeAheadLog.activeSegment()).thenReturn(30L);
      when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(7L);
      doReturn(9L).when(cache).getMinimalNotFlushedSegment();
      return null;
    }).when(file).synch();
    doAnswer(invocation -> {
      assertThat(((ModifiableInteger) holds.get()).intValue()).isZero();
      assertThat(checkpointActive).isFalse();
      assertThat(stateLock.isReadLockedByCurrentThread()).isTrue();
      return true;
    }).when(writeAheadLog).cutAllSegmentsSmallerThan(5L);

    if (vacuum) {
      storage.runWALVacuum();
      assertThat(vacuumInProgress).isFalse();
    } else {
      storage.makeFuzzyCheckpoint();
    }

    final var order = inOrder(storage, cache, file, writeAheadLog, doubleWrite, filesLock);
    order.verify(storage).saveCheckpointFloor(0L);
    order.verify(cache).syncDataFiles();
    order.verify(filesLock).acquireReadLock();
    order.verify(doubleWrite).startCheckpoint();
    order.verify(file).synch();
    order.verify(writeAheadLog).flush();
    order.verify(doubleWrite).endCheckpoint();
    order.verify(filesLock).releaseReadLock();
    order.verify(writeAheadLog).cutAllSegmentsSmallerThan(5L);
    verify(writeAheadLog, times(1)).cutAllSegmentsSmallerThan(anyLong());
    verify(writeAheadLog, never()).cutTill(any());
  }

  /** A fuzzy cut failure stays inside its IOException reporting boundary after successful force. */
  @Test
  public void fuzzyCutFailureReportsAfterSuccessfulForce() throws Exception {
    prepareFuzzyCheckpoint();
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);
    final var failure = new IOException("injected cut failure");
    doThrow(failure).when(writeAheadLog).cutAllSegmentsSmallerThan(20L);

    assertThatThrownBy(storage::makeFuzzyCheckpoint)
        .isInstanceOf(YTIOException.class).hasCause(failure);

    final var order = inOrder(writeCache, writeAheadLog);
    order.verify(writeCache).syncDataFiles();
    order.verify(writeAheadLog).cutAllSegmentsSmallerThan(20L);
  }

  /** A vacuum cut failure is logged after force and always releases vacuum admission. */
  @Test
  public void vacuumCutFailureLogsAndClearsAdmission() throws Exception {
    prepareFuzzyCheckpoint();
    final var vacuumInProgress = new AtomicBoolean(true);
    setPrivateField(storage, "walVacuumInProgress", vacuumInProgress);
    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {3L});
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);
    doThrow(new IOException("injected cut failure"))
        .when(writeAheadLog).cutAllSegmentsSmallerThan(20L);

    try (var logs = LogRecordCollector.attachTo(storage.getClass())) {
      storage.runWALVacuum();
      assertThat(logs.messages()).anyMatch(message -> message.startsWith("SEVERE")
          && message.contains("Error during flushing of data for fuzzy checkpoint"));
    }

    final var order = inOrder(writeCache, writeAheadLog);
    order.verify(writeCache).syncDataFiles();
    order.verify(writeAheadLog).cutAllSegmentsSmallerThan(20L);
    assertThat(vacuumInProgress).isFalse();
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

  /** Shutdown flushes histogram statistics before freezing writers for the checkpoint. */
  @Test
  public void shutdownFlushesHistogramsBeforeCheckpointFreeze() throws Exception {
    prepareShutdownTeardown();
    doAnswer(invocation -> false).when(storage).isInError();
    var histogram = mock(IndexHistogramManager.class);
    var engine = mock(BTreeSingleValueIndexEngine.class);
    when(engine.getHistogramManager()).thenReturn(histogram);
    setPrivateField(storage, "indexEngines", new java.util.ArrayList<>(java.util.List.of(engine)));
    var freezeCalled = new AtomicBoolean();
    var flushedAfterFreeze = new AtomicBoolean();
    doAnswer(invocation -> {
      freezeCalled.set(true);
      return 0L;
    }).when(storage.atomicOperationsManager)
        .freezeWriteOperations(any(), org.mockito.ArgumentMatchers.isNull());
    doAnswer(invocation -> {
      flushedAfterFreeze.set(freezeCalled.get());
      return null;
    }).when(histogram).flushIfDirty();

    storage.doShutdown();

    verify(histogram).flushIfDirty();
    assertThat(freezeCalled).isTrue();
    assertThat(flushedAfterFreeze).isFalse();
  }

  /** A failed shutdown re-mark restores OPEN and histograms, then a retry closes. */
  @Test
  public void failedShutdownRemarkReleasesFreezeAndAllowsRetry() throws Exception {
    prepareShutdownTeardown();
    doAnswer(invocation -> false).when(storage).isInError();
    var histogram = mock(IndexHistogramManager.class);
    var engine = mock(BTreeSingleValueIndexEngine.class);
    when(engine.getHistogramManager()).thenReturn(histogram);
    setPrivateField(storage, "indexEngines", new java.util.ArrayList<>(java.util.List.of(engine)));
    doThrow(new IOException("re-mark unavailable")).doNothing()
        .when(storage).makeStorageDirty();

    assertThatThrownBy(storage::doShutdown)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("re-mark unavailable");
    assertThat(storage.status).isEqualTo(Storage.STATUS.OPEN);
    verify(histogram).resumeRebalancesAfterFailedStorageShutdown();
    verify(storage.atomicOperationsManager).unfreezeWriteOperations(0L);
    storage.doShutdown();
    assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
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

    try (var logs = LogRecordCollector.attachTo(storage.getClass())) {
      storage.doShutdown();
      assertThat(logs.messages()).anyMatch(message -> message.startsWith("SEVERE")
          && message.contains("failing-stats")
          && message.contains("Failed to flush histogram stats"));
    }

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

    try (var logs = LogRecordCollector.attachTo(storage.getClass())) {
      storage.doShutdown();
      assertThat(logs.messages()).anyMatch(message -> message.startsWith("SEVERE")
          && message.contains("failing-stats")
          && message.contains("Failed to close histogram stats"));
    }

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
    doNothing().doThrow(dirtyFailure).when(storage).makeStorageDirty();
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

  /**
   * YTDB-1394: a data commit pauses after state admission and recovery marking, before timestamp
   * registration. Forced close must wait for its read lock and clear only after commit apply ends.
   */
  @Test(timeout = 30_000)
  public void forcedCloseWaitsForAdmittedCommitBeforeClearingDirtyMarker() throws Exception {
    final var stateLock = prepareForcedClose();
    final var admitted = new CountDownLatch(1);
    final var resume = new CountDownLatch(1);
    final var commitApplied = new AtomicBoolean();
    final var dirty = new AtomicBoolean();
    final var transaction = mock(FrontendTransactionImpl.class);
    final var session = mock(DatabaseSessionEmbedded.class, RETURNS_DEEP_STUBS);
    final var operation = mock(AtomicOperation.class);
    when(transaction.getDatabaseSession()).thenReturn(session);
    when(session.getTxSchemaState()).thenReturn(null);
    when(transaction.getIndexOperations()).thenReturn(Map.of());
    when(transaction.getRecordOperationsInternal()).thenReturn(List.of());
    when(transaction.getAtomicOperation()).thenReturn(operation);
    when(transaction.getRecordSerializationContext())
        .thenReturn(mock(RecordSerializationContext.class));
    doAnswer(invocation -> {
      assertThat(stateLock.isReadLockedByCurrentThread()).isTrue();
      assertThat(storage.status).isEqualTo(Storage.STATUS.OPEN);
      dirty.set(true);
      return null;
    }).when(storage).ensureRecoveryIndicationBeforeTimestamp();
    doAnswer(invocation -> {
      // Model the manager's recovery-marking step before timestamp/table registration.
      storage.ensureRecoveryIndicationBeforeTimestamp();
      admitted.countDown();
      assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
      return null;
    }).when(storage.atomicOperationsManager).startToApplyOperations(operation, false, null);
    doAnswer(invocation -> {
      // Shutdown re-marks after its checkpoint and before close-time atomic work.
      assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSING);
      assertThat(stateLock.isWriteLockedByCurrentThread()).isTrue();
      assertThat(commitApplied).isTrue();
      dirty.set(true);
      return null;
    }).when(storage).makeStorageDirty();
    doAnswer(invocation -> {
      assertThat(stateLock.isReadLockedByCurrentThread()).isTrue();
      commitApplied.set(true);
      return null;
    }).when(storage.atomicOperationsManager).ensureThatComponentsUnlocked(operation);
    doAnswer(invocation -> {
      assertThat(commitApplied).as("clean marker must follow admitted commit apply").isTrue();
      assertThat(stateLock.isWriteLockedByCurrentThread()).isTrue();
      dirty.set(false);
      return null;
    }).when(storage).clearStorageDirty();
    doAnswer(invocation -> {
      // Model the final metadata clear after the re-mark and successful cache close.
      assertThat(commitApplied).as("final clean marker must follow admitted commit apply").isTrue();
      assertThat(stateLock.isWriteLockedByCurrentThread()).isTrue();
      assertThat(dirty).as("close-time work must retain the recovery indication").isTrue();
      dirty.set(false);
      return null;
    }).when(storage).postCloseSteps(false, false, 0L);

    final var workers = Executors.newFixedThreadPool(2);
    try {
      final var commit = workers.submit(() -> storage.commit(transaction, false));
      assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
      final var close = workers.submit(() -> storage.close(null, true));
      awaitForcedCloseReaderDrain(stateLock, close);
      assertThat(dirty).isTrue();
      verify(storage, never()).flushAllData();
      verify(storage, never()).clearStorageDirty();
      resume.countDown();
      assertThat(commit.get(10, TimeUnit.SECONDS)).isEmpty();
      close.get(10, TimeUnit.SECONDS);
      verify(storage.atomicOperationsManager).startToApplyOperations(operation, false, null);
      verify(storage.atomicOperationsManager).endAtomicOperation(operation, null);
      verify(storage).clearStorageDirty();
      verify(storage).ensureRecoveryIndicationBeforeTimestamp();
      verify(storage).makeStorageDirty();
      verify(storage).postCloseSteps(false, false, 0L);
      assertThat(dirty).isFalse();
      assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
      assertStateWriteLockReleased(stateLock);
    } finally {
      resume.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** A running fuzzy checkpoint keeps forced close outside shutdown until its force returns. */
  @Test(timeout = 30_000)
  public void forcedCloseWaitsForRunningFuzzyCheckpoint() throws Exception {
    assertForcedCloseWaitsForCheckpoint(false);
  }

  /** A running WAL vacuum keeps forced close outside shutdown until its force returns. */
  @Test(timeout = 30_000)
  public void forcedCloseWaitsForRunningWalVacuum() throws Exception {
    assertForcedCloseWaitsForCheckpoint(true);
  }

  /** An idle forced close completes real shutdown, clears the marker and releases write mode. */
  @Test
  public void forcedCloseCompletesNormallyAndReleasesStateWriteLock() throws Exception {
    final var stateLock = prepareForcedClose();
    doAnswer(invocation -> {
      assertThat(stateLock.isWriteLockedByCurrentThread()).isTrue();
      return null;
    }).when(storage).clearStorageDirty();

    storage.close(null, true);

    verify(storage).clearStorageDirty();
    verify(writeAheadLog).close();
    assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
    assertStateWriteLockReleased(stateLock);
  }

  /** A failed initial checkpoint releases forced-close write mode so shutdown can retry. */
  @Test
  public void forcedCloseCheckpointFailureReleasesStateWriteLockForRetry() throws Exception {
    final var stateLock = prepareForcedClose();
    doThrow(new RuntimeException("injected forced-close checkpoint failure"))
        .doCallRealMethod().when(storage).flushAllData();

    assertThatThrownBy(() -> storage.close(null, true))
        .hasMessageContaining("injected forced-close checkpoint failure");
    assertThat(storage.status).isEqualTo(Storage.STATUS.OPEN);
    verify(storage, never()).clearStorageDirty();
    assertStateWriteLockReleased(stateLock);
    storage.close(null, true);
    assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
    assertStateWriteLockReleased(stateLock);
  }

  /** Checked failures and Errors keep the existing reporting path and release forced-close mode. */
  @Test
  public void forcedCloseReleasesStateWriteLockAfterCheckedFailureAndError() throws Exception {
    final var stateLock = prepareForcedClose();
    doThrow(new IOException("injected checked shutdown failure"))
        .doThrow(new AssertionError("injected shutdown error")).when(storage).doShutdown();

    assertThatThrownBy(() -> storage.close(null, true))
        .hasCauseInstanceOf(IOException.class);
    assertStateWriteLockReleased(stateLock);
    assertThatThrownBy(() -> storage.close(null, true))
        .isInstanceOf(AssertionError.class).hasMessage("injected shutdown error");
    assertStateWriteLockReleased(stateLock);
  }

  /** Non-forced close delegates within ten seconds while another thread holds state read mode. */
  @Test
  public void nonForcedCloseDoesNotAcquireStateWriteLock() throws Exception {
    final var stateLock = prepareForcedClose();
    final var session = mock(DatabaseSessionEmbedded.class);
    doNothing().when(storage).close(session);
    final var worker = Executors.newSingleThreadExecutor();
    stateLock.readLock().lock();
    try {
      worker.submit(() -> storage.close(session, false)).get(10, TimeUnit.SECONDS);
      verify(storage).close(session);
      verify(storage, never()).doShutdown();
    } finally {
      // Lock acquisition ignores interrupts, so release the reader before stopping the worker.
      stateLock.readLock().unlock();
      worker.shutdownNow();
      assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertStateWriteLockReleased(stateLock);
  }

  private ScalableRWLock prepareForcedClose() throws Exception {
    prepareShutdownTeardown();
    final var stateLock = new ScalableRWLock();
    setPrivateField(storage, "stateLock", stateLock);
    setPrivateField(storage, "error", new AtomicReference<Throwable>());
    setPrivateField(storage, "beforeCommitApplyTestAction", new AtomicReference<>());
    storage.configuration = mock(StorageConfiguration.class, RETURNS_DEEP_STUBS);
    doAnswer(invocation -> false).when(storage).isInError();
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);
    return stateLock;
  }

  private void assertForcedCloseWaitsForCheckpoint(final boolean vacuum) throws Exception {
    final var stateLock = prepareForcedClose();
    setPrivateField(storage, "walVacuumInProgress", new AtomicBoolean(true));
    when(writeAheadLog.begin()).thenReturn(new LogSequenceNumber(1, 1));
    when(writeAheadLog.end()).thenReturn(checkpointLsn);
    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {3L});
    final var forcing = new CountDownLatch(1);
    final var resume = new CountDownLatch(1);
    doAnswer(invocation -> {
      assertThat(stateLock.isReadLockedByCurrentThread()).isTrue();
      forcing.countDown();
      assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
      return null;
    }).when(writeCache).syncDataFiles();
    final var workers = Executors.newFixedThreadPool(2);
    try {
      final var checkpoint = workers.submit(() -> {
        if (vacuum) {
          storage.runWALVacuum();
        } else {
          storage.makeFuzzyCheckpoint();
        }
      });
      assertThat(forcing.await(10, TimeUnit.SECONDS)).isTrue();
      final var close = workers.submit(() -> storage.close(null, true));
      awaitForcedCloseReaderDrain(stateLock, close);
      assertThat(storage.status).isEqualTo(Storage.STATUS.OPEN);
      verify(storage, never()).flushAllData();
      verify(storage, never()).clearStorageDirty();
      resume.countDown();
      checkpoint.get(10, TimeUnit.SECONDS);
      close.get(10, TimeUnit.SECONDS);
      verify(storage).clearStorageDirty();
      assertThat(storage.status).isEqualTo(Storage.STATUS.CLOSED);
      assertStateWriteLockReleased(stateLock);
    } finally {
      resume.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void awaitForcedCloseReaderDrain(
      final ScalableRWLock stateLock, final Future<?> close) throws Exception {
    // ScalableRWLock sets the observable write bit before draining existing readers.
    // Waiting for that bit proves close reached lock acquisition, not just worker scheduling.
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!stateLock.isWriteLocked() && !close.isDone() && System.nanoTime() < deadline) {
      Thread.yield();
    }
    if (close.isDone()) {
      close.get(10, TimeUnit.SECONDS);
      throw new AssertionError("forced close completed while an admitted reader was paused");
    }
    assertThat(stateLock.isWriteLocked()).as("forced close must reach reader drain").isTrue();
    assertThat(close.isDone()).isFalse();
  }

  private static void assertStateWriteLockReleased(final ScalableRWLock stateLock) {
    assertThat(stateLock.writeLock().tryLock()).as("forced close must release write mode").isTrue();
    stateLock.writeLock().unlock();
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
    verify(writeCache, never()).syncDataFiles();
    verify(writeAheadLog, never()).cutAllSegmentsSmallerThan(anyLong());
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
    verify(writeCache, never()).syncDataFiles();
    verify(writeAheadLog, never()).cutAllSegmentsSmallerThan(anyLong());
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
    verify(writeCache).syncDataFiles();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(6L);
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
    verify(writeCache).syncDataFiles();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(6L);
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

  /** Counts WAL cuts without changing Mockito verification state inside a checkpoint hook. */
  private long cutCalls(String name) {
    return mockingDetails(writeAheadLog).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals(name))
        .count();
  }

  /** A fuzzy sample retains protection transferred from the table to cache below its anchor. */
  @Test
  public void fuzzyCheckpointObservesOperationToCacheTransfer() throws Exception {
    prepareFuzzyCheckpoint();
    publishCacheProtectionDuringTableSample();

    storage.makeFuzzyCheckpoint();

    verify(writeCache).syncDataFiles();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(6L);
    assertTableBeforeCacheTransferSample();
  }

  /** The final vacuum sample sees a transfer even when earlier flush-loop cache reads miss it. */
  @Test
  public void vacuumObservesOperationToCacheTransferInFinalSample() throws Exception {
    setPrivateField(storage, "stateLock", new ScalableRWLock());
    setPrivateField(storage, "walVacuumInProgress", new AtomicBoolean(true));
    storage.status = Storage.STATUS.OPEN;
    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {3L});
    publishCacheProtectionDuringTableSample();

    storage.runWALVacuum();

    verify(writeCache).syncDataFiles();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(6L);
    assertTableBeforeCacheTransferSample();
  }

  private void publishCacheProtectionDuringTableSample() {
    final var cacheRequirementPublished = new AtomicBoolean();
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenAnswer(invocation -> {
      // The operation publishes its pages before it leaves the table. The sample sees no entry.
      cacheRequirementPublished.set(true);
      return -1L;
    });
    when(writeCache.getMinimalNotFlushedSegment())
        .thenAnswer(invocation -> cacheRequirementPublished.get() ? 6L : null);
  }

  private void assertTableBeforeCacheTransferSample() {
    final var ownershipOrder = inOrder(atomicOperationsTable, writeCache);
    ownershipOrder.verify(atomicOperationsTable).getSegmentEarliestNotPersistedOperation();
    ownershipOrder.verify(writeCache).getMinimalNotFlushedSegment();
  }

  /** A cache boundary below the fuzzy anchor still limits the cut. */
  @Test
  public void fuzzyCheckpointKeepsEarlierCacheProtection() throws Exception {
    prepareFuzzyCheckpoint();
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(7L);

    storage.makeFuzzyCheckpoint();

    verify(writeCache).syncDataFiles();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(7L);
  }

  /** Operation protection below both the fuzzy anchor and cache boundary still wins. */
  @Test
  public void fuzzyCheckpointKeepsEarlierOperationProtection() throws Exception {
    prepareFuzzyCheckpoint();
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(5L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(9L);

    storage.makeFuzzyCheckpoint();

    verify(writeCache).syncDataFiles();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(5L);
  }

  /** With no cache protection, a fuzzy checkpoint caps its WAL end at the active anchor. */
  @Test
  public void fuzzyCheckpointCapsWalEndWithoutCacheProtection() throws Exception {
    prepareFuzzyCheckpoint();
    when(writeAheadLog.activeSegment()).thenReturn(10L);
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);

    storage.makeFuzzyCheckpoint();

    verify(writeCache).syncDataFiles();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(10L);
  }

  /** An anchor at WAL begin preserves the fuzzy no-progress exit even with a later cache. */
  @Test
  public void fuzzyCheckpointDoesNotSyncWhenAnchorCannotAdvance() throws Exception {
    prepareFuzzyCheckpoint();
    when(writeAheadLog.activeSegment()).thenReturn(1L);
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(9L);

    storage.makeFuzzyCheckpoint();

    verify(writeCache, never()).syncDataFiles();
    verify(writeAheadLog, never()).cutAllSegmentsSmallerThan(anyLong());
  }

  /** An operation below the final vacuum anchor still retains its earlier WAL segment. */
  @Test
  public void vacuumKeepsEarlierOperationProtection() throws Exception {
    setPrivateField(storage, "stateLock", new ScalableRWLock());
    setPrivateField(storage, "walVacuumInProgress", new AtomicBoolean(true));
    storage.status = Storage.STATUS.OPEN;
    when(writeAheadLog.nonActiveSegments()).thenReturn(new long[] {3L});
    when(writeAheadLog.activeSegment()).thenReturn(10L);
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(5L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);

    storage.runWALVacuum();

    verify(writeCache).syncDataFiles();
    verify(writeAheadLog).cutAllSegmentsSmallerThan(5L);
  }

  /** Rotation after logging cannot raise the unprotected full-flush cut above its record. */
  @Test
  public void unprotectedFullFlushKeepsEarlierLoggedBoundaryAfterRotation() throws Exception {
    when(writeAheadLog.activeSegment()).thenReturn(21L);
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(null);

    storage.flushAllData();

    var order = inOrder(writeAheadLog, writeCache, atomicOperationsTable);
    order.verify(writeAheadLog).log(any());
    order.verify(writeCache).flush();
    order.verify(writeAheadLog).activeSegment();
    order.verify(atomicOperationsTable).getSegmentEarliestOperationInProgress();
    order.verify(writeAheadLog).flush();
    order.verify(atomicOperationsTable).getSegmentEarliestNotPersistedOperation();
    order.verify(writeCache).getMinimalNotFlushedSegment();
    order.verify(writeAheadLog).cutTill(checkpointLsn);
    verify(writeAheadLog, never()).cutAllSegmentsSmallerThan(21L);
    verify(storage).clearStorageDirty();
  }

  /** Memory WAL accepts the zero anchor in protected and unprotected full flushes. */
  @Test
  public void memoryWalFullFlushAcceptsZeroAnchorAndNoOpCuts() throws Exception {
    var memoryWal = org.mockito.Mockito.spy(new MemoryWriteAheadLog());
    storage.writeAheadLog = memoryWal;
    setPrivateField(storage, "stateLock", new ScalableRWLock());
    setPrivateField(storage, "walVacuumInProgress", new AtomicBoolean(true));
    when(atomicOperationsTable.getSegmentEarliestNotPersistedOperation()).thenReturn(-1L);
    when(writeCache.getMinimalNotFlushedSegment()).thenReturn(7L, (Long) null);

    storage.flushAllData();
    storage.flushAllData();

    verify(memoryWal).cutAllSegmentsSmallerThan(0L);
    verify(memoryWal).cutTill(new LogSequenceNumber(0, 2));
    verify(storage, times(1)).clearStorageDirty();
    assertThat(memoryWal.activeSegment()).isZero();
    assertThat(memoryWal.preflightCut(7).removesSegments()).isFalse();
    storage.runWALVacuum();
  }

  private void prepareFuzzyCheckpoint() throws Exception {
    setPrivateField(storage, "stateLock", new ScalableRWLock());
    setPrivateField(storage, "indexEngines", new java.util.ArrayList<>());
    storage.status = Storage.STATUS.OPEN;
    when(writeAheadLog.begin()).thenReturn(new LogSequenceNumber(1, 1));
    when(writeAheadLog.end()).thenReturn(checkpointLsn);
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
