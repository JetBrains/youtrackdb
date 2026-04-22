package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
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
        readCache, writeCache, null, STORAGE_ID,
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

    long fileId = op.addFile(fileName).fileId();

    for (int i = 0; i < pageCount; i++) {
      var page = op.addPage(new FileHandler(fileId));
      // Make a change so hasChanges() returns true
      page.getChanges().setByteValue(null, (byte) 1, 100);
      page.setInitialLSN(new LogSequenceNumber(-1, -1));
    }
    return fileId;
  }

  /**
   * Registers a single PageOperation on a page that is loaded for write.
   * Verifies the operation is accumulated in CacheEntryChanges' pendingOperations list.
   */
  @Test
  public void testRegisterSingleOperation() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPages(op, "test.dat", 1);

    var pageOp = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 42);
    op.registerPageOperation(fileId, 0, pageOp);

    // Verify the operation was accumulated in CacheEntryChanges
    var page = (CacheEntryChanges) op.loadPageForWrite(new FileHandler(fileId), 0, 1, false);
    var pending = page.getPendingOperations();
    Assert.assertEquals(1, pending.size());
    Assert.assertSame(pageOp, pending.get(0));
    Assert.assertEquals(42, ((TestPageOperation) pending.get(0)).getTestValue());
    op.releasePageFromWrite(page);
  }

  /**
   * Registers multiple PageOperations on the same page and verifies they are
   * accumulated in insertion order in the pendingOperations list.
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

    // Verify operations are accumulated in order
    var page = (CacheEntryChanges) op.loadPageForWrite(new FileHandler(fileId), 0, 1, false);
    var pending = page.getPendingOperations();
    Assert.assertEquals(3, pending.size());
    Assert.assertSame(op1, pending.get(0));
    Assert.assertSame(op2, pending.get(1));
    Assert.assertSame(op3, pending.get(2));
    op.releasePageFromWrite(page);
  }

  /**
   * Registers operations on different pages in the same file and verifies
   * each page accumulates its own operations independently.
   */
  @Test
  public void testRegisterOperationsOnDifferentPages() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPages(op, "test.dat", 2);

    var pageOp0 = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 100);
    var pageOp1 = new TestPageOperation(1, fileId, 0, new LogSequenceNumber(0, 0), 200);

    op.registerPageOperation(fileId, 0, pageOp0);
    op.registerPageOperation(fileId, 1, pageOp1);

    // Verify each page has its own pending operation
    var page0 = (CacheEntryChanges) op.loadPageForWrite(new FileHandler(fileId), 0, 2, false);
    Assert.assertEquals(1, page0.getPendingOperations().size());
    Assert.assertSame(pageOp0, page0.getPendingOperations().get(0));
    op.releasePageFromWrite(page0);

    var page1 = (CacheEntryChanges) op.loadPageForWrite(new FileHandler(fileId), 1, 2, false);
    Assert.assertEquals(1, page1.getPendingOperations().size());
    Assert.assertSame(pageOp1, page1.getPendingOperations().get(0));
    op.releasePageFromWrite(page1);
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
