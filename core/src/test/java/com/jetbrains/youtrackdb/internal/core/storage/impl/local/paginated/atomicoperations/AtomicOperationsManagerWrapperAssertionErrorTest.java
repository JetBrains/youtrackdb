package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.CommonStorageComponentException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/**
 * Wrapper-level cascade-containment regression test for the broadened catches in
 * {@link AtomicOperationsManager#executeInsideAtomicOperation},
 * {@link AtomicOperationsManager#calculateInsideAtomicOperation},
 * {@link AtomicOperationsManager#executeInsideComponentOperation}, and
 * {@link AtomicOperationsManager#calculateInsideComponentOperation}.
 *
 * <p>Before the catches were broadened from {@code catch (Exception e)} to
 * {@code catch (Exception | AssertionError e)}, an {@code AssertionError} thrown
 * from the lambda body (which is possible on a {@code -ea} JVM, the test JVM
 * default) would bypass the catch, leave the {@code error} variable {@code null},
 * and propagate to the API caller as a bare {@link Error}. In the atomic-operation
 * wrappers it would also run {@code endAtomicOperation(op, null)} as if the op
 * had succeeded, committing the half-baked atomic op. The component-operation
 * wrappers do not own the lifecycle, but an escaping {@link AssertionError} would
 * still skip the storage-context-wrapping the catch normally performs.
 *
 * <p>After the broadening, each catch handles both {@link Exception} and
 * {@link AssertionError}. The atomic-operation pair captures the throwable into
 * {@code error}, throws a {@link StorageException} with the original throwable
 * as the cause, and the {@code finally} block calls
 * {@code endAtomicOperation(op, error)} so the rollback path runs. The
 * component-operation pair throws a {@link CommonStorageComponentException}
 * with the throwable as the cause.
 */
public class AtomicOperationsManagerWrapperAssertionErrorTest {

  private static final String STORAGE_NAME = "testStorage";
  private static final String COMPONENT_LOCK_NAME = "testComponent";

  private AbstractStorage storage;
  private AtomicOperationsTable table;

  @Before
  public void setUp() {
    // Plain mock (no CALLS_REAL_METHODS): we stub endAtomicOperation on the
    // manager spy and do not need the real moveToErrorStateIfNeeded /
    // checkErrorState bodies to fire. The latter would NPE on the uninitialized
    // error field in a mocked AbstractStorage.
    storage = mock(AbstractStorage.class);
    when(storage.getName()).thenReturn(STORAGE_NAME);
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(mock(AtomicOperationIdGen.class));

    table = mock(AtomicOperationsTable.class);
  }

  /**
   * Builds a spy on {@link AtomicOperationsManager} that returns a mock
   * {@link AtomicOperation} from {@code startAtomicOperation()} and treats
   * {@code startToApplyOperations} / {@code endAtomicOperation} as no-ops.
   * The spy lets us test the atomic-operation-wrapper catch contract without
   * dragging in the full {@code AtomicOperationBinaryTracking} construction
   * graph (which needs ReadCache, WriteCache, WAL, snapshot indexes, and ID
   * generators with realistic state).
   */
  private AtomicOperationsManager spyManagerWithMockedLifecycle(
      AtomicReference<Throwable> capturedError, AtomicOperation operation)
      throws java.io.IOException {
    var spyManager = spy(new AtomicOperationsManager(storage, table));
    doReturn(operation).when(spyManager).startAtomicOperation();
    doNothing().when(spyManager).startToApplyOperations(any());
    // Capture the error argument so the test can verify the rollback path
    // was taken (error != null) rather than the success path (error == null).
    doAnswer(invocation -> {
      capturedError.set(invocation.getArgument(1));
      return null;
    }).when(spyManager).endAtomicOperation(any(), any());
    return spyManager;
  }

