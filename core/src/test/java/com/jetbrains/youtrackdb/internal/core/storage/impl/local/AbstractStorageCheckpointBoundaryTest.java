package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises late registration and real segment deletion at each checkpoint boundary. */
public class AbstractStorageCheckpointBoundaryTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private AbstractStorage storage;
  private WriteCache cache;
  private AtomicOperationsTable table;

  @Before
  public void setUp() throws Exception {
    storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    cache = mock(WriteCache.class);
    table = spy(new AtomicOperationsTable(10_000, 0));
    storage.writeCache = cache;
    storage.atomicOperationsTable = table;
    storage.status = Storage.STATUS.OPEN;
    setField("stateLock", new ScalableRWLock());
    setField("walVacuumInProgress", new AtomicBoolean(true));
    setField("indexEngines", new ArrayList<>());
    // CALLS_REAL_METHODS skips the generator and floor-hook constructor initializers.
    setField("idGen", mock(AtomicOperationIdGen.class));
    for (var field : new String[] {"checkpointFloorTestAction",
        "beforeMaintenanceFloorReadTestAction", "beforeMaintenanceFloorTestAction",
        "afterMaintenanceFloorTestAction"}) {
      setField(field, new AtomicReference<>());
    }
  }

  /** A late operation in S survives a fuzzy cut despite a non-null cache boundary in S+1. */
  @Test
  public void fuzzyCheckpointRetainsLateOperationSegmentWithLaterCache() throws Exception {
    assertLateOperationSurvives(Checkpoint.FUZZY, false);
  }

  /** A late operation in S survives the final vacuum sample with cache protection in S+1. */
  @Test
  public void vacuumRetainsLateOperationSegmentWithLaterCache() throws Exception {
    assertLateOperationSurvives(Checkpoint.VACUUM, false);
  }

  /** A null final cache sample cannot move the vacuum boundary past the earlier anchor. */
  @Test
  public void vacuumRetainsLateOperationSegmentWithoutCacheProtection() throws Exception {
    assertLateOperationSurvives(Checkpoint.VACUUM, true);
  }

  /** Registration after the full-flush in-progress check survives a protected cut in S+1. */
  @Test
  public void fullFlushRetainsLateOperationRegisteredAfterInProgressCheck() throws Exception {
    assertLateOperationSurvives(Checkpoint.FULL, false);
  }

  /** A fuzzy cut retains records in S+1 when their delayed table entry still carries S. */
  @Test
  public void fuzzyCheckpointRetainsRecordsFromDelayedOlderSegmentRegistration() throws Exception {
    assertLateOperationSurvives(Checkpoint.FUZZY, false, true);
  }

  /** Vacuum retains S+1 records after sampling an unpublished entry that was assigned S. */
  @Test
  public void vacuumRetainsRecordsFromDelayedOlderSegmentRegistration() throws Exception {
    assertLateOperationSurvives(Checkpoint.VACUUM, false, true);
  }

  /** Full flush samples before publication of an S entry whose later records land in S+1. */
  @Test
  public void fullFlushRetainsRecordsFromDelayedOlderSegmentRegistration() throws Exception {
    assertLateOperationSurvives(Checkpoint.FULL, false, true);
  }

  private void assertLateOperationSurvives(Checkpoint checkpoint, boolean nullCache)
      throws Exception {
    assertLateOperationSurvives(checkpoint, nullCache, false);
  }

  private void assertLateOperationSurvives(
      Checkpoint checkpoint, boolean nullCache, boolean delayedRegistration) throws Exception {
    final var directory = temporaryFolder.newFolder("checkpoint-wal").toPath();
    final var wal = createWal(directory);
    storage.writeAheadLog = wal;
    final var registered = new AtomicBoolean();
    final var operation = mock(AtomicOperation.class);
    final var registrationStorage = mock(AbstractStorage.class);
    final var idGen = mock(AtomicOperationIdGen.class);
    when(idGen.nextId()).thenReturn(1L);
    when(registrationStorage.getWALInstance()).thenReturn(wal);
    when(registrationStorage.getWriteCache()).thenReturn(cache);
    when(registrationStorage.getIdGen()).thenReturn(idGen);
    when(operation.getCommitTs()).thenReturn(1L);
    when(operation.isRollbackInProgress()).thenReturn(true);
    final var manager = new AtomicOperationsManager(registrationStorage, table);
    final var executor = Executors.newSingleThreadExecutor();
    final var segmentRead = new CountDownLatch(1);
    final var allowRegistration = new CountDownLatch(1);
    try {
      // Leave segment 1 behind so a correct cut can make observable progress.
      wal.appendNewSegment();
      wal.log(new EmptyWALRecord());
      wal.flush();
      // Full flush rotates once before sampling. Immediate registration sees that new segment.
      final var lateOperationSegment = wal.activeSegment()
          + (checkpoint == Checkpoint.FULL && !delayedRegistration ? 1 : 0);
      final var recordSegment = lateOperationSegment + (delayedRegistration ? 1 : 0);
      final var startLsn = new LogSequenceNumber[1];
      final var endLsn = new LogSequenceNumber[1];
      doAnswer(invocation -> {
        assertThat(table.getSegmentEarliestOperationInProgress())
            .isEqualTo(lateOperationSegment);
        registered.set(true);
        return null;
      }).when(operation).startToApplyOperations(1L);

      final Future<?> registration;
      if (delayedRegistration) {
        doAnswer(invocation -> {
          assertThat(invocation.getArgument(1, Long.class)).isEqualTo(lateOperationSegment);
          // Pause after the manager reads S, but before the real table entry is published.
          segmentRead.countDown();
          assertThat(allowRegistration.await(10, TimeUnit.SECONDS)).isTrue();
          return invocation.callRealMethod();
        }).when(table).startOperation(1L, lateOperationSegment);
        registration = executor.submit(() -> manager.startToApplyOperations(operation));
        assertThat(segmentRead.await(10, TimeUnit.SECONDS)).isTrue();
        // Fuzzy and vacuum need an explicit rotation. Full flush rotates before its anchor.
        if (checkpoint != Checkpoint.FULL) {
          wal.appendNewSegment();
          wal.log(new EmptyWALRecord());
          wal.flush();
        }
      } else {
        registration = null;
      }

      doAnswer(invocation -> {
        // Capture the real table result first. The later registration is absent from it.
        final var sampledSegment = (long) invocation.callRealMethod();
        assertThat(sampledSegment).isEqualTo(-1L);
        assertThat(wal.activeSegment()).isEqualTo(recordSegment);
        if (delayedRegistration) {
          allowRegistration.countDown();
          registration.get(10, TimeUnit.SECONDS);
        } else {
          manager.startToApplyOperations(operation);
        }
        assertThat(registered).isTrue();
        startLsn[0] = wal.log(new AtomicUnitStartRecord(true, 1L));
        endLsn[0] = wal.log(new AtomicUnitEndRecord(1L, false, Map.of()));
        // Leave the operation registered without applying pages to the cache.
        wal.flush();
        wal.appendNewSegment();
        wal.log(new EmptyWALRecord());
        wal.flush();
        return sampledSegment;
      }).when(table).getSegmentEarliestNotPersistedOperation();

      when(cache.getMinimalNotFlushedSegment()).thenAnswer(invocation -> {
        if (!registered.get() || nullCache) {
          return null;
        }
        assertThat(wal.activeSegment()).isEqualTo(recordSegment + 1);
        return wal.activeSegment();
      });
      doAnswer(invocation -> {
        wal.flush();
        wal.cutAllSegmentsSmallerThan(invocation.getArgument(0, Long.class));
        return null;
      }).when(cache).syncDataFiles(anyLong());

      switch (checkpoint) {
        case FUZZY -> storage.makeFuzzyCheckpoint();
        case VACUUM -> storage.runWALVacuum();
        case FULL -> storage.flushAllData();
      }

      assertThat(registered).isTrue();
      verify(table).startOperation(1L, lateOperationSegment);
      assertThat(startLsn[0].getSegment()).isEqualTo(recordSegment);
      assertThat(endLsn[0].getSegment()).isEqualTo(recordSegment);
      assertThat(wal.begin().getSegment()).isEqualTo(recordSegment);
      final var retainedRecords = wal.read(startLsn[0], 10);
      assertThat(retainedRecords).anyMatch(record -> record.getLsn().equals(startLsn[0])
          && record instanceof AtomicUnitStartRecord);
      assertThat(retainedRecords).anyMatch(record -> record.getLsn().equals(endLsn[0])
          && record instanceof AtomicUnitEndRecord);
      assertThat(wal.nonActiveSegments()).contains(recordSegment).doesNotContain(1L);
      if (delayedRegistration) {
        // S held no operation records, so the checkpoint can safely delete it too.
        assertThat(wal.nonActiveSegments()).doesNotContain(lateOperationSegment);
      }
      final var order = inOrder(table, cache);
      if (checkpoint == Checkpoint.FULL) {
        order.verify(table).getSegmentEarliestOperationInProgress();
      }
      order.verify(table).getSegmentEarliestNotPersistedOperation();
      order.verify(cache).getMinimalNotFlushedSegment();
      if (checkpoint == Checkpoint.FULL) {
        verify(storage, never()).clearStorageDirty();
      } else {
        verify(cache).syncDataFiles(recordSegment);
      }
    } finally {
      try {
        allowRegistration.countDown();
        if (delayedRegistration) {
          // Freezer admission is thread-local. Balance it on the registration worker.
          executor.submit(() -> {
            if (registered.get()) {
              manager.endAtomicOperation(operation, new IOException("fixture rollback"));
            }
            return null;
          }).get(10, TimeUnit.SECONDS);
        } else if (registered.get()) {
          manager.endAtomicOperation(operation, new IOException("fixture rollback"));
        }
      } finally {
        try {
          executor.shutdownNow();
          assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          wal.close();
        }
      }
    }
  }

  private static CASDiskWriteAheadLog createWal(Path directory) throws IOException {
    return new CASDiskWriteAheadLog("boundary", directory, directory,
        ContextConfiguration.WAL_DEFAULT_NAME, 100, 64, null, null,
        Integer.MAX_VALUE, Integer.MAX_VALUE, 20, true, Locale.US, -1,
        1000, false, false, false, 10);
  }

  private void setField(String name, Object value) throws Exception {
    final var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(storage, value);
  }

  private enum Checkpoint {
    FUZZY, VACUUM, FULL
  }
}
