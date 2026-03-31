package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
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
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that commitChanges() skips WAL records for non-durable files:
 * (a) pure non-durable operations produce no WAL records at all,
 * (b) mixed operations produce WAL records only for the durable subset,
 * (c) durable-only operations are unchanged (full WAL unit).
 */
public class AtomicOperationBinaryTrackingWALSkipTest {

  private static final int STORAGE_ID = 1;
  private static final int PAGE_SIZE =
      DurablePage.MAX_PAGE_SIZE_BYTES;

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

    // Capture all WAL records logged via wal.log() and set the LSN on
    // the record (as the real WriteAheadLog implementation does).
    when(wal.log(any(WriteableWALRecord.class))).thenAnswer(invocation -> {
      var record = invocation.getArgument(0, WriteableWALRecord.class);
      loggedRecords.add(record);
      var lsn = new LogSequenceNumber(0, lsnCounter.getAndIncrement());
      record.setLsn(lsn);
      return lsn;
    });

    // Capture start record
    when(wal.logAtomicOperationStartRecord(anyBoolean(), anyLong()))
        .thenAnswer(invocation -> {
          loggedRecords.add(null); // placeholder for start record
          return new LogSequenceNumber(0, lsnCounter.getAndIncrement());
        });

    when(wal.end()).thenReturn(new LogSequenceNumber(0, lsnCounter.get()));
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

  /**
   * A pure non-durable operation (only non-durable file creation + page changes)
   * must not produce any WAL records: no start, no FileCreatedWALRecord,
   * no UpdatePageRecord, no end. Returns null txEndLsn.
   */
  @Test
  public void pureNonDurableOperationProducesNoWALRecords()
      throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPage(op, "nd-file.dat", true);

    mockAllocateNewPage(fileId, 0);

    var result = op.commitChanges(42L, wal);

