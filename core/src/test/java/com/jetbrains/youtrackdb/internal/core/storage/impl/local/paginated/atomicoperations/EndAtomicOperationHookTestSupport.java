package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.OperationsFreezer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.lang.reflect.Field;
import java.util.Collections;

/**
 * Shared Mockito fixtures for tests that drive
 * {@link AtomicOperationsManager#endAtomicOperation(AtomicOperation, Throwable)}
 * directly without spinning up the full {@code startToApplyOperations} stack.
 * Used by the persist-hook test and the hook-ordering test that follows it.
 *
 * <p>Package-private on purpose: it depends on the package-private
 * {@code writeOperationsFreezer} field and is meaningful only to tests in the
 * {@code atomicoperations} package.
 */
final class EndAtomicOperationHookTestSupport {

  /** Default commit timestamp used by {@link #mockOperation}. */
  static final long DEFAULT_COMMIT_TS = 42L;

  private EndAtomicOperationHookTestSupport() {
    // utility — not instantiable
  }

  /**
   * Pre-increments the freezer's per-thread operation depth so that
   * {@code endAtomicOperation}'s outer-finally {@code endOperation()} call
   * sees a positive depth. The freezer is private to the manager, so a
   * reflective bump is the smallest change that lets us drive the lifecycle
   * method directly without spinning up the full {@code startToApplyOperations}
   * stack (which needs a populated {@code idGen}, WAL active segment, and
   * atomic operations table state).
   *
   * <p>If the manager's internal field name or freezer type changes, the cast
   * or {@code startOperation()} call will fail with a typed
   * {@link AssertionError} carrying the original cause, so the contract drift
   * shows up immediately instead of masking as a confusing
   * {@code NullPointerException} on a later call.
   */
  static void primeFreezer(AtomicOperationsManager manager) {
    Field field;
    try {
      field = AtomicOperationsManager.class.getDeclaredField("writeOperationsFreezer");
      field.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(
          "Could not access AtomicOperationsManager.writeOperationsFreezer", e);
    }
    OperationsFreezer freezer;
    try {
      Object raw = field.get(manager);
      assert raw != null : "AtomicOperationsManager.writeOperationsFreezer was null";
      freezer = (OperationsFreezer) raw;
      freezer.startOperation();
    } catch (ClassCastException | NullPointerException | IllegalStateException
        | ReflectiveOperationException e) {
      throw new AssertionError(
          "primeFreezer no longer matches AtomicOperationsManager.writeOperationsFreezer"
              + " contract: " + e.getMessage(),
          e);
    }
  }

  /**
   * Builds a mock {@link AtomicOperation} pre-configured for the
   * {@code endAtomicOperation} call path: starts in not-rollback state, has a
   * stable commit timestamp ({@link #DEFAULT_COMMIT_TS}), returns an empty
   * locked-components iterable so {@code releaseLocks} is a no-op, and routes
   * {@code rollbackInProgress()} through to flip the
   * {@code isRollbackInProgress()} return value (so the persist hook's
   * rollback signal is observable downstream).
   *
   * <p>The {@code getHistogramDeltas() -> null} stub is a focus-narrowing
   * default for the index-count branch tests this helper serves: it
   * short-circuits the lifecycle method's histogram branch at its null-holder
   * gate so verifications stay focused on the index-count side. Tests that
   * exercise the histogram branch supply their own operation mock with a
   * non-null {@code HistogramDeltaHolder}.
   */
  static AtomicOperation mockOperation(IndexCountDeltaHolder holder) {
    var operation = mock(AtomicOperation.class);
    when(operation.getCommitTs()).thenReturn(DEFAULT_COMMIT_TS);
    when(operation.lockedComponents()).thenReturn(Collections.emptyList());
    when(operation.lockedObjects()).thenReturn(Collections.emptySet());
    when(operation.getIndexCountDeltas()).thenReturn(holder);
    // The histogram-delta getter is stubbed to null on the baseline mock so
    // the lifecycle method's histogram branch short-circuits at its null-
    // holder gate. Tests that exercise the histogram branch supply their own
    // operation mock with a non-null HistogramDeltaHolder; the index-count
    // branch tests (which this helper serves) want the histogram path to be
    // a no-op so the verifications stay focused on the index-count side.
    when(operation.getHistogramDeltas()).thenReturn(null);
    // rollbackInProgress() is a state-flip — track it so the hook's call
    // makes isRollbackInProgress() return true on subsequent checks.
    var rollbackFlag = new boolean[] {false};
    when(operation.isRollbackInProgress()).thenAnswer(inv -> rollbackFlag[0]);
    doAnswer(inv -> {
      rollbackFlag[0] = true;
      return null;
    }).when(operation).rollbackInProgress();
    return operation;
  }

  /**
   * Builds a baseline {@link AbstractStorage} mock for hook tests: a stable
   * storage name plus a {@link WriteAheadLog} mock so the manager's
   * constructor wiring succeeds. The caller is expected to stub
   * {@code persistIndexCountDeltas}, {@code moveToErrorStateIfNeeded}, and any
   * other methods relevant to the test under construction.
   *
   * @param storageName name returned by {@code storage.getName()}
   * @param wal WAL mock the manager will wire into its constructor
   */
  static AbstractStorage mockStorage(String storageName, WriteAheadLog wal) {
    var storage = mock(AbstractStorage.class);
    when(storage.getName()).thenReturn(storageName);
    when(storage.getWALInstance()).thenReturn(wal);
    return storage;
  }
}