  /**
   * executeInsideAtomicOperation: an AssertionError thrown from the consumer
   * must be caught, wrapped as StorageException with the AssertionError as
   * cause, and endAtomicOperation must be invoked with the AssertionError so
   * the rollback path runs. Without the broadened catch, the AssertionError
   * would escape as a bare Error and endAtomicOperation would be invoked
   * with a null error (committing the op as success).
   */
  @Test
  public void executeInsideAtomicOperationCatchesAssertionErrorAndRollsBack()
      throws java.io.IOException {
    var operation = mock(AtomicOperation.class);
    var capturedError = new AtomicReference<Throwable>();
    var spyManager = spyManagerWithMockedLifecycle(capturedError, operation);
    var thrown = new AssertionError("simulated lambda-body invariant violation");

    try {
      spyManager.executeInsideAtomicOperation(op -> {
        throw thrown;
      });
      fail("Expected StorageException wrapping the AssertionError");
    } catch (StorageException wrapped) {
      assertSame("Wrapped StorageException must carry the AssertionError as cause",
          thrown, wrapped.getCause());
    } catch (Error escaped) {
      fail("AssertionError escaped the wrapper as a bare Error: " + escaped);
    }

    verify(spyManager, times(1)).endAtomicOperation(any(), any());
    assertSame(
        "endAtomicOperation must receive the AssertionError so the rollback path runs",
        thrown, capturedError.get());
  }

  /**
   * calculateInsideAtomicOperation: same contract as executeInsideAtomicOperation
   * — AssertionError caught, wrapped as StorageException, endAtomicOperation
   * invoked with the AssertionError to trigger rollback.
   */
  @Test
  public void calculateInsideAtomicOperationCatchesAssertionErrorAndRollsBack()
      throws java.io.IOException {
    var operation = mock(AtomicOperation.class);
    var capturedError = new AtomicReference<Throwable>();
    var spyManager = spyManagerWithMockedLifecycle(capturedError, operation);
    var thrown = new AssertionError("simulated lambda-body invariant violation");

    try {
      spyManager.calculateInsideAtomicOperation(op -> {
        throw thrown;
      });
      fail("Expected StorageException wrapping the AssertionError");
    } catch (StorageException wrapped) {
      assertSame("Wrapped StorageException must carry the AssertionError as cause",
          thrown, wrapped.getCause());
    } catch (Error escaped) {
      fail("AssertionError escaped the wrapper as a bare Error: " + escaped);
    }

    verify(spyManager, times(1)).endAtomicOperation(any(), any());
    assertSame(
        "endAtomicOperation must receive the AssertionError so the rollback path runs",
        thrown, capturedError.get());
  }

  /**
   * Builds a minimal mock {@link AtomicOperation} for the component-operation
   * pair. {@link AtomicOperationsManager#executeInsideComponentOperation} and
   * {@link AtomicOperationsManager#calculateInsideComponentOperation} do not
   * own the atomic-op lifecycle, so we just need an operation that pretends
   * the component lock is already held (so the wrapper does not actually
   * acquire it).
   */
  private AtomicOperation mockOperationWithLockAlreadyHeld() {
    var operation = mock(AtomicOperation.class);
    when(operation.containsInLockedObjects(anyString())).thenReturn(true);
    return operation;
  }

  /**
   * executeInsideComponentOperation: an AssertionError thrown from the consumer
   * must be caught and wrapped as CommonStorageComponentException with the
   * AssertionError as cause. Without the broadened catch, the AssertionError
   * would escape as a bare Error and skip the storage-context-wrapping the
   * catch normally performs.
   */
  @Test
  public void executeInsideComponentOperationCatchesAssertionError() {
    var operation = mockOperationWithLockAlreadyHeld();
    var component = mock(StorageComponent.class);
    when(component.getLockName()).thenReturn(COMPONENT_LOCK_NAME);
    var manager = new AtomicOperationsManager(storage, table);
    var thrown = new AssertionError("simulated component-op invariant violation");

    try {
      manager.executeInsideComponentOperation(operation, component, op -> {
        throw thrown;
      });
      fail("Expected CommonStorageComponentException wrapping the AssertionError");
    } catch (CommonStorageComponentException wrapped) {
      assertSame("Wrapped exception must carry the AssertionError as cause",
          thrown, wrapped.getCause());
      assertTrue(
          "Message must include the component lock name",
          wrapped.getMessage().contains(COMPONENT_LOCK_NAME));
      assertTrue(
          "Message must include the storage name",
          wrapped.getMessage().contains(STORAGE_NAME));
    } catch (Error escaped) {
      fail("AssertionError escaped the wrapper as a bare Error: " + escaped);
    }
  }

