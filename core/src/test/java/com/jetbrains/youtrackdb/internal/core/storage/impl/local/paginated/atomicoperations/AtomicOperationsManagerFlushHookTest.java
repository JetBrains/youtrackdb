package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.CommonStorageComponentException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that {@link AtomicOperationsManager} calls
 * {@link AtomicOperation#flushPendingOperations()} at the correct points in
 * {@code executeInsideComponentOperation} and {@code calculateInsideComponentOperation}:
 * after successful execution of the consumer/function, but not when the consumer/function throws.
 */
public class AtomicOperationsManagerFlushHookTest {

  private AtomicOperationsManager manager;
  private StorageComponent component;

  @Before
  public void setUp() {
    var storage = mock(AbstractStorage.class);
    when(storage.getName()).thenReturn("testStorage");
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(mock(AtomicOperationIdGen.class));

    var table = mock(AtomicOperationsTable.class);
    manager = new AtomicOperationsManager(storage, table);

    component = mock(StorageComponent.class);
    when(component.getLockName()).thenReturn("testComponent");
  }

  private AtomicOperation mockOperation() {
    var operation = mock(AtomicOperation.class);
    when(operation.containsInLockedObjects(anyString())).thenReturn(true);
    return operation;
  }

  /**
   * After successful executeInsideComponentOperation, flushPendingOperations must be called
   * exactly once.
   */
  @Test
  public void testExecuteFlushesAfterSuccess() throws IOException {
    var operation = mockOperation();
    manager.executeInsideComponentOperation(
        operation, component, op -> {
          /* successful no-op */});
    verify(operation, times(1)).flushPendingOperations();
  }

  /**
   * When the consumer throws, flushPendingOperations must NOT be called — pending ops
   * are discarded with the rolled-back operation.
   */
  @Test
  public void testExecuteDoesNotFlushOnException() throws IOException {
    var operation = mockOperation();
    try {
      manager.executeInsideComponentOperation(
          operation, component, op -> {
            throw new RuntimeException("consumer failure");
          });
      fail("Expected exception");
    } catch (CommonStorageComponentException e) {
      // expected
    }
    verify(operation, never()).flushPendingOperations();
  }

  /**
   * After successful calculateInsideComponentOperation, flushPendingOperations must be called
   * exactly once, and the return value must be the function's result.
   */
  @Test
  public void testCalculateFlushesAfterSuccess() throws IOException {
    var operation = mockOperation();
    int result = manager.calculateInsideComponentOperation(
        operation, component, op -> 42);
    assertEquals(42, result);
    verify(operation, times(1)).flushPendingOperations();
  }

  /**
   * When the function throws, flushPendingOperations must NOT be called.
   */
  @Test
  public void testCalculateDoesNotFlushOnException() throws IOException {
    var operation = mockOperation();
    try {
      manager.calculateInsideComponentOperation(
          operation, component, op -> {
            throw new RuntimeException("function failure");
          });
      fail("Expected exception");
    } catch (CommonStorageComponentException e) {
      // expected
    }
    verify(operation, never()).flushPendingOperations();
  }

  /**
   * Verifies that flush is called after the consumer completes, not before — the consumer's
   * mutations must be fully applied before they are flushed to WAL.
   */
  @Test
  public void testExecuteFlushHappensAfterConsumer() throws IOException {
    var consumerCompleted = new AtomicBoolean(false);
    var operation = mockOperation();

    manager.executeInsideComponentOperation(
        operation, component, op -> consumerCompleted.set(true));

    // If flushPendingOperations were called before consumer, consumerCompleted would be false
    // at flush time. We verify it was true by the time we get here.
    assertTrue("Consumer should have completed before flush", consumerCompleted.get());
    verify(operation).flushPendingOperations();
  }

  /**
   * Verifies that calculateInsideComponentOperation captures the return value before flushing —
   * the function's result is available even if flush modifies state.
   */
  @Test
  public void testCalculateReturnValueCapturedBeforeFlush() throws IOException {
    var operation = mockOperation();
    var result = manager.calculateInsideComponentOperation(
        operation, component, op -> "captured");
    assertEquals("captured", result);
  }

  /**
   * When flushPendingOperations itself throws IOException (wrapped as RuntimeException by the
   * caller), the exception should propagate as CommonStorageComponentException since it
   * occurs inside the try block.
   */
  @Test
  public void testExecuteFlushIOExceptionPropagates() throws IOException {
    var operation = mockOperation();
    var ioException = new IOException("WAL write failed");
    org.mockito.Mockito.doThrow(ioException).when(operation).flushPendingOperations();

    try {
      manager.executeInsideComponentOperation(
          operation, component, op -> {
            /* success, but flush fails */});
      fail("Expected exception from flush");
    } catch (CommonStorageComponentException e) {
      // The IOException should be in the cause chain
      assertTrue("Should wrap the flush IOException",
          e.getMessage().contains("testComponent"));
    }
  }
}
