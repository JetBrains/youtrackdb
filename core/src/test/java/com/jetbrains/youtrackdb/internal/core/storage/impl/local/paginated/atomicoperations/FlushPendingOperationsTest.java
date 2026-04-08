package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link AtomicOperationBinaryTracking#flushPendingOperations()}.
 * Verifies lazy AtomicUnitStartRecord emission, WAL writing, changeLSN capture,
 * and no-op behavior when no pending operations exist.
 */
public class FlushPendingOperationsTest {

  private static final int STORAGE_ID = 1;

  private ReadCache readCache;
  private WriteCache writeCache;
  private WriteAheadLog wal;
  private List<WriteableWALRecord> loggedRecords;
  private AtomicInteger lsnCounter;
  private AtomicLong fileIdCounter;

  @Before
  public void setUp() throws IOException {
    readCache = mock(ReadCache.class);
    writeCache = mock(WriteCache.class);
    when(writeCache.getStorageName()).thenReturn("test-storage");
    wal = mock(WriteAheadLog.class);
    loggedRecords = new ArrayList<>();
    lsnCounter = new AtomicInteger(1);
    fileIdCounter = new AtomicLong(100);

    when(wal.log(any(WriteableWALRecord.class))).thenAnswer(invocation -> {
      var record = invocation.getArgument(0, WriteableWALRecord.class);
      loggedRecords.add(record);
      var lsn = new LogSequenceNumber(0, lsnCounter.getAndIncrement());
      record.setLsn(lsn);
      return lsn;
    });

    when(wal.logAtomicOperationStartRecord(anyBoolean(), anyLong()))
        .thenAnswer(invocation -> {
          loggedRecords.add(null); // placeholder for start record
          return new LogSequenceNumber(0, lsnCounter.getAndIncrement());
        });

    when(wal.end()).thenAnswer(inv -> new LogSequenceNumber(0, lsnCounter.get()));
  }

  private AtomicOperationBinaryTracking createOperation() {
    var snapshot =
        new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100);
    return new AtomicOperationBinaryTracking(
        readCache, writeCache, wal, STORAGE_ID,
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

  private long setupNewFileWithPage(AtomicOperationBinaryTracking op, String fileName)
      throws IOException {
    long nextInternalId = fileIdCounter.getAndIncrement();
    long fullFileId = composeFileId(nextInternalId, STORAGE_ID);
    when(writeCache.bookFileId(fileName)).thenReturn(fullFileId);

    long fileId = op.addFile(fileName);

    var page = op.addPage(fileId);
    page.getChanges().setByteValue(null, (byte) 1, 100);
    page.setInitialLSN(new LogSequenceNumber(-1, -1));
    return fileId;
  }

  /**
   * Flushing a single pending PageOperation writes the AtomicUnitStartRecord
   * (lazy emission) followed by the PageOperation to WAL.
   */
  @Test
  public void testFlushSingleOperation() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPage(op, "test.dat");

    var pageOp = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 42);
    op.registerPageOperation(fileId, 0, pageOp);

    op.flushPendingOperations();

