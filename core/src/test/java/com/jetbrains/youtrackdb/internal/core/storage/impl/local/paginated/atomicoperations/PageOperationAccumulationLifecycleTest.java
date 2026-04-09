package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end integration tests for the full PageOperation accumulation lifecycle:
 * register → flush → commit, including WALRecordsFactory serialization roundtrip
 * to verify records survive the full write/read pipeline.
 */
public class PageOperationAccumulationLifecycleTest {

  private static final int STORAGE_ID = 1;
  private static final int PAGE_SIZE = DurablePage.MAX_PAGE_SIZE_BYTES;

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

    WALRecordsFactory.INSTANCE.registerNewRecord(
        TestPageOperation.TEST_RECORD_ID, TestPageOperation.class);

    when(wal.log(any(WriteableWALRecord.class))).thenAnswer(invocation -> {
      var record = invocation.getArgument(0, WriteableWALRecord.class);
      loggedRecords.add(record);
      var lsn = new LogSequenceNumber(0, lsnCounter.getAndIncrement());
      record.setLsn(lsn);
      return lsn;
    });

    when(wal.logAtomicOperationStartRecord(anyBoolean(), anyLong()))
        .thenAnswer(invocation -> {
          loggedRecords.add(null);
          return new LogSequenceNumber(0, lsnCounter.getAndIncrement());
        });

