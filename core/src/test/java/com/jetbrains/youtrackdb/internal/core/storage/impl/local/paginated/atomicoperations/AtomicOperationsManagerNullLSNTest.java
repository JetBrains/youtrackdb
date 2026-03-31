package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.OperationsFreezer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;

/**
 * TC4 — Tests that {@code AtomicOperationsManager.endAtomicOperation()} correctly handles null
 * LSN from {@code commitChanges()} (pure non-durable operation). When LSN is null, the operation
 * must be immediately persisted via {@code atomicOperationsTable.persistOperation()} instead of
 * being deferred via {@code writeAheadLog.addEventAt(lsn, ...)}.
 */
public class AtomicOperationsManagerNullLSNTest {

  private WriteAheadLog wal;
  private AtomicOperationsTable atomicOperationsTable;
  private AtomicOperationsManager manager;

  @Before
  public void setUp() {
    wal = mock(WriteAheadLog.class);
    var readCache = mock(ReadCache.class);
    var writeCache = mock(WriteCache.class);
    var idGen = mock(AtomicOperationIdGen.class);

    var storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    when(storage.getWALInstance()).thenReturn(wal);
    when(storage.getReadCache()).thenReturn(readCache);
    when(storage.getWriteCache()).thenReturn(writeCache);
    when(storage.getIdGen()).thenReturn(idGen);

    atomicOperationsTable = mock(AtomicOperationsTable.class);
    manager = new AtomicOperationsManager(storage, atomicOperationsTable);

    // Replace the internal OperationsFreezer with a mock to avoid the
    // startOperation/endOperation depth check — we're testing endAtomicOperation
    // directly without going through the full startAtomicOperation lifecycle.
    try {
      Field freezerField =
          AtomicOperationsManager.class.getDeclaredField("writeOperationsFreezer");
      freezerField.setAccessible(true);
      freezerField.set(manager, mock(OperationsFreezer.class));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Pure non-durable operation: commitChanges() returns null LSN. The operation must be
   * immediately persisted (not deferred via WAL event). This is the null-LSN path at
   * {@code AtomicOperationsManager.endAtomicOperation()} lines 247-249.
   */
  @Test
  public void nullLSNFromCommitChangesTriggersImmediatePersist()
      throws IOException {
    var operation = mock(AtomicOperation.class);
    when(operation.getCommitTs()).thenReturn(42L);
    // Pure non-durable operation returns null LSN
    when(operation.commitChanges(anyLong(), any())).thenReturn(null);

    manager.endAtomicOperation(operation, null);

    // commitOperation must be called (not rollback)
    verify(atomicOperationsTable).commitOperation(42L);
    // persistOperation called immediately — no WAL event deferral
    verify(atomicOperationsTable).persistOperation(42L);
    // addEventAt must NOT be called — no LSN to attach the callback to
    verify(wal, never()).addEventAt(any(), any());
  }

  /**
   * Durable operation: commitChanges() returns a valid LSN. The operation must be deferred
   * via writeAheadLog.addEventAt(lsn, callback). This is the normal path that should NOT
   * call persistOperation immediately.
   */
  @Test
  public void nonNullLSNFromCommitChangesDefersPersistViaWAL()
      throws IOException {
    var operation = mock(AtomicOperation.class);
    when(operation.getCommitTs()).thenReturn(42L);
    var lsn = new LogSequenceNumber(0, 10);
    when(operation.commitChanges(anyLong(), any())).thenReturn(lsn);

    manager.endAtomicOperation(operation, null);

    // commitOperation must be called
    verify(atomicOperationsTable).commitOperation(42L);
    // Persistence deferred to WAL flush via addEventAt
    verify(wal).addEventAt(any(), any());
    // persistOperation must NOT be called immediately
    verify(atomicOperationsTable, never()).persistOperation(42L);
  }
}
