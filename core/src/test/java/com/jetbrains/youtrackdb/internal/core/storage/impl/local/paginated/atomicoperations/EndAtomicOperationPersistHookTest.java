package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.DEFAULT_COMMIT_TS;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.mockOperation;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.mockStorage;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.primeFreezer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.core.exception.CommonStorageComponentException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.SetInErrorAssertionErrorGuardTest;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

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
 * <p>The {@code AssertionError} skip-branch inside
 * {@code AbstractStorage.setInError} is a separate, downstream defense — it
 * keeps a stray dev/test invariant violation from flipping the storage into
 * permanent error state even if it reaches the setter. That guard's contract
 * is pinned in {@link SetInErrorAssertionErrorGuardTest}; the hook just calls
 * {@code moveToErrorStateIfNeeded} and lets the setter decide.
 *
 * <p>Dual-invocation safety: the {@link IndexCountDeltaHolder#isPersisted()}
 * latch short-circuits the hook when the legacy inline persist call inside
 * {@link AbstractStorage#commit} has already run on the same atomic operation.
 * Closes the window where both call sites would otherwise double-write the
 * BTree entry-point page until the inline call is deleted in a later step;
 * remains as a defensive belt thereafter.
 *
 * @see SetInErrorAssertionErrorGuardTest
 */
public class EndAtomicOperationPersistHookTest {

  private static final String STORAGE_NAME = "endAtomicOpPersistHook";

  private AbstractStorage storage;
  private AtomicOperationsTable table;
  private WriteAheadLog wal;

  @Before
  public void setUp() {
    // Plain mocks — the manager calls back into storage.persistIndexCountDeltas
    // and storage.moveToErrorStateIfNeeded; both are stubbed per test case.
    // Only the two getters used by the manager's constructor are stubbed here
    // (getName, getWALInstance); read-cache, write-cache, and id-gen are not
    // exercised on the endAtomicOperation path so we leave them at Mockito's
    // default (null) return.
    wal = mock(WriteAheadLog.class);
    storage = mockStorage(STORAGE_NAME, wal);
    table = mock(AtomicOperationsTable.class);
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
    // null inbound error from endAtomicOperation's signature). Order pins the
    // two-call shape: first the entry-level null moveToErrorStateIfNeeded,
    // then the persist-failure call inside the hook.
    InOrder errorOrder = Mockito.inOrder(storage);
    errorOrder.verify(storage).moveToErrorStateIfNeeded(null);
    errorOrder.verify(storage).moveToErrorStateIfNeeded(thrown);
    // Persist was attempted exactly once.
    verify(storage, times(1)).persistIndexCountDeltas(operation);
    // commitChanges was skipped because the hook flipped the operation into
    // rollback before the commit gate.
    verify(operation, never()).commitChanges(any(Long.class), any(WriteAheadLog.class));
    // The operation's table-side state was recorded as a rollback, not a commit.
    verify(table, times(1)).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).commitOperation(DEFAULT_COMMIT_TS);
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

    // Same two-call shape as the ComponentException case above: entry-level
    // null call, then the persist failure inside the hook.
    InOrder errorOrder = Mockito.inOrder(storage);
    errorOrder.verify(storage).moveToErrorStateIfNeeded(null);
    errorOrder.verify(storage).moveToErrorStateIfNeeded(thrown);
    verify(storage, times(1)).persistIndexCountDeltas(operation);
    verify(operation, never()).commitChanges(any(Long.class), any(WriteAheadLog.class));
    verify(table, times(1)).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).commitOperation(DEFAULT_COMMIT_TS);
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
   * (defense-in-depth, contract pinned in
   * {@link SetInErrorAssertionErrorGuardTest}), but the hook still calls
   * {@code moveToErrorStateIfNeeded} per the persist-failure contract; the
   * guard is a downstream concern of {@code setInError}, not the hook.
   *
   * <p>This test is also the load-bearing pin for the
   * locks-released-before-throw invariant: the re-raise lives outside the
   * inner-try so the per-component locks (and the {@code deactivate} that
   * follows in the inner-finally) run before the wrapped exception escapes.
   * The {@link InOrder} on {@code rollbackInProgress} ->
   * {@code lockedComponents} -> {@code deactivate} pins the call order; a
   * future refactor that re-throws before {@code releaseLocks} would break
   * the order and fail here rather than leak a stuck lock at runtime.
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
      // dbName must carry the storage name through the wrap so downstream
      // logging keeps the storage identity attached to the failure.
      assertEquals("Wrapped StorageException must carry the storage dbName",
          STORAGE_NAME, wrapped.getDbName());
      assertTrue(
          "Wrapped StorageException message must identify the commit-time origin: "
              + wrapped.getMessage(),
          wrapped.getMessage() != null
              && wrapped.getMessage().contains("Error during transaction commit"));
    } catch (Error escaped) {
      fail("AssertionError must not escape as a bare Error: " + escaped);
    }

    // Storage moved to error mode using the captured persist failure (not the
    // null inbound error from endAtomicOperation's signature). Order pins the
    // two-call shape.
    InOrder errorOrder = Mockito.inOrder(storage);
    errorOrder.verify(storage).moveToErrorStateIfNeeded(null);
    errorOrder.verify(storage).moveToErrorStateIfNeeded(thrown);
    verify(storage, times(1)).persistIndexCountDeltas(operation);
    verify(operation, never()).commitChanges(any(Long.class), any(WriteAheadLog.class));
    verify(table, times(1)).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).commitOperation(DEFAULT_COMMIT_TS);

    // Locks-released-before-throw invariant: rollbackInProgress is set in the
    // hook's catch (inner-try), lockedComponents is read in releaseLocks
    // (inner-finally), and deactivate runs in the same inner-finally. All
    // three must precede the re-raise.
    InOrder lockOrder = Mockito.inOrder(operation);
    lockOrder.verify(operation).rollbackInProgress();
    lockOrder.verify(operation).lockedComponents();
    lockOrder.verify(operation).deactivate();
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
    assertVmErrorEscapesUnconverted(new OutOfMemoryError("simulated OOM"));
  }

  /**
   * Bounded catch: a {@link LinkageError} (e.g. {@code NoClassDefFoundError},
   * {@code IncompatibleClassChangeError}) must escape the persist hook as a
   * bare {@code Error}. Linkage failures usually mean the runtime environment
   * is broken; wrapping them as {@code StorageException} would hide the
   * actual failure mode from the caller.
   */
  @Test
  public void persistHookDoesNotCatchLinkageError() throws IOException {
    assertVmErrorEscapesUnconverted(new LinkageError("simulated linkage failure"));
  }

  /**
   * Bounded catch: a {@link StackOverflowError} must escape the persist hook
   * as a bare {@code Error}. Stack overflows indicate the thread is unable to
   * make progress; wrapping them would suggest the storage is recoverable
   * when it is not.
   */
  @Test
  public void persistHookDoesNotCatchStackOverflowError() throws IOException {
    assertVmErrorEscapesUnconverted(new StackOverflowError("simulated stack overflow"));
  }

  /**
   * Bounded catch: an {@link InternalError} (VM-internal failure) must escape
   * the persist hook as a bare {@code Error}. InternalError is the runtime's
   * signal that something is wrong at a level the application cannot
   * meaningfully respond to.
   */
  @Test
  public void persistHookDoesNotCatchInternalError() throws IOException {
    assertVmErrorEscapesUnconverted(new InternalError("simulated VM-internal failure"));
  }

  /**
   * Shared verification for the four VM-error escape tests above. The persist
   * stub raises the given error, and the test asserts (i) the same instance
   * escapes as a bare {@code Error}, (ii) no {@code StorageException} wrap
   * fires, and (iii) the entry-level {@code moveToErrorStateIfNeeded(null)}
   * is the only such call (the hook's catch does not cover this error class,
   * so the persist-failure conversion machinery never fires).
   */
  private void assertVmErrorEscapesUnconverted(Error thrown) throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    doThrow(thrown).when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    try {
      manager.endAtomicOperation(operation, null);
      fail("Expected the " + thrown.getClass().getSimpleName()
          + " to escape the persist hook");
    } catch (StorageException wrapped) {
      fail(thrown.getClass().getSimpleName()
          + " must not be wrapped as StorageException: " + wrapped);
    } catch (Error escaped) {
      assertSame(thrown.getClass().getSimpleName() + " must escape as-is, not be wrapped",
          thrown, escaped);
    }

    // Persist was attempted exactly once; the conversion-to-rollback machinery
    // never fired (no rollbackOperation, no commitOperation, no second
    // moveToErrorStateIfNeeded call).
    verify(storage, times(1)).persistIndexCountDeltas(operation);
    verify(storage, times(1)).moveToErrorStateIfNeeded(null);
    verify(storage, never()).moveToErrorStateIfNeeded(thrown);
    verify(table, never()).rollbackOperation(any(Long.class));
    verify(table, never()).commitOperation(any(Long.class));
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
    verify(operation, times(1)).commitChanges(DEFAULT_COMMIT_TS, wal);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).rollbackOperation(DEFAULT_COMMIT_TS);
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
    verify(table, times(1)).rollbackOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).commitOperation(DEFAULT_COMMIT_TS);
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
    // Entry-level moveToErrorStateIfNeeded still fires (with the null inbound
    // error); no persist-failure path runs because persist is skipped at the
    // null-holder gate, not because a failure was caught.
    verify(storage, times(1)).moveToErrorStateIfNeeded(null);
    verify(operation, times(1)).commitChanges(DEFAULT_COMMIT_TS, wal);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).rollbackOperation(DEFAULT_COMMIT_TS);
  }

  /**
   * Storage-already-in-error contract: pins the current Hook A gate, which
   * checks only {@code currentError == null && !operation.isRollbackInProgress()}
   * and does NOT consult {@code storage.isInError()}. The hook therefore
   * attempts persist anyway when called with a {@code null} inbound error,
   * regardless of whether a prior transaction left the storage flagged as
   * in-error.
   *
   * <p>The protected {@code isInError()} method is not stubbable from this
   * package, so the test cannot directly set the storage to in-error and
   * observe the hook's behaviour. The contract is instead pinned by
   * inspection of the Hook A condition (no {@code isInError} call) plus
   * the assertion below that the persist call still fires on the null-error
   * entry path. If a future change adds an {@code isInError()} guard to
   * Hook A, that change will move {@code AbstractStorage.isInError} to
   * package-private or expose a callable seam, and the test will be updated
   * to verify the "skips when storage in error" contract; either contract
   * is sound, and pinning the current one closes the silent-regression
   * window in either direction.
   */
  @Test
  public void persistHookDoesNotConsultIsInErrorOnNullEntryPath() throws IOException {
    var holder = new IndexCountDeltaHolder();
    var operation = mockOperation(holder);
    // The hook does not consult isInError(); no throw from the persist mock
    // so the commit path runs end-to-end.
    doNothing().when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    verify(storage, times(1)).persistIndexCountDeltas(operation);
    // Entry-level moveToErrorStateIfNeeded fires with the null inbound error.
    // No second call because persist returned cleanly.
    verify(storage, times(1)).moveToErrorStateIfNeeded(null);
    // commit path runs to completion.
    verify(operation, times(1)).commitChanges(DEFAULT_COMMIT_TS, wal);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
    verify(table, never()).rollbackOperation(DEFAULT_COMMIT_TS);
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
   * path, lives on the operation, and is read by the lifecycle persist hook
   * inside {@code AtomicOperationsManager.endAtomicOperation}. The hook's
   * {@code isPersisted()} gate guards against re-entry on the same atomic
   * operation. This test pins the empty-holder shape so a future regression
   * that defaults {@code persisted} to {@code true} (or skips the field
   * altogether) fails here rather than in a higher-level commit test.
   */
  @Test
  public void emptyHolderHasNoDeltasAndIsNotPersisted() {
    var holder = new IndexCountDeltaHolder();
    assertNotNull(holder.getDeltas());
    assertEquals(0, holder.getDeltas().size());
    assertFalse(holder.isPersisted());
  }

  /**
   * Non-empty holder forwarding: a holder carrying real per-engine deltas
   * (the production accumulate path adds one delta entry per engine touched
   * by the transaction) must be forwarded to {@code persistIndexCountDeltas}
   * the same way an empty one is. The mocked persist stub returns without
   * setting the latch, so the holder's {@code isPersisted()} stays
   * {@code false} after the call returns — the hook itself does not touch
   * the latch; setting it is the responsibility of
   * {@code AbstractStorage.persistIndexCountDeltas} after a successful pass.
   * If a future refactor moves the {@code setPersisted()} call into the hook,
   * this test will fail.
   */
  @Test
  public void persistHookForwardsNonEmptyHolderWithoutSettingLatch() throws IOException {
    var holder = new IndexCountDeltaHolder();
    // Populate the holder so it is genuinely non-empty. The default
    // IndexCountDelta starts with zero net deltas, which is fine for the
    // forwarding assertion below; what matters is that the holder is
    // distinguishable from an empty one when production code iterates it.
    holder.getOrCreate(7);
    assertEquals("Holder must carry one engine entry after getOrCreate(7)",
        1, holder.getDeltas().size());
    var operation = mockOperation(holder);
    // No throw — happy path. The mocked persistIndexCountDeltas does not
    // touch the latch because the real method's latch flip is the
    // production-side responsibility; here we only verify the hook calls it.
    doNothing().when(storage).persistIndexCountDeltas(operation);

    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(operation, null);

    verify(storage, times(1)).persistIndexCountDeltas(operation);
    assertFalse("The hook itself must not set the persisted latch",
        holder.isPersisted());
    verify(operation, times(1)).commitChanges(DEFAULT_COMMIT_TS, wal);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
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
    verify(operation, times(1)).commitChanges(DEFAULT_COMMIT_TS, wal);
    verify(table, times(1)).commitOperation(DEFAULT_COMMIT_TS);
    // Reaching this line is the no-re-raise assertion — any thrown exception
    // would have skipped past it and failed the test before verify().
  }
}