    when(wal.end()).thenAnswer(inv -> new LogSequenceNumber(0, lsnCounter.get()));
  }

  private AtomicOperationBinaryTracking createOperation() {
    var snapshot =
        new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100);
    var op = new AtomicOperationBinaryTracking(
        readCache, writeCache, wal, STORAGE_ID,
        snapshot,
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong(),
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong());
    // Production lifecycle: startToApplyOperations is always called before
    // component operations (and thus before flushPendingOperations)
    op.startToApplyOperations(42);
    return op;
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

  private void mockAllocateNewPage(long fileId, int pageIndex) throws IOException {
    var buffer = ByteBuffer.allocateDirect(PAGE_SIZE).order(ByteOrder.nativeOrder());
    var cachePointer =
        mock(com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer.class);
    when(cachePointer.getBuffer()).thenReturn(buffer);

    var cacheEntry = mock(com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry.class);
    when(cacheEntry.getPageIndex()).thenReturn(pageIndex);
    when(cacheEntry.getFileId()).thenReturn(fileId);
    when(cacheEntry.getCachePointer()).thenReturn(cachePointer);

    when(readCache.allocateNewPage(anyLong(), any(), any()))
        .thenReturn(cacheEntry);
  }

  /**
   * Full lifecycle: register → flush → verify WAL contains AtomicUnitStartRecord +
   * PageOperation. Deserialize the PageOperation through WALRecordsFactory and verify
   * all fields survive the roundtrip.
   */
  @Test
  public void testRegisterFlushAndDeserializeThroughFactory() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPage(op, "test.dat");

    var initialLsn = new LogSequenceNumber(5, 200);
    var pageOp = new TestPageOperation(0, fileId, 42, initialLsn, 99);
    op.registerPageOperation(fileId, 0, pageOp);

    op.flushPendingOperations();

    // Verify WAL records: start + TestPageOperation
    Assert.assertEquals(2, loggedRecords.size());
    Assert.assertNull(loggedRecords.get(0)); // start placeholder
    Assert.assertTrue(loggedRecords.get(1) instanceof TestPageOperation);

    // Serialize through WALRecordsFactory and deserialize back
    var original = (TestPageOperation) loggedRecords.get(1);
    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = (TestPageOperation) WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original.getTestValue(), deserialized.getTestValue());
  }

  /**
   * Multi-component-operation simulation: register → flush → register more on same
   * and different pages → flush again → verify all records in WAL in correct order.
   */
  @Test
  public void testMultiFlushAccumulationOrder() throws IOException {
    var op = createOperation();

    long nextId = fileIdCounter.getAndIncrement();
    long fullFileId = composeFileId(nextId, STORAGE_ID);
    when(writeCache.bookFileId("test.dat")).thenReturn(fullFileId);
    long fileId = op.addFile("test.dat");

    // Add two pages
    var page0 = op.addPage(fileId);
    page0.getChanges().setByteValue(null, (byte) 1, 100);
    page0.setInitialLSN(new LogSequenceNumber(-1, -1));
    var page1 = op.addPage(fileId);
    page1.getChanges().setByteValue(null, (byte) 1, 100);
    page1.setInitialLSN(new LogSequenceNumber(-1, -1));

    // First component operation: ops on page 0
    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 10));
    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 20));
    op.flushPendingOperations();

    // Second component operation: ops on page 0 and page 1
    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 30));
    op.registerPageOperation(fileId, 1,
        new TestPageOperation(1, fileId, 0, new LogSequenceNumber(0, 0), 40));
    op.flushPendingOperations();

    // Verify: 1 start + 4 page operations = 5 records
    Assert.assertEquals(5, loggedRecords.size());
    Assert.assertNull(loggedRecords.get(0)); // single start

    var pageOps = loggedRecords.stream()
        .filter(r -> r instanceof TestPageOperation)
        .map(r -> (TestPageOperation) r)
        .toList();
    Assert.assertEquals(4, pageOps.size());

    // First flush: ops 10 and 20 on page 0
    Assert.assertEquals(10, pageOps.get(0).getTestValue());
    Assert.assertEquals(20, pageOps.get(1).getTestValue());
    // Second flush: op 30 on page 0, op 40 on page 1
    Assert.assertEquals(30, pageOps.get(2).getTestValue());
    Assert.assertEquals(40, pageOps.get(3).getTestValue());
  }

  /**
   * Verify that flushed ops' changeLSNs are captured on CacheEntryChanges.
   * After flush, the changeLSN should reflect the LAST flushed operation's LSN
   * (not the first), since each op overwrites it.
   */
  @Test
  public void testChangeLSNCapturedAfterFlush() throws IOException {
    var op = createOperation();

    // Set up file and page manually to capture the CacheEntryChanges
    long nextInternalId = fileIdCounter.getAndIncrement();
    long fullFileId = composeFileId(nextInternalId, STORAGE_ID);
    when(writeCache.bookFileId("test.dat")).thenReturn(fullFileId);
    long fileId = op.addFile("test.dat");
    var page = (CacheEntryChanges) op.addPage(fileId);
    page.getChanges().setByteValue(null, (byte) 1, 100);
    page.setInitialLSN(new LogSequenceNumber(-1, -1));

    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 10));
    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 20));

    op.flushPendingOperations();

    // The changeLSN on CacheEntryChanges must equal the LAST flushed op's LSN
    var firstOp = (TestPageOperation) loggedRecords.get(1);
    var lastOp = (TestPageOperation) loggedRecords.get(2);
    Assert.assertNotNull(lastOp.getLsn());

    // Both ops have distinct LSNs (mock counter increments)
    Assert.assertNotEquals(firstOp.getLsn(), lastOp.getLsn());

    // changeLSN must be the last op's LSN, not the first
    Assert.assertEquals(lastOp.getLsn(), page.getChangeLSN());
  }

  /**
   * Verify that flushPendingOperations is safe to call when no pending ops
   * exist — no WAL records emitted, no exceptions.
   */
  @Test
  public void testEmptyFlushSafe() throws IOException {
    var op = createOperation();
    setupNewFileWithPage(op, "test.dat");

    // No registerPageOperation calls
    op.flushPendingOperations();

    Assert.assertTrue(loggedRecords.isEmpty());

    // Multiple empty flushes are also safe
    op.flushPendingOperations();
    op.flushPendingOperations();

    Assert.assertTrue(loggedRecords.isEmpty());
  }

  /**
   * Full lifecycle: flush logical records, then commitChanges. The converted page
   * (with changeLSN set by flush) is skipped in commitChanges — no UpdatePageRecord
   * is created for it. Only the logical PageOperation (from flush) and the
   * AtomicUnitEndRecord (from commit) appear.
   */
  @Test
  public void testFlushThenCommitSkipsUpdatePageRecordForConvertedPage() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPage(op, "test.dat");

    // Register a logical page operation
    op.registerPageOperation(fileId, 0,
        new TestPageOperation(0, fileId, 0, new LogSequenceNumber(0, 0), 42));

    // Flush the logical operation — sets changeLSN on the page
    op.flushPendingOperations();

    // Commit — should skip UpdatePageRecord for the converted page (changeLSN != null)
    mockAllocateNewPage(fileId, 0);
    var txEndLsn = op.commitChanges(42L, wal);
    Assert.assertNotNull(txEndLsn);

    // Records should be: start, TestPageOperation (flushed), FileCreatedWALRecord,
    // AtomicUnitEndRecord — no UpdatePageRecord for the converted page
    var testOps = loggedRecords.stream()
        .filter(r -> r instanceof TestPageOperation)
        .count();
    Assert.assertEquals(1, testOps);

    var updatePageRecords = loggedRecords.stream()
        .filter(r -> r instanceof UpdatePageRecord)
        .count();
    Assert.assertEquals(
        "Converted page should not produce UpdatePageRecord", 0, updatePageRecords);

    var endRecords = loggedRecords.stream()
        .filter(r -> r instanceof AtomicUnitEndRecord)
        .count();
    Assert.assertEquals(1, endRecords);
  }
}
