package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.OperationsFreezer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Tests freezer accounting when logical commit sequence allocation fails. */
public class AtomicOperationsManagerSequenceExhaustionTest {

  /** Sequence exhaustion releases the freezer admission without changing rollback semantics. */
  @Test
  public void sequenceExhaustionReleasesFreezerAdmission() throws Exception {
    final var idGen = mock(AtomicOperationIdGen.class);
    when(idGen.nextId())
        .thenThrow(new IllegalStateException("Logical operation identifier space is exhausted"));
    final var storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(idGen);
    final var manager =
        new AtomicOperationsManager(storage, mock(AtomicOperationsTable.class));
    final var freezer = mock(OperationsFreezer.class);
    final Field freezerField =
        AtomicOperationsManager.class.getDeclaredField("writeOperationsFreezer");
    freezerField.setAccessible(true);
    freezerField.set(manager, freezer);

    assertThatThrownBy(() -> manager.startToApplyOperations(mock(AtomicOperation.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exhausted");

    verify(freezer).startOperation(false, null);
    verify(freezer).endOperation();
  }

  /**
   * The calculating wrapper keeps the allocation failure as its cause.
   * It does not run full cleanup after the failed start releases freezer admission.
   */
  @Test
  public void calculatingWrapperDoesNotReleaseAdmissionTwiceOnExhaustion() throws Exception {
    assertWrapperBalancesExhaustion(false);
  }

  /**
   * The executing wrapper keeps the allocation failure as its cause.
   * It does not run full cleanup after the failed start releases freezer admission.
   */
  @Test
  public void executingWrapperDoesNotReleaseAdmissionTwiceOnExhaustion() throws Exception {
    assertWrapperBalancesExhaustion(true);
  }

  /** A wrapper-style table failure completes the real protected error-state transition. */
  @Test
  public void tableInvariantFailureMovesStorageToErrorState() throws Exception {
    final var failure = new IllegalStateException("Broken atomic operations table");
    final var idGen = mock(AtomicOperationIdGen.class);
    final var storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    final var storageError = new AtomicReference<Throwable>();
    final Field errorField = AbstractStorage.class.getDeclaredField("error");
    errorField.setAccessible(true);
    errorField.set(storage, storageError);
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(idGen);
    final var table = mock(AtomicOperationsTable.class);
    doThrow(failure).when(table).startOperation(0, 0);
    final var manager = new AtomicOperationsManager(storage, table);

    assertThatThrownBy(() -> manager.startToApplyOperations(mock(AtomicOperation.class)))
        .isSameAs(failure);

    assertThat(storageError.get()).isSameAs(failure);
    verify(table, never()).rollbackOperation(0);
  }

  /** A direct commit start failure keeps the pre-track runtime error-state behavior. */
  @Test
  public void directCommitStartFailureDoesNotMoveStorageToErrorState() {
    final var failure = new IllegalStateException("Broken atomic operations table");
    final var idGen = mock(AtomicOperationIdGen.class);
    final var storage = mock(AbstractStorage.class);
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(idGen);
    final var table = mock(AtomicOperationsTable.class);
    doThrow(failure).when(table).startOperation(0, 0);
    final var manager = new AtomicOperationsManager(storage, table);

    assertThatThrownBy(
        () -> manager.startToApplyOperations(mock(AtomicOperation.class), false, null))
        .isSameAs(failure);

    verify(storage, never()).moveToErrorStateIfNeeded(failure);
  }

  /** An operation start failure rolls back its completed table registration. */
  @Test
  public void operationStartFailureRollsBackTableRegistration() {
    final var failure = new IllegalStateException("Operation apply start failed");
    final var idGen = mock(AtomicOperationIdGen.class);
    final var storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(idGen);
    final var table = mock(AtomicOperationsTable.class);
    final var operation = mock(AtomicOperation.class);
    doThrow(failure).when(operation).startToApplyOperations(0);
    final var manager = new AtomicOperationsManager(storage, table);

    assertThatThrownBy(() -> manager.startToApplyOperations(operation)).isSameAs(failure);

    verify(table).startOperation(0, 0);
    verify(table).rollbackOperation(0);
  }

  /** Identical start and rollback failures preserve the start failure and release the freezer. */
  @Test
  public void identicalRollbackFailureDoesNotSkipFreezerRelease() throws Exception {
    final var failure = new IllegalStateException("Shared start and rollback failure");
    final var idGen = mock(AtomicOperationIdGen.class);
    final var storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(idGen);
    final var table = mock(AtomicOperationsTable.class);
    final var operation = mock(AtomicOperation.class);
    doThrow(failure).when(operation).startToApplyOperations(0);
    doThrow(failure).when(table).rollbackOperation(0);
    final var manager = new AtomicOperationsManager(storage, table);
    final var freezer = mock(OperationsFreezer.class);
    final Field freezerField =
        AtomicOperationsManager.class.getDeclaredField("writeOperationsFreezer");
    freezerField.setAccessible(true);
    freezerField.set(manager, freezer);

    assertThatThrownBy(() -> manager.startToApplyOperations(operation)).isSameAs(failure);

    verify(table).rollbackOperation(0);
    verify(freezer).endOperation();
  }

  private static void assertWrapperBalancesExhaustion(final boolean execute) throws Exception {
    final var idGen = mock(AtomicOperationIdGen.class);
    final var exhaustion =
        new IllegalStateException("Logical operation identifier space is exhausted");
    when(idGen.nextId()).thenThrow(exhaustion);
    final var storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);
    when(storage.getName()).thenReturn("sequence-exhaustion-test");
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(idGen);
    final var manager =
        spy(new AtomicOperationsManager(storage, mock(AtomicOperationsTable.class)));
    doReturn(mock(AtomicOperation.class)).when(manager).startAtomicOperation();
    final var freezer = mock(OperationsFreezer.class);
    final Field freezerField =
        AtomicOperationsManager.class.getDeclaredField("writeOperationsFreezer");
    freezerField.setAccessible(true);
    freezerField.set(manager, freezer);

    if (execute) {
      assertThatThrownBy(() -> manager.executeInsideAtomicOperation(operation -> {
      }))
          .isInstanceOf(StorageException.class)
          .hasRootCause(exhaustion);
    } else {
      assertThatThrownBy(() -> manager.calculateInsideAtomicOperation(operation -> null))
          .isInstanceOf(StorageException.class)
          .hasRootCause(exhaustion);
    }

    verify(freezer).startOperation(false, null);
    verify(freezer).endOperation();
    verify(manager, never()).endAtomicOperation(any(), any());
  }
}