  /**
   * calculateInsideComponentOperation: same contract as
   * executeInsideComponentOperation — AssertionError caught and wrapped as
   * CommonStorageComponentException with the AssertionError as cause.
   */
  @Test
  public void calculateInsideComponentOperationCatchesAssertionError() {
    var operation = mockOperationWithLockAlreadyHeld();
    var component = mock(StorageComponent.class);
    when(component.getLockName()).thenReturn(COMPONENT_LOCK_NAME);
    var manager = new AtomicOperationsManager(storage, table);
    var thrown = new AssertionError("simulated component-op invariant violation");

    try {
      manager.calculateInsideComponentOperation(operation, component, op -> {
        throw thrown;
      });
      fail("Expected CommonStorageComponentException wrapping the AssertionError");
    } catch (CommonStorageComponentException wrapped) {
      assertSame("Wrapped exception must carry the AssertionError as cause",
          thrown, wrapped.getCause());
      assertTrue(
          "Message must include the component lock name",
          wrapped.getMessage().contains(COMPONENT_LOCK_NAME));
      assertTrue(
          "Message must include the storage name",
          wrapped.getMessage().contains(STORAGE_NAME));
    } catch (Error escaped) {
      fail("AssertionError escaped the wrapper as a bare Error: " + escaped);
    }
  }

  /**
   * Sanity check on the executeInsideAtomicOperation wrapper's RuntimeException
   * behaviour: a RuntimeException from the consumer must still wrap as
   * StorageException with the RuntimeException as cause, and the rollback
   * path must still run. The broadened catch must not regress the existing
   * exception behaviour.
   */
  @Test
  public void executeInsideAtomicOperationStillWrapsRuntimeException()
      throws java.io.IOException {
    var operation = mock(AtomicOperation.class);
    var capturedError = new AtomicReference<Throwable>();
    var spyManager = spyManagerWithMockedLifecycle(capturedError, operation);
    var thrown = new RuntimeException("pre-existing exception path");

    try {
      spyManager.executeInsideAtomicOperation(op -> {
        throw thrown;
      });
      fail("Expected StorageException wrapping the RuntimeException");
    } catch (StorageException wrapped) {
      assertSame("Wrapped StorageException must carry the RuntimeException as cause",
          thrown, wrapped.getCause());
    }

    assertNotNull("endAtomicOperation must receive the RuntimeException",
        capturedError.get());
    assertSame(thrown, capturedError.get());
  }

  /**
   * Sanity check that the catch broadening did not change the OutOfMemoryError
   * escape path: an {@link OutOfMemoryError} is an {@link Error} but not an
   * {@link AssertionError}, so it must escape the wrapper as a bare Error, not
   * be wrapped as a StorageException. This pins the deliberate scope choice
   * documented in the catch's inline comment ("Error superclasses other than
   * AssertionError are deliberately not caught here").
   */
  @Test
  public void executeInsideAtomicOperationDoesNotCatchOutOfMemoryError()
      throws java.io.IOException {
    var operation = mock(AtomicOperation.class);
    var capturedError = new AtomicReference<Throwable>();
    var spyManager = spyManagerWithMockedLifecycle(capturedError, operation);
    var thrown = new OutOfMemoryError("simulated OOM");

    try {
      spyManager.executeInsideAtomicOperation(op -> {
        throw thrown;
      });
      fail("Expected OutOfMemoryError to escape as a bare Error");
    } catch (OutOfMemoryError escaped) {
      assertSame("OutOfMemoryError must escape unchanged", thrown, escaped);
    } catch (StorageException wrapped) {
      fail("OutOfMemoryError must not be wrapped as StorageException; was: "
          + wrapped);
    }

    // endAtomicOperation must still be invoked via the finally block, with
    // a null error (the outer catch in the wrapper did not match, so error
    // stayed null).
    verify(spyManager, atLeastOnce()).endAtomicOperation(any(), any());
  }
}
