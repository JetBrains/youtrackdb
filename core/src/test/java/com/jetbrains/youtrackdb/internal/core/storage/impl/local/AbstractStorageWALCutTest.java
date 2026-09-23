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
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.lang.reflect.Field;
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

  /** Sets a constructor-initialized field on a Mockito real-method mock. */
  private static void setPrivateField(
      final AbstractStorage target, final String name, final Object value) throws Exception {
    final Field field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
