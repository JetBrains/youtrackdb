package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
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

    when(wal.end()).thenAnswer(inv -> new LogSequenceNumber(0, lsnCounter.get()));
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

    var ndCacheEntry = createCacheEntryWithBuffer(fileId, 0);
    when(readCache.allocateNewPage(eq(fileId), any(), any()))
        .thenReturn(ndCacheEntry);

    var result = op.commitChanges(42L, wal);

    // No WAL records at all — not even a start record
    assertThat(loggedRecords).isEmpty();
    assertThat(result).isNull();
    verify(wal, never()).logAtomicOperationStartRecord(anyBoolean(), anyLong());
    verify(wal, never()).log(any());

    // Cache application still happens for non-durable pages
    verify(readCache).allocateNewPage(eq(fileId), any(), isNull());
    // endLSN must NOT be set on non-durable cache entries
    verify(ndCacheEntry, never()).setEndLSN(any());
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
    // commitChanges() must return the LSN of the AtomicUnitEndRecord
    assertThat(result).isEqualTo(loggedRecords.get(3).getLsn());
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
    // commitChanges() must return the LSN of the AtomicUnitEndRecord
    assertThat(result).isEqualTo(loggedRecords.get(3).getLsn());
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

    var result = op.commitChanges(42L, wal);

    // No FileDeletedWALRecord for the non-durable file
    var deletedRecords = loggedRecords.stream()
        .filter(r -> r instanceof FileDeletedWALRecord)
        .toList();
    assertThat(deletedRecords).isEmpty();
    // Total: start + FileCreated(durable) + UpdatePage(durable) + End
    assertThat(loggedRecords).hasSize(4);
    // Durable part still produces a valid end LSN
    assertThat(result).isEqualTo(loggedRecords.get(3).getLsn());
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
    // Cache application still happens for both non-durable files
    verify(readCache).allocateNewPage(eq(fileId1), any(), isNull());
    verify(readCache).allocateNewPage(eq(fileId2), any(), isNull());
  }

  /**
   * Deleting only a non-durable file (no other changes) must produce no WAL
   * records at all: no start, no FileDeletedWALRecord, no end. The cache
   * deletion is still applied.
   */
  @Test
  public void pureNonDurableDeleteProducesNoWALRecords() throws IOException {
    var op = createOperation();

    long ndFileId = composeFileId(10, STORAGE_ID);
    when(writeCache.isNonDurable(ndFileId)).thenReturn(true);
    when(writeCache.fileNameById(ndFileId)).thenReturn("nd-file.dat");

    op.deleteFile(ndFileId);

    var result = op.commitChanges(42L, wal);

    assertThat(loggedRecords).isEmpty();
    assertThat(result).isNull();
    // Cache deletion still applied
    verify(readCache).deleteFile(ndFileId, writeCache);
  }

  /**
   * Non-durable pages must receive null startLSN in cache application and must
   * NOT have endLSN or changeLSN set. This is the defense-in-depth invariant
   * that prevents non-durable pages from entering the dirty pages table or
   * blocking WAL truncation.
   */
  @Test
  public void nonDurablePageDoesNotGetEndLSNOrChangeLSN() throws IOException {
    var op = createOperation();
    long ndFileId = setupNewFileWithPage(op, "nd-file.dat", true);

    var ndCacheEntry = createCacheEntryWithBuffer(ndFileId, 0);
    when(readCache.allocateNewPage(eq(ndFileId), any(), any()))
        .thenReturn(ndCacheEntry);

    op.commitChanges(42L, wal);

    // allocateNewPage for non-durable file must receive null startLSN
    verify(readCache).allocateNewPage(eq(ndFileId), any(), isNull());
    // setEndLSN must NOT be called on non-durable cache entries
    verify(ndCacheEntry, never()).setEndLSN(any());
  }

  /**
   * TB6 — Mixed operation must call setEndLSN(txEndLsn) on the durable cache
   * entry but NOT on the non-durable entry. DurablePage.setLsn(changeLSN) is
   * also guarded by the same condition but writes directly to a ByteBuffer, so
   * it is not verifiable via Mockito — only setEndLSN is checked here.
   */
  @Test
  public void mixedOperationSetsEndLSNOnlyOnDurableCacheEntry()
      throws IOException {
    var op = createOperation();

    long durableFileId =
        setupNewFileWithPage(op, "durable-file.dat", false);
    long ndFileId = setupNewFileWithPage(op, "nd-file.dat", true);

    // Durable cache entry — needs a real buffer for DurablePage.restoreChanges
    var durableCacheEntry = createCacheEntryWithBuffer(durableFileId, 0);
    when(readCache.allocateNewPage(eq(durableFileId), any(), any()))
        .thenReturn(durableCacheEntry);

    // Non-durable cache entry — also needs a real buffer
    var ndCacheEntry = createCacheEntryWithBuffer(ndFileId, 0);
    when(readCache.allocateNewPage(eq(ndFileId), any(), any()))
        .thenReturn(ndCacheEntry);

    var txEndLsn = op.commitChanges(42L, wal);

    // Durable cache entry must have setEndLSN called with the WAL end LSN
    verify(durableCacheEntry).setEndLSN(txEndLsn);
    // Non-durable cache entry must NOT have setEndLSN called
    verify(ndCacheEntry, never()).setEndLSN(any());
  }

  /**
   * TC6 — Truncating a non-durable file must not produce any WAL records
   * (truncate doesn't use FileDeletedWALRecord — it has no WAL record type at
   * all). The cache truncation (readCache.truncateFile) must still be applied.
   */
  @Test
  public void truncateNonDurableFileSkipsWALButAppliesCache()
      throws IOException {
    var op = createOperation();

    // Register a non-durable file as an existing file (not new in this op)
    long ndFileId = composeFileId(10, STORAGE_ID);
    when(writeCache.isNonDurable(ndFileId)).thenReturn(true);
    when(writeCache.loadFile("nd-existing.dat")).thenReturn(ndFileId);
    when(writeCache.getFilledUpTo(ndFileId)).thenReturn(10L);
    op.loadFile("nd-existing.dat");

    // Truncate the non-durable file
    op.truncateFile(ndFileId);

    // Also add a durable change so the operation has something to commit
    long durableFileId =
        setupNewFileWithPage(op, "durable-file.dat", false);
    mockAllocateNewPage(durableFileId, 0);

    op.commitChanges(42L, wal);

    // Truncate has no dedicated WAL record type for any file (durable or not).
    // Verify no WAL records reference the non-durable file at all — neither
    // FileDeletedWALRecord nor UpdatePageRecord (pageChangesMap is cleared by
    // truncateFile()).
    var ndRecords = loggedRecords.stream()
        .filter(r -> r != null)
        .filter(r -> {
          if (r instanceof FileDeletedWALRecord fdr) {
            return fdr.getFileId() == ndFileId;
          }
          if (r instanceof UpdatePageRecord upr) {
            return upr.getFileId() == ndFileId;
          }
          if (r instanceof FileCreatedWALRecord fcr) {
            return fcr.getFileId() == ndFileId;
          }
          return false;
        })
        .toList();
    assertThat(ndRecords).isEmpty();
    // Total: start + FileCreated(durable) + UpdatePage(durable) + End = 4
    assertThat(loggedRecords).hasSize(4);

    // Cache truncation must still be applied
    verify(readCache).truncateFile(ndFileId, writeCache);
  }

  /**
   * TY4 — WAL write-then-replay round-trip: create a mixed operation with both
   * durable and non-durable files, capture the WAL records from commitChanges(),
   * verify only durable-file records are emitted, then feed those records into
   * restoreAtomicUnit() and verify the durable file's page is restored.
   */
  @Test
  public void mixedOperationWALRecordsRoundTripThroughRestore()
      throws Exception {
    // --- Phase 1: Create mixed operation and capture WAL records ---
    var op = createOperation();

    long durableFileId =
        setupNewFileWithPage(op, "durable-file.dat", false);
    long ndFileId = setupNewFileWithPage(op, "nd-file.dat", true);

    mockAllocateNewPage(durableFileId, 0);
    mockAllocateNewPage(ndFileId, 0);

    op.commitChanges(42L, wal);

    // Verify emitted records contain only durable file data
    // start(placeholder=null), FileCreated(durable), UpdatePage(durable), End
    assertThat(loggedRecords).hasSize(4);
    var fileCreated = (FileCreatedWALRecord) loggedRecords.get(1);
    assertThat(fileCreated.getFileId()).isEqualTo(durableFileId);
    var updatePage = (UpdatePageRecord) loggedRecords.get(2);
    assertThat(updatePage.getFileId()).isEqualTo(durableFileId);

    // --- Phase 2: Feed captured records into restoreAtomicUnit ---
    // Build the atomic unit from the real captured records
    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 42));
    atomicUnit.add(fileCreated);
    atomicUnit.add(updatePage);
    atomicUnit.add(loggedRecords.get(3)); // AtomicUnitEndRecord

    // Set up AbstractStorage mock for restoreAtomicUnit — only stubs
    // actually invoked during restore of durable-only WAL records
    var restoreWriteCache = mock(WriteCache.class);
    var restoreReadCache = mock(ReadCache.class);
    int internalDurableId = (int) (durableFileId & 0xFFFFFFFFL);
    when(restoreWriteCache.internalFileId(durableFileId))
        .thenReturn(internalDurableId);
    // exists(fileId) returns true so UpdatePageRecord doesn't try restoreFileById
    when(restoreWriteCache.exists(durableFileId)).thenReturn(true);
    // exists(fileName) returns false to trigger re-creation in FileCreatedWALRecord
    when(restoreWriteCache.exists("durable-file.dat")).thenReturn(false);
    when(restoreWriteCache.externalFileId(internalDurableId))
        .thenReturn(durableFileId);

    // restoreAtomicUnit calls loadForWrite for UpdatePageRecord
    var restoreCacheEntry = createCacheEntryWithBuffer(durableFileId, 0);
    when(restoreReadCache.loadForWrite(
        eq(durableFileId), eq(0L), eq(restoreWriteCache), anyBoolean(), any()))
        .thenReturn(restoreCacheEntry);

    // Create AbstractStorage with CALLS_REAL_METHODS
    var storage = mock(AbstractStorage.class, CALLS_REAL_METHODS);

    // Inject fields via reflection
    setField(storage, "writeCache", restoreWriteCache);
    setField(storage, "readCache", restoreReadCache);
    setField(storage, "name", "testStorage");
    setField(storage, "deletedNonDurableFileIds",
        new IntOpenHashSet());

    var atLeastOnePageUpdate = new ModifiableBoolean();
    // restoreAtomicUnit is protected — invoke via reflection
    var restoreMethod = AbstractStorage.class.getDeclaredMethod(
        "restoreAtomicUnit", List.class, ModifiableBoolean.class);
    restoreMethod.setAccessible(true);
    restoreMethod.invoke(storage, atomicUnit, atLeastOnePageUpdate);

    // Durable file's page must have been loaded for write (= restored)
    verify(restoreReadCache).loadForWrite(
        eq(durableFileId), eq(0L), eq(restoreWriteCache), eq(true), any());
    assertThat(atLeastOnePageUpdate.getValue()).isTrue();

    // Durable file was re-created (exists("durable-file.dat") returns false)
    verify(restoreReadCache).addFile(
        "durable-file.dat", durableFileId, restoreWriteCache);
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

  private static void setField(Object target, String fieldName, Object value)
      throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Field findField(Class<?> clazz, String fieldName) {
    NoSuchFieldException lastException = null;
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        lastException = e;
        clazz = clazz.getSuperclass();
      }
    }
    throw new RuntimeException("Field not found: " + fieldName,
        lastException);
  }
}
