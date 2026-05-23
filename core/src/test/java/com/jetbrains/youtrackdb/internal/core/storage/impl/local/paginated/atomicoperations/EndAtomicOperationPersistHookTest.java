package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.CommonStorageComponentException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.OperationsFreezer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for the persist hook inside {@link
 * AtomicOperationsManager#endAtomicOperation(AtomicOperation, Throwable)}.
 *
 * <p>The persist hook calls {@link AbstractStorage#persistIndexCountDeltas} before
 * {@code commitChanges} when (a) the inbound error is {@code null}, (b) the
 * operation is not already in rollback, (c) the per-operation
 * {@link IndexCountDeltaHolder} is non-null and not already latched as
 * persisted. Failures from the persist call (either {@code RuntimeException}
 * or {@code AssertionError}) are converted: the storage is moved to error
 * mode via {@code moveToErrorStateIfNeeded}, the operation is flipped to
 * rollback so the subsequent {@code commitChanges} is skipped, and after the
 * inner-finally {@code releaseLocks} the captured throwable is re-raised.
 * {@code RuntimeException} short-circuits as-is; {@code AssertionError} is
 * wrapped as {@link StorageException} so the outer call site sees a uniform
 * runtime exception (the method declares only {@code throws IOException}, so
 * a raw rethrow of an arbitrary {@code Throwable} would not compile, and an
 * {@code AssertionError} re-raise would land at
 * {@code logAndPrepareForRethrow(Error)} and re-trigger {@code setInError}).
 * {@code IOException} is not caught at the hook because
 * {@link AbstractStorage#persistIndexCountDeltas} does not declare it — the
 * underlying {@code BTree.addToApproximateEntriesCount} routes IO failures
 * through {@code executeInsideComponentOperation}, which converts them into
 * {@code CommonStorageComponentException} (a runtime) before the persist
 * method returns. Such failures therefore enter the
 * {@code RuntimeException} branch above.
 *
 * <p>Bounded catch: VM errors other than {@code AssertionError}
 * ({@code OutOfMemoryError}, {@code StackOverflowError}, {@code LinkageError},
 * {@code InternalError}) are deliberately not caught by the persist hook and
 * escape to the outer {@code catch (Error)} at the call site so genuine VM
 * failures still poison the storage.
 *
 * <p>Dual-invocation safety: the {@link IndexCountDeltaHolder#isPersisted()}
 * latch short-circuits the hook when the legacy inline persist call inside
 * {@link AbstractStorage#commit} has already run on the same atomic operation.
 * Closes the window where both call sites would otherwise double-write the
 * BTree entry-point page until the inline call is deleted in a later step;
 * remains as a defensive belt thereafter.
 */
public class EndAtomicOperationPersistHookTest {

  private static final String STORAGE_NAME = "endAtomicOpPersistHook";

  private AbstractStorage storage;
  private AtomicOperationsTable table;
  private WriteAheadLog wal;

  @Before
  public void setUp() {
    // Plain mock — the manager calls back into storage.persistIndexCountDeltas
    // and storage.moveToErrorStateIfNeeded; both are stubbed per test case.
    // checkErrorState / setInError bodies are not exercised here because the
    // manager's entry-level moveToErrorStateIfNeeded(error) is the only call
    // on the storage in the success path.
    storage = mock(AbstractStorage.class);
    when(storage.getName()).thenReturn(STORAGE_NAME);
    wal = mock(WriteAheadLog.class);
    when(storage.getWALInstance()).thenReturn(wal);
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(mock(AtomicOperationIdGen.class));
    table = mock(AtomicOperationsTable.class);
  }

  /**
   * Pre-increments the freezer's per-thread operation depth so that
   * {@code endAtomicOperation}'s outer-finally {@code endOperation()} call
   * sees a positive depth. The freezer is private to the manager, so a
   * reflective bump is the smallest change that lets us drive the lifecycle
   * method directly without spinning up the full {@code startToApplyOperations}
   * stack (which needs a populated {@code idGen}, WAL active segment, and
   * atomic operations table state).
   */
  private static void primeFreezer(AtomicOperationsManager manager) {
    try {
      Field f = AtomicOperationsManager.class.getDeclaredField(
          "writeOperationsFreezer");
      f.setAccessible(true);
      OperationsFreezer freezer = (OperationsFreezer) f.get(manager);
      freezer.startOperation();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(
          "Could not access AtomicOperationsManager.writeOperationsFreezer", e);
    }
  }

  /**
   * Builds a mock {@link AtomicOperation} pre-configured for the
   * {@code endAtomicOperation} call path: starts in not-rollback state, has
   * a stable commit timestamp, returns an empty locked-components iterable
   * so {@code releaseLocks} is a no-op, and routes {@code rollbackInProgress()}
   * through to flip the {@code isRollbackInProgress()} return value (so the
   * persist hook's rollback signal is observable downstream).
   */
  private AtomicOperation mockOperation(IndexCountDeltaHolder holder) {
    var operation = mock(AtomicOperation.class);
    when(operation.getCommitTs()).thenReturn(42L);
    when(operation.lockedComponents()).thenReturn(java.util.Collections.emptyList());
    when(operation.lockedObjects()).thenReturn(java.util.Collections.emptySet());
    when(operation.getIndexCountDeltas()).thenReturn(holder);
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
   * Persist failure with a {@link CommonStorageComponentException} — the
   * realistic shape of an IO failure on the persist path. {@code
   * BTree.addToApproximateEntriesCount} routes its work through
   * {@code executeInsideComponentOperation}, which wraps any {@code
   * IOException} from {@code loadPageForWrite} into
   * {@code CommonStorageComponentException} (a {@code RuntimeException})
   * before the call returns. The hook's {@code RuntimeException} branch
   * catches it. {@code IOException} is not a possible escape because
   * {@code persistIndexCountDeltas} does not declare it.
   */
  @Test
  public void persistHookConvertsComponentExceptionToRollbackAndShortCircuits()
      throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    var thrown = new CommonStorageComponentException(
        "simulated IO failure during persist", "ep-page",
        STORAGE_NAME);
    doThrow(thrown).when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    try {
      manager.endAtomicOperation(operation, null);
      fail("Expected the captured RuntimeException to be re-raised as-is");
    } catch (CommonStorageComponentException re) {
      // RuntimeException subclass short-circuits as-is — no StorageException
      // wrap on this path.
      assertSame("Captured RuntimeException must short-circuit as-is",
          thrown, re);
    }

    // Storage moved to error mode using the captured persist failure (not the
    // null inbound error from endAtomicOperation's signature).
    verify(storage, times(1)).moveToErrorStateIfNeeded(thrown);
    // Persist was attempted exactly once.
    verify(storage, times(1)).persistIndexCountDeltas(operation);
    // commitChanges was skipped because the hook flipped the operation into
    // rollback before the commit gate.
    verify(operation, never()).commitChanges(any(Long.class), any(WriteAheadLog.class));
    // The operation's table-side state was recorded as a rollback, not a commit.
    verify(table, times(1)).rollbackOperation(42L);
    verify(table, never()).commitOperation(42L);
  }

  /**
   * Persist failure with a {@link RuntimeException}: same conversion contract
   * as the {@code IOException} case, except the re-raise must short-circuit
   * the captured runtime exception as-is rather than wrap it. Existing
   * error-type contracts (for example a
   * {@code ConcurrentModificationException} caller catch) survive intact.
   */
  @Test
  public void persistHookConvertsRuntimeExceptionToRollbackAndShortCircuits()
      throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    var thrown = new RuntimeException("simulated persist runtime failure");
    doThrow(thrown).when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    try {
      manager.endAtomicOperation(operation, null);
      fail("Expected the captured RuntimeException to be re-raised as-is");
    } catch (RuntimeException re) {
      // Must be the exact instance — no StorageException wrap on the
      // RuntimeException path.
      assertSame("Captured RuntimeException must short-circuit as-is",
          thrown, re);
      assertFalse("RuntimeException must not be wrapped as StorageException",
          re instanceof StorageException);
    }

    verify(storage, times(1)).moveToErrorStateIfNeeded(thrown);
    verify(operation, never()).commitChanges(any(Long.class), any(WriteAheadLog.class));
    verify(table, times(1)).rollbackOperation(42L);
  }

  /**
   * Persist failure with an {@link AssertionError}: must convert to rollback
   * (skip {@code commitChanges}), the storage's
   * {@code moveToErrorStateIfNeeded} must be called with the
   * {@code AssertionError}, and the captured throwable must be re-raised
   * wrapped as a {@link StorageException} so the call site does not see a
   * bare {@code Error} (which would land
   * {@code logAndPrepareForRethrow(Error)} and re-trigger {@code setInError}).
   *
   * <p>The storage's {@code setInError} guard at the {@code AbstractStorage}
   * level deliberately skips the error-state flip for {@code AssertionError}
   * (defense-in-depth), but the hook still calls
   * {@code moveToErrorStateIfNeeded} per the persist-failure contract; the
   * guard is a downstream concern of {@code setInError}, not the hook.
   */
  @Test
  public void persistHookConvertsAssertionErrorToRollbackAndWraps()
      throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    var thrown = new AssertionError("simulated persist invariant violation");
    doThrow(thrown).when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    try {
      manager.endAtomicOperation(operation, null);
      fail("Expected StorageException wrapping the AssertionError");
    } catch (StorageException wrapped) {
      assertSame("Wrapped StorageException must carry the AssertionError as cause",
          thrown, wrapped.getCause());
    } catch (Error escaped) {
      fail("AssertionError must not escape as a bare Error: " + escaped);
    }

    verify(storage, times(1)).moveToErrorStateIfNeeded(thrown);
    verify(operation, never()).commitChanges(any(Long.class), any(WriteAheadLog.class));
    verify(table, times(1)).rollbackOperation(42L);
  }

  /**
   * Bounded catch: an {@link OutOfMemoryError} (an {@link Error} but not an
   * {@link AssertionError}) must escape the persist hook as a bare
   * {@code Error}, not be wrapped as {@link StorageException}. Matches the
   * scope chosen by the four wrapper catches in
   * {@link AtomicOperationsManager#executeInsideAtomicOperation} etc., and
   * keeps genuine VM failures routing through the outer
   * {@code catch (Error)} at the {@code commit} call site so the storage is
   * poisoned via {@code logAndPrepareForRethrow(Error)}.
   */
  @Test
  public void persistHookDoesNotCatchOutOfMemoryError() throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    var thrown = new OutOfMemoryError("simulated OOM");
    doThrow(thrown).when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    try {
      manager.endAtomicOperation(operation, null);
      fail("Expected the OutOfMemoryError to escape the persist hook");
    } catch (OutOfMemoryError escaped) {
      assertSame("OutOfMemoryError must escape as-is, not be wrapped",
          thrown, escaped);
    } catch (StorageException wrapped) {
      fail("OutOfMemoryError must not be wrapped as StorageException: "
          + wrapped);
    }

    // moveToErrorStateIfNeeded must NOT be called on the OOM path — the
    // bounded catch deliberately does not cover OOM, so the throwable bypasses
    // the hook's conversion machinery. Only the entry-level call at line 260
    // of endAtomicOperation fires, with the inbound null error.
    verify(storage, times(1)).moveToErrorStateIfNeeded(null);
    verify(storage, never()).moveToErrorStateIfNeeded(thrown);
  }

  /**
   * Dual-invocation safety: when the holder has already been marked
   * persisted (by the legacy inline call inside {@link AbstractStorage#commit}),
   * the lifecycle hook must NOT re-invoke {@code persistIndexCountDeltas}.
   * Closes the window where both the inline call and the hook would
   * double-write the BTree entry-point page within a single transaction.
   *
   * <p>The check is symmetric: the hook also runs the normal commit path
   * (i.e., commit is not converted to rollback). Verifying both halves of
   * the contract — no persist re-invocation, normal commit downstream — pins
   * the latch's role as a short-circuit, not as a rollback trigger.
   */
  @Test
  public void persistHookSkipsWhenHolderAlreadyPersisted() throws IOException {
    var holder = new IndexCountDeltaHolder();
    holder.setPersisted();
    var operation = mockOperation(holder);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    // Persist not re-invoked — the latch short-circuited the hook.
    verify(storage, never()).persistIndexCountDeltas(operation);
    // The single moveToErrorStateIfNeeded call is the entry-level one with
    // the null inbound error; no persist-failure path fired.
    verify(storage, times(1)).moveToErrorStateIfNeeded(null);
    // Commit path proceeded normally — the holder being pre-persisted does
    // not flip the operation into rollback.
    verify(operation, times(1)).commitChanges(42L, wal);
    verify(table, times(1)).commitOperation(42L);
    verify(table, never()).rollbackOperation(42L);
  }

  /**
   * Rollback path: when {@code endAtomicOperation} is entered with a non-null
   * inbound error, the persist hook must be skipped entirely (the hook is
   * gated on {@code currentError == null && !operation.isRollbackInProgress()}).
   * The entry-level {@code moveToErrorStateIfNeeded(inbound)} call fires, the
   * operation is flipped to rollback, and the table-side state is recorded as
   * a rollback. No StorageException wrap on this path — the inbound error is
   * the caller's responsibility to handle.
   */
  @Test
  public void persistHookSkipsOnInboundError() throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    var inbound = new RuntimeException("inbound error from rollback path");

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, inbound);

    // Persist must not be called when entering on the rollback path.
    verify(storage, never()).persistIndexCountDeltas(operation);
    // The entry-level moveToErrorStateIfNeeded fires with the inbound error.
    verify(storage, times(1)).moveToErrorStateIfNeeded(inbound);
    // commitChanges skipped because the operation is in rollback.
    verify(operation, never()).commitChanges(any(Long.class), any(WriteAheadLog.class));
    verify(table, times(1)).rollbackOperation(42L);
    verify(table, never()).commitOperation(42L);
  }

  /**
   * Null-holder path: when no {@link IndexCountDeltaHolder} has been
   * accumulated for this atomic operation (e.g. recovery-time atomic ops
   * during {@code open()}, which never touch the per-tx counter machinery),
   * the persist hook must be a no-op. The commit path proceeds normally.
   * Mirrors the existing {@code if (holder == null) return;} early-exit
   * inside {@code AbstractStorage.persistIndexCountDeltas}.
   */
  @Test
  public void persistHookSkipsWhenHolderIsNull() throws IOException {
    var operation = mockOperation(null);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    verify(storage, never()).persistIndexCountDeltas(operation);
    verify(operation, times(1)).commitChanges(42L, wal);
    verify(table, times(1)).commitOperation(42L);
  }

  /**
   * Latch behaviour at the source: {@link IndexCountDeltaHolder} starts in
   * the non-persisted state, and {@link IndexCountDeltaHolder#setPersisted()}
   * is idempotent. The lifecycle hook depends on this contract — the latch
   * is read inside the hook gate and set at the end of
   * {@link AbstractStorage#persistIndexCountDeltas} so a second pass on the
   * same holder no-ops.
   */
  @Test
  public void indexCountDeltaHolderPersistedLatchIsIdempotent() {
    var holder = new IndexCountDeltaHolder();
    assertFalse("Holder starts in the non-persisted state", holder.isPersisted());
    holder.setPersisted();
    assertTrue("Latch flips after setPersisted()", holder.isPersisted());
    holder.setPersisted();
    assertTrue("setPersisted() must be idempotent", holder.isPersisted());
  }

  /**
   * Anchor for the {@code AbstractStorage.persistIndexCountDeltas} side of
   * the latch contract: the holder is created via
   * {@code AtomicOperation.getOrCreateIndexCountDeltas()} on the accumulate
   * path, lives on the operation, and is read back by both the inline
   * persist call (until it is deleted) and the lifecycle hook. The hook's
   * {@code isPersisted()} gate ties the two call sites together. This test
   * pins the empty-holder shape so a future regression that defaults
   * {@code persisted} to {@code true} (or skips the field altogether) fails
   * here rather than in a higher-level commit test.
   */
  @Test
  public void emptyHolderHasNoDeltasAndIsNotPersisted() {
    var holder = new IndexCountDeltaHolder();
    assertNotNull(holder.getDeltas());
    assertEquals(0, holder.getDeltas().size());
    assertFalse(holder.isPersisted());
  }

  /**
   * Sanity that the entry-level {@code moveToErrorStateIfNeeded} call on the
   * happy path receives a null argument. Combined with the persist-failure
   * tests, this pins the call shape: one null call on success, two calls on
   * the persist-failure path (null at entry, then the persist failure inside
   * the hook).
   */
  @Test
  public void happyPathEntryLevelMoveToErrorStateReceivesNull()
      throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    // No throw from persist — the success path runs end-to-end.
    doNothing().when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    verify(storage, times(1)).moveToErrorStateIfNeeded(null);
    verify(storage, times(1)).persistIndexCountDeltas(operation);
    verify(operation, times(1)).commitChanges(42L, wal);
    verify(table, times(1)).commitOperation(42L);
    // Reaching this line is the no-re-raise assertion — any thrown exception
    // would have skipped past it and failed the test before verify().
  }
}