    assertThat(loggedRecords).isEmpty();
    assertThat(result).isNull();
  }

  /**
   * A durable-only operation must produce the full WAL unit: start record,
   * FileCreatedWALRecord, UpdatePageRecord, and AtomicUnitEndRecord.
   */
  @Test
  public void durableOnlyOperationProducesFullWALUnit() throws IOException {
    var op = createOperation();
    long fileId = setupNewFileWithPage(op, "durable-file.dat", false);

    mockAllocateNewPage(fileId, 0);

    var result = op.commitChanges(42L, wal);

    // start placeholder, FileCreated, UpdatePage, AtomicUnitEnd
    assertThat(loggedRecords).hasSize(4);
    assertThat(loggedRecords.get(0)).isNull(); // start record placeholder
    assertThat(loggedRecords.get(1)).isInstanceOf(FileCreatedWALRecord.class);
    assertThat(loggedRecords.get(2)).isInstanceOf(UpdatePageRecord.class);
    assertThat(loggedRecords.get(3)).isInstanceOf(AtomicUnitEndRecord.class);
    assertThat(result).isNotNull();
  }

  /**
   * A mixed operation with both durable and non-durable files must produce
   * WAL records only for the durable file. The non-durable file's
   * FileCreatedWALRecord and UpdatePageRecord must be skipped.
   */
  @Test
  public void mixedOperationProducesWALRecordsOnlyForDurableFiles()
      throws IOException {
    var op = createOperation();

    long durableFileId =
        setupNewFileWithPage(op, "durable-file.dat", false);
    long ndFileId = setupNewFileWithPage(op, "nd-file.dat", true);

    mockAllocateNewPage(durableFileId, 0);
    mockAllocateNewPage(ndFileId, 0);

    var result = op.commitChanges(42L, wal);

    // start, FileCreated(durable), UpdatePage(durable), AtomicUnitEnd
    assertThat(loggedRecords).hasSize(4);
    assertThat(loggedRecords.get(0)).isNull();
    assertThat(loggedRecords.get(1)).isInstanceOf(FileCreatedWALRecord.class);
    assertThat(((FileCreatedWALRecord) loggedRecords.get(1)).getFileId())
        .isEqualTo(durableFileId);
    assertThat(loggedRecords.get(2)).isInstanceOf(UpdatePageRecord.class);
    assertThat(((UpdatePageRecord) loggedRecords.get(2)).getFileId())
        .isEqualTo(durableFileId);
    assertThat(loggedRecords.get(3)).isInstanceOf(AtomicUnitEndRecord.class);
    assertThat(result).isNotNull();
  }

  /**
   * Deleting a non-durable file must not produce a FileDeletedWALRecord.
   * The deletion is still applied to the cache.
   */
  @Test
  public void deleteNonDurableFileSkipsWALRecord() throws IOException {
    var op = createOperation();

    long existingFileId = composeFileId(10, STORAGE_ID);
    when(writeCache.isNonDurable(existingFileId)).thenReturn(true);
    when(writeCache.fileNameById(existingFileId))
        .thenReturn("nd-existing.dat");

    // Add a durable file change so the WAL unit gets started
    long durableFileId =
        setupNewFileWithPage(op, "durable-file.dat", false);

    // Delete the non-durable file
    op.deleteFile(existingFileId);

    mockAllocateNewPage(durableFileId, 0);

    op.commitChanges(42L, wal);

    // No FileDeletedWALRecord for the non-durable file
    var deletedRecords = loggedRecords.stream()
        .filter(r -> r instanceof FileDeletedWALRecord)
        .toList();
    assertThat(deletedRecords).isEmpty();
    // Total: start + FileCreated(durable) + UpdatePage(durable) + End
    assertThat(loggedRecords).hasSize(4);
    // Cache deletion still applied for the non-durable file
    verify(readCache).deleteFile(existingFileId, writeCache);
  }

  /**
   * Deleting a durable file produces a FileDeletedWALRecord as before.
   */
  @Test
  public void deleteDurableFileProducesWALRecord() throws IOException {
    var op = createOperation();

    long existingFileId = composeFileId(10, STORAGE_ID);
    when(writeCache.isNonDurable(existingFileId)).thenReturn(false);
    when(writeCache.fileNameById(existingFileId))
        .thenReturn("durable-existing.dat");

    op.deleteFile(existingFileId);

    op.commitChanges(42L, wal);

    var deletedRecords = loggedRecords.stream()
        .filter(r -> r instanceof FileDeletedWALRecord)
        .toList();
    assertThat(deletedRecords).hasSize(1);
    assertThat(((FileDeletedWALRecord) deletedRecords.get(0)).getFileId())
        .isEqualTo(existingFileId);
  }

  /**
   * An existing non-durable file loaded via loadFile() and modified must not
   * produce UpdatePageRecord WAL records. writeCache.isNonDurable() is used
   * to detect non-durability for files not created in this operation.
   */
  @Test
  public void existingNonDurableFileLoadedViaLoadFileSkipsWAL()
      throws IOException {
    var op = createOperation();

    long internalFileId = 20;
    long fullFileId = composeFileId(internalFileId, STORAGE_ID);
    when(writeCache.loadFile("nd-existing.dat")).thenReturn(fullFileId);
    when(writeCache.isNonDurable(fullFileId)).thenReturn(true);
    when(writeCache.getFilledUpTo(fullFileId)).thenReturn(10L);

    // Load the existing file
    op.loadFile("nd-existing.dat");

    // Load a page for write (existing file, not new)
    var mockDelegate = createCacheEntryWithBuffer(fullFileId, 0);
    when(readCache.loadForRead(fullFileId, 0, writeCache, true))
        .thenReturn(mockDelegate);

    var pageEntry = op.loadPageForWrite(fullFileId, 0, 1, true);
    assertThat(pageEntry).isNotNull();

    // Make a change so hasChanges() returns true
    pageEntry.getChanges().setByteValue(null, (byte) 1, 100);
    // Set initialLSN (normally done by DurablePage constructor)
    pageEntry.setInitialLSN(new LogSequenceNumber(-1, -1));

    // Also add a durable file so the operation isn't empty
    long durableFileId =
        setupNewFileWithPage(op, "durable-file.dat", false);
    mockAllocateNewPage(durableFileId, 0);

    // Mock cache application for the existing non-durable file's page
    var ndCacheEntry = createCacheEntryWithBuffer(fullFileId, 0);
    when(readCache.loadForWrite(
        anyLong(), anyLong(), any(), anyBoolean(), any()))
        .thenAnswer(invocation -> {
          long fId = invocation.getArgument(0);
          if (fId == fullFileId) {
            return ndCacheEntry;
          }
          return null;
        });

    op.commitChanges(42L, wal);

    // Verify no UpdatePageRecord for the non-durable file
    var updateRecords = loggedRecords.stream()
        .filter(r -> r instanceof UpdatePageRecord)
        .map(r -> (UpdatePageRecord) r)
        .toList();
    assertThat(updateRecords).hasSize(1);
    assertThat(updateRecords.get(0).getFileId()).isEqualTo(durableFileId);
  }

  /**
   * Multiple non-durable file creations with no durable changes produce
   * zero WAL records and return null txEndLsn.
   */
  @Test
  public void multipleNonDurableFilesProduceNoWAL() throws IOException {
    var op = createOperation();

    long fileId1 = setupNewFileWithPage(op, "nd-file1.dat", true);
    long fileId2 = setupNewFileWithPage(op, "nd-file2.dat", true);

    mockAllocateNewPage(fileId1, 0);
    mockAllocateNewPage(fileId2, 0);

    var result = op.commitChanges(42L, wal);

    assertThat(loggedRecords).isEmpty();
    assertThat(result).isNull();
  }

  // --- Helper methods ---

  /**
   * Creates a new file in the operation, adds a page, makes a change,
   * and sets the initial LSN. Returns the composite file ID.
   */
  private long setupNewFileWithPage(
      AtomicOperationBinaryTracking op,
      String fileName,
      boolean nonDurable)
      throws IOException {
    long nextInternalId = fileIdCounter.getAndIncrement();
    long fullFileId = composeFileId(nextInternalId, STORAGE_ID);
    when(writeCache.bookFileId(fileName)).thenReturn(fullFileId);

    long fileId = op.addFile(fileName, nonDurable);

    // Add a page to the new file
    var page = op.addPage(fileId);

    // Make a change so hasChanges() returns true
    page.getChanges().setByteValue(null, (byte) 1, 100);

    // Set initialLSN (normally done by DurablePage constructor;
    // for new pages with null buffer it's (-1,-1))
    page.setInitialLSN(new LogSequenceNumber(-1, -1));

    return fileId;
  }

  /**
   * Mocks readCache.allocateNewPage() to return a CacheEntry with a real
   * direct ByteBuffer at the expected page index. Uses eq(fileId) to avoid
   * overwriting stubs when called for multiple files.
   */
  private void mockAllocateNewPage(long fileId, int pageIndex)
      throws IOException {
    var cacheEntry = createCacheEntryWithBuffer(fileId, pageIndex);
    when(readCache.allocateNewPage(eq(fileId), any(), any()))
        .thenReturn(cacheEntry);
  }

  /**
   * Creates a mock CacheEntry backed by a real direct ByteBuffer so that
   * DurablePage.restoreChanges() and setLsn() work correctly.
   */
  private CacheEntry createCacheEntryWithBuffer(long fileId, int pageIndex) {
    var buffer =
        ByteBuffer.allocateDirect(PAGE_SIZE).order(ByteOrder.nativeOrder());
    var cachePointer = mock(CachePointer.class);
    when(cachePointer.getBuffer()).thenReturn(buffer);

    var cacheEntry = mock(CacheEntry.class);
    when(cacheEntry.getPageIndex()).thenReturn(pageIndex);
    when(cacheEntry.getFileId()).thenReturn(fileId);
    when(cacheEntry.getCachePointer()).thenReturn(cachePointer);
    return cacheEntry;
  }

  private static long composeFileId(long fileId, int storageId) {
    return (((long) storageId) << 32) | fileId;
  }
}
