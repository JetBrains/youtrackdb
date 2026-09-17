package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.FreezeKind;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;

/** Verifies staged cleanup when an atomic operation fails before its body can start. */
public class AtomicOperationsManagerStartupFailureTest {

  private static final String STORAGE_NAME = "startupFailureStorage";

  private AbstractStorage storage;
  private WriteAheadLog wal;
  private AtomicOperationIdGen idGen;
  private AtomicOperationsTable table;
  private AtomicOperationsManager manager;
  private AtomicOperation operation;

  @Before
  public void setUp() {
    storage = mock(AbstractStorage.class);
    wal = mock(WriteAheadLog.class);
    idGen = mock(AtomicOperationIdGen.class);
    table = mock(AtomicOperationsTable.class);
    operation = mock(AtomicOperation.class);

    when(storage.getName()).thenReturn(STORAGE_NAME);
    when(storage.getWALInstance()).thenReturn(wal);
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(idGen);
    when(idGen.nextId()).thenReturn(42L);
    when(wal.activeSegment()).thenReturn(7L);

    manager = spy(new AtomicOperationsManager(storage, table));
    doReturn(operation).when(manager).startAtomicOperation();
  }

  /** A throw-mode freeze rejection remains the primary error and never ends unentered admission. */
  @Test(timeout = 2_000)
  public void freezeRejectionPreservesOriginalFailureAndSkipsBody() throws Exception {
    var rejection = new ModificationOperationProhibitedException(
        STORAGE_NAME, "Modification requests are prohibited");
    var bodyCalled = new AtomicBoolean();
    var freezeId = manager.freezeWriteOperations(FreezeKind.OPERATOR, () -> rejection);
    try {
      try {
        manager.executeInsideAtomicOperation(op -> bodyCalled.set(true));
        fail("Expected the operator freeze rejection");
      } catch (ModificationOperationProhibitedException thrown) {
        assertSame("the startup rejection must not be masked", rejection, thrown);
      }
    } finally {
      manager.unfreezeWriteOperations(freezeId);
    }

    assertTrue("the operation body must not run after rejected startup", !bodyCalled.get());
    verify(table, never()).startOperation(anyLong(), anyLong());
    verify(operation).deactivate();
  }

  /** The calculating wrapper also preserves a rejected startup and skips its function. */
  @Test(timeout = 2_000)
  public void calculateWrapperPreservesFreezeRejectionAndSkipsFunction() throws Exception {
    var rejection = new ModificationOperationProhibitedException(
        STORAGE_NAME, "Modification requests are prohibited");
    var functionCalled = new AtomicBoolean();
    var freezeId = manager.freezeWriteOperations(FreezeKind.OPERATOR, () -> rejection);
    try {
      try {
        manager.calculateInsideAtomicOperation(op -> {
          functionCalled.set(true);
          return 1;
        });
        fail("Expected the operator freeze rejection");
      } catch (ModificationOperationProhibitedException thrown) {
        assertSame("the calculating wrapper must retain the startup rejection", rejection, thrown);
      }
    } finally {
      manager.unfreezeWriteOperations(freezeId);
    }

    assertTrue("the function must not run after rejected startup", !functionCalled.get());
    verify(table, never()).startOperation(anyLong(), anyLong());
    verify(operation).deactivate();
  }

  /** Failure after freezer admission releases admission so a later freeze can complete. */
  @Test(timeout = 2_000)
  public void failureAfterFreezerAdmissionLeavesFreezerBalanced() throws Exception {
    var startupFailure = new IllegalStateException("active segment unavailable");
    when(wal.activeSegment()).thenThrow(startupFailure);

    assertStartupFailure(startupFailure);

    var freezeId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    manager.unfreezeWriteOperations(freezeId);
    verify(table, never()).startOperation(anyLong(), anyLong());
    verify(operation).deactivate();
  }

  /** A table-start failure attempts rollback and still releases freezer admission. */
  @Test(timeout = 2_000)
  public void tableStartFailureAttemptsRollbackAndPreservesFailure() throws Exception {
    var startupFailure = new IllegalStateException("table registration failed");
    org.mockito.Mockito.doThrow(startupFailure).when(table).startOperation(42L, 7L);

    assertStartupFailure(startupFailure);

    verify(table).rollbackOperation(42L);
    verify(operation).deactivate();
    var freezeId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    manager.unfreezeWriteOperations(freezeId);
  }

  /** A registered table entry rolls back when operation activation fails afterward. */
  @Test(timeout = 2_000)
  public void failureAfterTableRegistrationRollsBackRegisteredEntry() throws Exception {
    var startupFailure = new IllegalStateException("operation activation failed");
    org.mockito.Mockito.doThrow(startupFailure)
        .when(operation).startToApplyOperations(42L);

    assertStartupFailure(startupFailure);

    verify(table).startOperation(42L, 7L);
    verify(table).rollbackOperation(42L);
    verify(operation).deactivate();
    var freezeId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    manager.unfreezeWriteOperations(freezeId);
  }

  /** Rollback failure is suppressed without replacing the activation failure. */
  @Test(timeout = 2_000)
  public void rollbackFailureIsSuppressedOnActivationFailure() throws Exception {
    var startupFailure = new IllegalStateException("operation activation failed");
    var rollbackFailure = new IllegalStateException("table rollback failed");
    org.mockito.Mockito.doThrow(startupFailure)
        .when(operation).startToApplyOperations(42L);
    org.mockito.Mockito.doThrow(rollbackFailure).when(table).rollbackOperation(42L);

    assertStartupFailure(startupFailure);

    assertTrue("rollback failure must be attached to the primary failure",
        java.util.Arrays.asList(startupFailure.getSuppressed()).contains(rollbackFailure));
    verify(operation).deactivate();
    var freezeId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    manager.unfreezeWriteOperations(freezeId);
  }

  /** Cleanup failure is suppressed without replacing the primary startup failure. */
  @Test(timeout = 2_000)
  public void cleanupFailureIsSuppressedOnStartupFailure() throws Exception {
    var startupFailure = new IllegalStateException("active segment unavailable");
    var cleanupFailure = new IllegalStateException("deactivation failed");
    when(wal.activeSegment()).thenThrow(startupFailure);
    org.mockito.Mockito.doThrow(cleanupFailure).when(operation).deactivate();

    assertStartupFailure(startupFailure);

    assertTrue("cleanup failure must be attached to the primary failure",
        java.util.Arrays.asList(startupFailure.getSuppressed()).contains(cleanupFailure));
    var freezeId = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
    manager.unfreezeWriteOperations(freezeId);
  }

  private void assertStartupFailure(RuntimeException expected) throws Exception {
    try {
      manager.executeInsideAtomicOperation(op -> fail("operation body must not run"));
      fail("Expected atomic startup failure");
    } catch (StorageException thrown) {
      assertSame("the wrapper must retain the startup failure as its cause",
          expected, thrown.getCause());
    }
  }
}
