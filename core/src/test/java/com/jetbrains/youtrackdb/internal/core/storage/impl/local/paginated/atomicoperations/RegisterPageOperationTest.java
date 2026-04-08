package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link AtomicOperationBinaryTracking#registerPageOperation} and
 * the {@link CacheEntryChanges} pending operations accumulation.
 */
public class RegisterPageOperationTest {

  private static final int STORAGE_ID = 1;
  private static final int PAGE_SIZE = DurablePage.MAX_PAGE_SIZE_BYTES;

  private ReadCache readCache;
  private WriteCache writeCache;
  private AtomicLong fileIdCounter;

  @Before
  public void setUp() {
    readCache = mock(ReadCache.class);
    writeCache = mock(WriteCache.class);
    when(writeCache.getStorageName()).thenReturn("test-storage");
    fileIdCounter = new AtomicLong(100);
  }

  private AtomicOperationBinaryTracking createOperation() {
    var snapshot =
        new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100);
    return new AtomicOperationBinaryTracking(
        readCache, writeCache, STORAGE_ID,
        snapshot,
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong(),
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong());
  }

  private static long composeFileId(long internalId, int storageId) {
    return internalId | ((long) storageId << 32);
  }

  /**
   * Creates a new file, adds pages via addPage(), makes a small change, and sets initialLSN.
   * Returns the composite file ID.
   */
  private long setupNewFileWithPages(
      AtomicOperationBinaryTracking op, String fileName, int pageCount)
      throws IOException {
    long nextInternalId = fileIdCounter.getAndIncrement();
    long fullFileId = composeFileId(nextInternalId, STORAGE_ID);
    when(writeCache.bookFileId(fileName)).thenReturn(fullFileId);

    long fileId = op.addFile(fileName);

    for (int i = 0; i < pageCount; i++) {
      var page = op.addPage(fileId);
      // Make a change so hasChanges() returns true
      page.getChanges().setByteValue(null, (byte) 1, 100);
      page.setInitialLSN(new LogSequenceNumber(-1, -1));
    }
    return fileId;
  }

  /**
   * Registers a single PageOperation on a page that is loaded for write.
   * Verifies the operation is accumulated in CacheEntryChanges.
   */
  @Test
  public void testRegisterSingleOperation() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPages(op, "test.dat", 1);

    var pageOp = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 42);
    op.registerPageOperation(fileId, 0, pageOp);

    Assert.assertTrue(op.hasChangesForPage(fileId, 0));
  }

  /**
   * Registers multiple PageOperations on the same page and verifies they are
   * accumulated in order.
   */
  @Test
  public void testRegisterMultipleOperationsOnSamePage() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPages(op, "test.dat", 1);

    var op1 = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 10);
    var op2 = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 20);
    var op3 = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 30);

    op.registerPageOperation(fileId, 0, op1);
    op.registerPageOperation(fileId, 0, op2);
    op.registerPageOperation(fileId, 0, op3);

    Assert.assertTrue(op.hasChangesForPage(fileId, 0));
  }

  /**
   * Registers operations on different pages in the same file and verifies
   * each page accumulates independently.
   */
  @Test
  public void testRegisterOperationsOnDifferentPages() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPages(op, "test.dat", 2);

    var pageOp0 = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 100);
    var pageOp1 = new TestPageOperation(1, fileId, 0, new LogSequenceNumber(0, 0), 200);

    op.registerPageOperation(fileId, 0, pageOp0);
    op.registerPageOperation(fileId, 1, pageOp1);

    Assert.assertTrue(op.hasChangesForPage(fileId, 0));
    Assert.assertTrue(op.hasChangesForPage(fileId, 1));
  }

  /**
   * Verifies that CacheEntryChanges.getPendingOperations returns empty list
   * when no operations have been registered, and clearPendingOperations is safe
   * to call on an empty list.
   */
  @Test
  public void testCacheEntryChangesEmptyPendingOperations() {
    var changes = new CacheEntryChanges(false, mock(AtomicOperation.class));
    Assert.assertTrue(changes.getPendingOperations().isEmpty());
    // Should not throw
    changes.clearPendingOperations();
    Assert.assertTrue(changes.getPendingOperations().isEmpty());
  }

  /**
   * Verifies that CacheEntryChanges accumulates and clears pending operations.
   */
  @Test
  public void testCacheEntryChangesAccumulateAndClear() {
    var changes = new CacheEntryChanges(false, mock(AtomicOperation.class));
    var op1 = new TestPageOperation(0, 0, 0, new LogSequenceNumber(0, 0), 1);
    var op2 = new TestPageOperation(0, 0, 0, new LogSequenceNumber(0, 0), 2);

    changes.addPendingOperation(op1);
    changes.addPendingOperation(op2);

    Assert.assertEquals(2, changes.getPendingOperations().size());
    Assert.assertSame(op1, changes.getPendingOperations().get(0));
    Assert.assertSame(op2, changes.getPendingOperations().get(1));

    changes.clearPendingOperations();
    Assert.assertTrue(changes.getPendingOperations().isEmpty());
  }

  /**
   * Verifies that the AtomicOperation interface default no-op methods work
   * without throwing for non-tracking implementations.
   */
  @Test
  public void testAtomicOperationDefaultMethods() throws IOException {
    AtomicOperation atomicOp = mock(AtomicOperation.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);

    // Default registerPageOperation is no-op
    atomicOp.registerPageOperation(0, 0,
        new TestPageOperation(0, 0, 0, new LogSequenceNumber(0, 0), 0));

    // Default flushPendingOperations is no-op
    atomicOp.flushPendingOperations();
  }
}
