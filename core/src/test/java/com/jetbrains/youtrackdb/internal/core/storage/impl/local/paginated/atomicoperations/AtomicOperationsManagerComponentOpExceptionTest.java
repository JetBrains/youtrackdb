package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.CommonStorageComponentException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that {@link AtomicOperationsManager#executeInsideComponentOperation} and
 * {@link AtomicOperationsManager#calculateInsideComponentOperation} correctly propagate
 * componentName and dbName in the wrapping {@link CommonStorageComponentException}
 * when the delegate throws.
 */
public class AtomicOperationsManagerComponentOpExceptionTest {

  private static final String STORAGE_NAME = "testStorage";
  private static final String COMPONENT_LOCK_NAME = "testComponent";

  private AtomicOperationsManager manager;
  private AtomicOperation operation;
  private StorageComponent component;

  @Before
  public void setUp() {
    var storage = mock(AbstractStorage.class);
    when(storage.getName()).thenReturn(STORAGE_NAME);
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(mock(AtomicOperationIdGen.class));

    var table = mock(AtomicOperationsTable.class);
    manager = new AtomicOperationsManager(storage, table);

    operation = mock(AtomicOperation.class);
    // Pretend the lock is already held so acquireExclusiveLockTillOperationComplete
    // skips the actual locking.
    when(operation.containsInLockedObjects(anyString())).thenReturn(true);

    component = mock(StorageComponent.class);
    when(component.getLockName()).thenReturn(COMPONENT_LOCK_NAME);
  }

  /**
   * When executeInsideComponentOperation's consumer throws, the wrapping
   * CommonStorageComponentException must have dbName=storageName and
   * componentName=componentLockName (not swapped).
   */
  @Test
  public void testExecuteInsideComponentOperationSetsCorrectExceptionFields() {
    try {
      manager.executeInsideComponentOperation(
          operation,
          component,
          op -> {
            throw new RuntimeException("test failure");
          });
      fail("Expected exception to be thrown");
    } catch (CommonStorageComponentException e) {
      assertExceptionFieldsCorrect(e);
    }
  }

  /**
   * When calculateInsideComponentOperation's function throws, the wrapping
   * CommonStorageComponentException must have dbName=storageName and
   * componentName=componentLockName (not swapped).
   */
  @Test
  public void testCalculateInsideComponentOperationSetsCorrectExceptionFields() {
    try {
      manager.calculateInsideComponentOperation(
          operation,
          component,
          op -> {
            throw new RuntimeException("test failure");
          });
      fail("Expected exception to be thrown");
    } catch (CommonStorageComponentException e) {
      assertExceptionFieldsCorrect(e);
    }
  }

  /**
   * Verifies that the exception carries the correct dbName and componentName fields.
   * Both values must be distinct (STORAGE_NAME != COMPONENT_LOCK_NAME) so that
   * a parameter-swap mutation is detectable.
   */
  private static void assertExceptionFieldsCorrect(CommonStorageComponentException e) {
    assertEquals(STORAGE_NAME, e.getDbName());
    // getMessage() includes both DB Name and Component Name labels
    assertTrue(
        "Exception message should contain Component Name=\"" + COMPONENT_LOCK_NAME + "\"",
        e.getMessage().contains("Component Name=\"" + COMPONENT_LOCK_NAME + "\""));
    assertTrue(
        "Exception message should contain DB Name=\"" + STORAGE_NAME + "\"",
        e.getMessage().contains("DB Name=\"" + STORAGE_NAME + "\""));
  }
}