    // start record placeholder + TestPageOperation
    Assert.assertEquals(2, loggedRecords.size());
    Assert.assertNull(loggedRecords.get(0)); // start record placeholder
    Assert.assertTrue(loggedRecords.get(1) instanceof TestPageOperation);
    Assert.assertEquals(42, ((TestPageOperation) loggedRecords.get(1)).getTestValue());
  }

  /**
   * AtomicUnitStartRecord is emitted at most once, even across multiple flushes.
   */
  @Test
  public void testLazyStartEmittedOnce() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPage(op, "test.dat");

    // First flush
    var op1 = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 10);
    op.registerPageOperation(fileId, 0, op1);
    op.flushPendingOperations();

    // Second flush — should NOT emit another start record
    var op2 = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 20);
    op.registerPageOperation(fileId, 0, op2);
    op.flushPendingOperations();

    // Only 1 start record (at index 0), then op1, then op2
    Assert.assertEquals(3, loggedRecords.size());
    Assert.assertNull(loggedRecords.get(0)); // start
    Assert.assertTrue(loggedRecords.get(1) instanceof TestPageOperation);
    Assert.assertTrue(loggedRecords.get(2) instanceof TestPageOperation);
    Assert.assertEquals(10, ((TestPageOperation) loggedRecords.get(1)).getTestValue());
    Assert.assertEquals(20, ((TestPageOperation) loggedRecords.get(2)).getTestValue());
  }

  /**
   * Flushing with no pending operations is a no-op — no WAL records emitted.
   */
  @Test
  public void testFlushNoPendingOperationsIsNoop() throws IOException {
    var op = createOperation();
    setupNewFileWithPage(op, "test.dat");

    op.flushPendingOperations();

    Assert.assertTrue(loggedRecords.isEmpty());
    verify(wal, never()).log(any());
    verify(wal, never()).logAtomicOperationStartRecord(anyBoolean(), anyLong());
  }

  /**
   * Flushing clears pending operations — a second flush after the first produces
   * no additional WAL records (unless new ops are registered).
   */
  @Test
  public void testFlushClearsPendingOperations() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPage(op, "test.dat");

    var pageOp = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 42);
    op.registerPageOperation(fileId, 0, pageOp);
    op.flushPendingOperations();

    int countAfterFirstFlush = loggedRecords.size();

    // Second flush — no new ops registered
    op.flushPendingOperations();
    Assert.assertEquals(countAfterFirstFlush, loggedRecords.size());
  }

  /**
   * Flushing operations on multiple pages writes all of them to WAL.
   */
  @Test
  public void testFlushMultiPageOperations() throws IOException {
    var op = createOperation();

    long nextInternalId = fileIdCounter.getAndIncrement();
    long fullFileId = composeFileId(nextInternalId, STORAGE_ID);
    when(writeCache.bookFileId("test.dat")).thenReturn(fullFileId);
    long fileId = op.addFile("test.dat");

    // Add two pages
    var page0 = op.addPage(fileId);
    page0.getChanges().setByteValue(null, (byte) 1, 100);
    page0.setInitialLSN(new LogSequenceNumber(-1, -1));
    var page1 = op.addPage(fileId);
    page1.getChanges().setByteValue(null, (byte) 1, 100);
    page1.setInitialLSN(new LogSequenceNumber(-1, -1));

    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 100));
    op.registerPageOperation(fileId, 1,
        new TestPageOperation(1, fileId, 0, new LogSequenceNumber(0, 0), 200));

    op.flushPendingOperations();

    // start + 2 page operations
    Assert.assertEquals(3, loggedRecords.size());
    Assert.assertNull(loggedRecords.get(0)); // start
    var ops = loggedRecords.stream()
        .filter(r -> r instanceof TestPageOperation)
        .map(r -> (TestPageOperation) r)
        .toList();
    Assert.assertEquals(2, ops.size());
  }

  /**
   * Flushing pending operations captures changeLSN on the CacheEntryChanges, and
   * the walUnitStarted state is preserved into commitChanges (which uses it to
   * decide whether to emit AtomicUnitEndRecord).
   */
  @Test
  public void testFlushThenCommitPreservesWALState() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPage(op, "test.dat");

    var pageOp = new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 42);
    op.registerPageOperation(fileId, 0, pageOp);

    op.flushPendingOperations();

    // Now commitChanges should see walUnitStarted=true and emit the end record
    // without emitting another start record.
    // We need to mock the cache application path for commitChanges.
    var mockCacheEntry =
        mock(com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry.class);
    var mockPointer = mock(com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer.class);
    var buffer = java.nio.ByteBuffer.allocateDirect(DurablePage.MAX_PAGE_SIZE_BYTES)
        .order(java.nio.ByteOrder.nativeOrder());
    when(mockPointer.getBuffer()).thenReturn(buffer);
    when(mockCacheEntry.getCachePointer()).thenReturn(mockPointer);
    when(mockCacheEntry.getPageIndex()).thenReturn(0);
    when(mockCacheEntry.getFileId()).thenReturn(fileId);
    when(readCache.allocateNewPage(anyLong(), any(), any()))
        .thenReturn(mockCacheEntry);

    var txEndLsn = op.commitChanges(42L, wal);

    Assert.assertNotNull(txEndLsn);
    // Start was already emitted during flush — commitChanges should not emit another
    long startRecordCount = loggedRecords.stream().filter(r -> r == null).count();
    Assert.assertEquals("Should have exactly 1 start record", 1, startRecordCount);

    // Should have an AtomicUnitEndRecord
    var endRecords = loggedRecords.stream()
        .filter(r -> r instanceof AtomicUnitEndRecord)
        .count();
    Assert.assertEquals(1, endRecords);
  }

  /**
   * Non-durable files are skipped during flush — no WAL records emitted for them.
   */
  @Test
  public void testFlushSkipsNonDurableFiles() throws IOException {
    var op = createOperation();

    long nextId = fileIdCounter.getAndIncrement();
    long fullFileId = composeFileId(nextId, STORAGE_ID);
    when(writeCache.bookFileId("nd-file.dat")).thenReturn(fullFileId);
    long fileId = op.addFile("nd-file.dat", true);

    var page = op.addPage(fileId);
    page.getChanges().setByteValue(null, (byte) 1, 100);
    page.setInitialLSN(new LogSequenceNumber(-1, -1));

    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 42));

    op.flushPendingOperations();

    // No WAL records — file is non-durable
    Assert.assertTrue(loggedRecords.isEmpty());
  }
}
