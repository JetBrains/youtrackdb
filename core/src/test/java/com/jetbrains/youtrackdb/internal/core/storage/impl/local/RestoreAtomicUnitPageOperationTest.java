package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageAllocatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageBrokenException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests that {@code AbstractStorage.restoreAtomicUnit()} correctly dispatches
 * {@code PageOperation} records: loads the page, checks pageLsn idempotency,
 * calls redo(), updates the page LSN, and releases the cache entry.
 */
public class RestoreAtomicUnitPageOperationTest {

  private static final int PAGE_SIZE = DurablePage.MAX_PAGE_SIZE_BYTES;
  private static final int DURABLE_INTERNAL_ID = 7;
  private static final long DURABLE_EXTERNAL_ID = (1L << 32) | DURABLE_INTERNAL_ID;
  private static final int ND_INTERNAL_ID = 42;
  private static final long ND_EXTERNAL_ID = (1L << 32) | ND_INTERNAL_ID;

  private WriteCache writeCache;
  private ReadCache readCache;
  private AbstractStorage storage;

  @BeforeClass
  public static void registerTestOp() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        TestPageOperation.TEST_RECORD_ID, TestPageOperation.class);
  }

  @Before
  public void setUp() throws Exception {
    writeCache = mock(WriteCache.class);
    readCache = mock(ReadCache.class);

    when(writeCache.internalFileId(DURABLE_EXTERNAL_ID)).thenReturn(DURABLE_INTERNAL_ID);
    when(writeCache.internalFileId(ND_EXTERNAL_ID)).thenReturn(ND_INTERNAL_ID);
    when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(true);
    when(writeCache.externalFileId(DURABLE_INTERNAL_ID)).thenReturn(DURABLE_EXTERNAL_ID);
    when(writeCache.fileNameById(DURABLE_EXTERNAL_ID)).thenReturn("durable.dat");

    storage = Mockito.mock(AbstractStorage.class, Mockito.CALLS_REAL_METHODS);
    setField(storage, "writeCache", writeCache);
    setField(storage, "readCache", readCache);
    setField(storage, "name", "testStorage");
    setField(storage, "deletedNonDurableFileIds", new IntOpenHashSet());
  }

  /** Missing-file operations must skip rather than resurrect an empty, invalid page. */
  @Test
  public void laterCommittedDeletionSkipsPageOperationAndLogs() throws Exception {
    assertLaterDeletionSkipped(0);
  }

  /** Physical page updates use the same missing-file deletion rule as logical operations. */
  @Test
  public void laterCommittedDeletionSkipsPageUpdateAndLogs() throws Exception {
    assertLaterDeletionSkipped(1);
  }

  /** Skipped allocations never enter the preload map or the page recovery context. */
  @Test
  public void laterCommittedDeletionSkipsAllocationAndLogs() throws Exception {
    assertLaterDeletionSkipped(2);
  }

  private void assertLaterDeletionSkipped(int kind) throws Exception {
    when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(false);
    var page = replayPage(kind, 1);
    var end = new AtomicUnitEndRecord(1, false, null);
    // Different external high bits still describe the same internal file.
    long backupId = (9L << 32) | DURABLE_INTERNAL_ID;
    when(writeCache.internalFileId(backupId)).thenReturn(DURABLE_INTERNAL_ID);
    var deletion = new FileDeletedWALRecord(2, backupId);
    var deleteEnd = new AtomicUnitEndRecord(2, false, null);
    var wal = replayWal(List.of(page, end, deletion, deleteEnd));
    try (var capture = new ReplayLogCapture()) {
      var context = new RecoveryPageContext();
      var method = AbstractStorage.class.getDeclaredMethod("restoreFrom", WriteAheadLog.class,
          LogSequenceNumber.class, RecoveryPageContext.class);
      method.setAccessible(true);
      assertEquals(deleteEnd.getLsn(), method.invoke(storage, wal, page.getLsn(), context));
      assertEquals(1, capture.records.stream()
          .filter(r -> r.getMessage().contains("Skipping page change")).count());
    }
    verify(writeCache, never()).restoreFileById(anyLong());
    verify(readCache, never()).loadOrAddForWrite(anyLong(), anyLong(), any(), anyBoolean(), any());
    // A declared allocation would preload this page. No load proves it was excluded during replay.
    // All records are in one batch. Lookahead must start after its consumed end, not its tail.
    verify(wal).next(end.getLsn(), 1_000);
    verify(wal, times(2)).next(deleteEnd.getLsn(), 1_000);
  }

  /** The deleting unit's own end is counted before replaying its earlier clearing operations. */
  @Test
  public void sameUnitDeletionSkipsEarlierChanges() throws Exception {
    when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(false);
    var page = replayPage(0, 1);
    var deletion = new FileDeletedWALRecord(1, DURABLE_EXTERNAL_ID);
    var end = new AtomicUnitEndRecord(1, false, null);
    var wal = replayWal(List.of(page, deletion, end));
    assertEquals(end.getLsn(), storage.restoreFrom(wal, page.getLsn()));
    verify(writeCache, never()).restoreFileById(anyLong());
  }

  /** An absent or incomplete deletion cannot suppress redo. The persisted-name fallback stays. */
  @Test
  public void absentOrIncompleteDeletionRestoresFileAndReplays() throws Exception {
    for (boolean incomplete : List.of(false, true)) {
      setUp();
      var page = replayPage(0, 1);
      var records = new ArrayList<WALRecord>(List.of(page,
          new AtomicUnitEndRecord(1, false, null)));
      if (incomplete) {
        records.add(new FileDeletedWALRecord(2, DURABLE_EXTERNAL_ID));
      }
      wireReplayFallback();
      var wal = replayWal(records);
      assertEquals(records.get(1).getLsn(), storage.restoreFrom(wal, page.getLsn()));
      verify(writeCache).restoreFileById(DURABLE_EXTERNAL_ID);
      verify((TestPageOperation) page).redo(any(DurablePage.class));
    }
  }

  /** A broken tail hides even a complete deletion beyond it and leaves the fallback active. */
  @Test
  public void brokenTailDoesNotAuthorizeSkip() throws Exception {
    var page = replayPage(0, 1);
    var end = new AtomicUnitEndRecord(1, false, null);
    var wal = replayWal(List.of(page, end));
    when(wal.next(end.getLsn(), 1_000)).thenThrow(new WALPageBrokenException("broken tail"));
    wireReplayFallback();
    assertEquals(end.getLsn(), storage.restoreFrom(wal, page.getLsn()));
    verify(writeCache).restoreFileById(DURABLE_EXTERNAL_ID);
    verify((TestPageOperation) page).redo(any(DurablePage.class));
  }

  /** A failed lookahead read does not stop application of the current unit. */
  @Test
  public void lookaheadIoErrorKeepsFallback() throws Exception {
    var page = replayPage(0, 1);
    var end = new AtomicUnitEndRecord(1, false, null);
    var wal = replayWal(List.of(page, end));
    var failure = new IOException("read failed");
    when(wal.next(end.getLsn(), 1_000)).thenThrow(failure).thenReturn(List.of());
    wireReplayFallback();
    try (var capture = new ReplayLogCapture()) {
      assertEquals(end.getLsn(), storage.restoreFrom(wal, page.getLsn()));
      var warnings = capture.records.stream()
          .filter(r -> r.getMessage().contains("Deletion lookahead stopped")).toList();
      assertEquals(1, warnings.size());
      assertEquals(Level.WARNING, warnings.getFirst().getLevel());
      assertTrue(warnings.getFirst().getMessage().contains(end.getLsn().toString()));
      assertSame(failure, warnings.getFirst().getThrown());
    }
    verify((TestPageOperation) page).redo(any(DurablePage.class));
  }

  /** A deletion before a new life of the same id must not suppress that life's changes. */
  @Test
  public void reusedIdAfterDeletionReplaysNewLife() throws Exception {
    var page = replayPage(0, 2);
    var deletion = new FileDeletedWALRecord(1, DURABLE_EXTERNAL_ID);
    var end = new AtomicUnitEndRecord(2, false, null);
    wireReplayFallback();
    // Missing on disk, including the deletion apply. The fallback restores the new life.
    var wal = replayWal(List.of(deletion, new AtomicUnitEndRecord(1, false, null), page, end));
    assertEquals(end.getLsn(), storage.restoreFrom(wal, deletion.getLsn()));
    verify(writeCache).restoreFileById(DURABLE_EXTERNAL_ID);
    verify((TestPageOperation) page).redo(any(DurablePage.class));
  }

  /** Existing pages replay normally even if their file is deleted later. No lookahead is needed. */
  @Test
  public void existingFileIsNotSkippedForLaterDeletion() throws Exception {
    var page = replayPage(0, 1);
    var end = new AtomicUnitEndRecord(2, false, null);
    wireReplayPage();
    var wal = replayWal(List.of(page, new AtomicUnitEndRecord(1, false, null),
        new FileDeletedWALRecord(2, DURABLE_EXTERNAL_ID), end));
    assertEquals(end.getLsn(), storage.restoreFrom(wal, page.getLsn()));
    verify((TestPageOperation) page).redo(any(DurablePage.class));
    verify(wal, times(1)).next(any(), eq(1_000));
  }

  /** Separate backup-style calls have separate evidence, even without a RecoveryPageContext. */
  @Test
  public void backupReplayDoesNotRetainDeletionsAcrossCalls() throws Exception {
    when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(false);
    var oldPage = replayPage(0, 1);
    var end = new AtomicUnitEndRecord(2, false, null);
    var first = replayWal(List.of(oldPage, new AtomicUnitEndRecord(1, false, null),
        new FileDeletedWALRecord(2, DURABLE_EXTERNAL_ID), end));
    assertEquals(end.getLsn(), storage.restoreFrom(first, oldPage.getLsn()));
    verify(writeCache, never()).restoreFileById(anyLong());

    var newPage = replayPage(0, 3);
    var newEnd = new AtomicUnitEndRecord(3, false, null);
    wireReplayFallback();
    var second = replayWal(List.of(newPage, newEnd));
    assertEquals(newEnd.getLsn(), storage.restoreFrom(second, newPage.getLsn()));
    verify(writeCache).restoreFileById(DURABLE_EXTERNAL_ID);
    verify((TestPageOperation) newPage).redo(any(DurablePage.class));
  }

  /** A pending create wins over later deletion evidence, so its page is created and redone. */
  @Test
  public void pendingCreateWinsOverLaterDeletion() throws Exception {
    wireMissingFileRecoverableViaConsult();
    var page = spy(new TestPageOperation(0, CREATED_EXTERNAL_ID, 1,
        new LogSequenceNumber(0, 0), 42));
    var entry = createCacheEntryWithLsn(CREATED_EXTERNAL_ID, 0, new LogSequenceNumber(0, 0));
    when(readCache.loadOrAddForWrite(eq(CREATED_EXTERNAL_ID), eq(0L), eq(writeCache),
        eq(true), any())).thenReturn(entry);
    var end = new AtomicUnitEndRecord(2, false, null);
    var wal = replayWal(List.of(new AtomicUnitStartRecord(false, 1), page,
        new FileCreatedWALRecord(1, "created.dat", CREATED_EXTERNAL_ID),
        new AtomicUnitEndRecord(1, false, null), new AtomicUnitStartRecord(false, 2),
        new FileDeletedWALRecord(2, CREATED_EXTERNAL_ID), end));

    assertEquals(end.getLsn(), storage.restoreFrom(wal, new LogSequenceNumber(1, 1)));

    var order = inOrder(readCache, page);
    order.verify(readCache).addFile("created.dat", CREATED_EXTERNAL_ID, writeCache);
    order.verify(page).redo(any(DurablePage.class));
    verify(writeCache, never()).restoreFileById(anyLong());
    assertEquals(page.getLsn(),
        DurablePage.getLogSequenceNumberFromPage(entry.getCachePointer().getBuffer()));
  }

  /**
   * Interleaved units place a page between another unit's deletion and end. Only the deletion
   * position defines the skip relation, so this later page must use the fallback and be redone.
   */
  @Test
  public void deletionPositionNotEndPositionControlsSkip() throws Exception {
    wireReplayFallback();
    var deletion = new FileDeletedWALRecord(1, DURABLE_EXTERNAL_ID);
    var page = replayPage(0, 2);
    var end = new AtomicUnitEndRecord(2, false, null);
    var wal = replayWal(List.of(new AtomicUnitStartRecord(false, 1), deletion,
        new AtomicUnitStartRecord(false, 2), page, new AtomicUnitEndRecord(1, false, null),
        end));

    assertEquals(end.getLsn(), storage.restoreFrom(wal, new LogSequenceNumber(1, 1)));

    verify(writeCache).restoreFileById(DURABLE_EXTERNAL_ID);
    verify((TestPageOperation) page).redo(any(DurablePage.class));
  }

  /** The latest of two committed deletions authorizes skipping a page between them. */
  @Test
  public void latestCommittedDeletionControlsSkip() throws Exception {
    when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(false);
    var first = new FileDeletedWALRecord(1, DURABLE_EXTERNAL_ID);
    var page = replayPage(0, 2);
    var end = new AtomicUnitEndRecord(3, false, null);
    var wal = replayWal(List.of(first, new AtomicUnitEndRecord(1, false, null), page,
        new AtomicUnitEndRecord(2, false, null),
        new FileDeletedWALRecord(3, DURABLE_EXTERNAL_ID), end));

    assertEquals(end.getLsn(), storage.restoreFrom(wal, first.getLsn()));

    verify(writeCache, never()).restoreFileById(anyLong());
    verify((TestPageOperation) page, never()).redo(any(DurablePage.class));
  }

  /** Captures only this replay's messages, even when other test classes log concurrently. */
  private final class ReplayLogCapture extends Handler implements AutoCloseable {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getLogger(storage.getClass().getName());
    private final long threadId = Thread.currentThread().threadId();

    private ReplayLogCapture() {
      logger.addHandler(this);
    }

    @Override
    public void publish(LogRecord record) {
      if (record.getLongThreadID() == threadId
          && (record.getMessage().contains("Skipping page change")
              || record.getMessage().contains("Deletion lookahead stopped"))) {
        records.add(record);
      }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
      logger.removeHandler(this);
    }
  }

  private WALRecord replayPage(int kind, long unit) {
    return switch (kind) {
      case 0 -> spy(new TestPageOperation(0, DURABLE_EXTERNAL_ID, unit,
          new LogSequenceNumber(0, 0), 42));
      case 1 -> new UpdatePageRecord(0, DURABLE_EXTERNAL_ID, unit, mock(WALChanges.class),
          new LogSequenceNumber(0, 0));
      default -> new PageAllocatedWALRecord(0, DURABLE_EXTERNAL_ID, unit);
    };
  }

  private WriteAheadLog replayWal(List<WALRecord> records) throws Exception {
    setField(storage, "idGen", new AtomicOperationIdGen());
    for (int i = 0; i < records.size(); i++) {
      records.get(i).setLsn(new LogSequenceNumber(1, i + 1));
    }
    var wal = mock(WriteAheadLog.class);
    when(wal.read(records.getFirst().getLsn(), 1_000)).thenAnswer(inv -> records);
    when(wal.next(any(), eq(1_000))).thenAnswer(inv -> {
      LogSequenceNumber cursor = inv.getArgument(0);
      return records.stream().filter(r -> r.getLsn().compareTo(cursor) > 0).toList();
    });
    return wal;
  }

  private void wireReplayFallback() throws Exception {
    when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(false);
    when(writeCache.restoreFileById(DURABLE_EXTERNAL_ID)).thenAnswer(inv -> {
      when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(true);
      return "durable.dat";
    });
    wireReplayPage();
  }

  private void wireReplayPage() throws Exception {
    var entry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0, new LogSequenceNumber(0, 0));
    when(readCache.loadOrAddForWrite(eq(DURABLE_EXTERNAL_ID), eq(0L), eq(writeCache),
        eq(true), any())).thenReturn(entry);
  }

  private CacheEntry createCacheEntryWithLsn(
      long fileId, int pageIndex, LogSequenceNumber pageLsn) {
    var buffer = ByteBuffer.allocateDirect(PAGE_SIZE).order(ByteOrder.nativeOrder());
    // Write the initial page LSN into the buffer so DurablePage.getLsn() returns it
    DurablePage.setLogSequenceNumberForPage(buffer, pageLsn);

    var cachePointer = mock(CachePointer.class);
    when(cachePointer.getBuffer()).thenReturn(buffer);

    var cacheEntry = mock(CacheEntry.class);
    when(cacheEntry.getPageIndex()).thenReturn(pageIndex);
    when(cacheEntry.getFileId()).thenReturn(fileId);
    when(cacheEntry.getCachePointer()).thenReturn(cachePointer);
    return cacheEntry;
  }

  /**
   * Single PageOperation with LSN > page LSN: redo is applied and page LSN is updated.
   */
  @Test
  public void testPageOperationRedoAppliedAndLsnUpdated() throws Exception {
    var pageLsn = new LogSequenceNumber(0, 0);
    var walLsn = new LogSequenceNumber(1, 100);
    var initialLsn = new LogSequenceNumber(0, 0);

    var pageOp = spy(new TestPageOperation(0, DURABLE_EXTERNAL_ID, 1, initialLsn, 42));
    pageOp.setLsn(walLsn);

    var cacheEntry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(cacheEntry);

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(pageOp);
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // Existing page-only units need neither file creation nor the deleted-file fallback.
    verify(readCache, never()).addFile(any(), anyLong(), any());
    verify(writeCache, never()).restoreFileById(anyLong());
    verify(pageOp).redo(any(DurablePage.class));

    // Verify page LSN was updated to the WAL record's LSN
    var buffer = cacheEntry.getCachePointer().getBuffer();
    var newLsn = DurablePage.getLogSequenceNumberFromPage(buffer);
    assertEquals("Page LSN should be updated to WAL record LSN", walLsn, newLsn);

    // Verify cache entry was released
    verify(readCache).releaseFromWrite(eq(cacheEntry), eq(writeCache), eq(true));

    assertTrue("atLeastOnePageUpdate should be true", atLeastOnePageUpdate.getValue());
  }

  /**
   * LSN idempotency: when pageLsn >= walRecordLsn, redo is NOT called.
   */
  @Test
  public void testPageOperationSkippedWhenPageLsnAlreadyCurrent() throws Exception {
    var walLsn = new LogSequenceNumber(1, 100);
    // Page already at or beyond the WAL record LSN
    var pageLsn = new LogSequenceNumber(1, 100);

    var pageOp = spy(
        new TestPageOperation(0, DURABLE_EXTERNAL_ID, 1, pageLsn, 42));
    pageOp.setLsn(walLsn);

    var cacheEntry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(cacheEntry);

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(pageOp);
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    verify(readCache, never()).addFile(any(), anyLong(), any());
    verify(writeCache, never()).restoreFileById(anyLong());

    // Redo must NOT be called — page is already current
    verify(pageOp, never()).redo(any(DurablePage.class));

    // Page LSN should be unchanged
    var buffer = cacheEntry.getCachePointer().getBuffer();
    var currentLsn = DurablePage.getLogSequenceNumberFromPage(buffer);
    assertEquals("Page LSN should not change", pageLsn, currentLsn);

    // Cache entry must still be released
    verify(readCache).releaseFromWrite(eq(cacheEntry), eq(writeCache), eq(true));

    // atLeastOnePageUpdate is still true (the record was in the atomic unit)
    assertTrue(atLeastOnePageUpdate.getValue());
  }

  /**
   * PageOperation for a non-durable file deleted during crash recovery is skipped
   * entirely — no cache load, no redo.
   */
  @Test
  public void testPageOperationSkippedForDeletedNonDurableFile() throws Exception {
    // Register the non-durable file as deleted
    var deletedIds = new IntOpenHashSet();
    deletedIds.add(ND_INTERNAL_ID);
    setField(storage, "deletedNonDurableFileIds", deletedIds);

    var pageOp = spy(
        new TestPageOperation(0, ND_EXTERNAL_ID, 1, new LogSequenceNumber(0, 0), 42));
    pageOp.setLsn(new LogSequenceNumber(1, 100));

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(pageOp);
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // No cache operations should occur
    verify(readCache, never()).loadOrAddForWrite(
        eq(ND_EXTERNAL_ID), anyLong(), any(), anyBoolean(), any());
    verify(pageOp, never()).redo(any(DurablePage.class));

    assertFalse("No page update for non-durable file only",
        atLeastOnePageUpdate.getValue());
  }

  /**
   * PageOperation for a file that doesn't exist triggers file restore, then proceeds
   * with redo.
   */
  @Test
  public void testPageOperationRestoresDeletedFile() throws Exception {
    // File doesn't exist but can be restored
    when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(false);
    when(writeCache.restoreFileById(DURABLE_EXTERNAL_ID)).thenReturn("durable.dat");

    var pageLsn = new LogSequenceNumber(0, 0);
    var walLsn = new LogSequenceNumber(1, 100);
    var pageOp = spy(
        new TestPageOperation(0, DURABLE_EXTERNAL_ID, 1, pageLsn, 42));
    pageOp.setLsn(walLsn);

    var cacheEntry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(cacheEntry);

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(pageOp);
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // File should be restored first
    verify(writeCache).restoreFileById(DURABLE_EXTERNAL_ID);

    // Then redo should proceed
    verify(pageOp).redo(any(DurablePage.class));
    assertTrue(atLeastOnePageUpdate.getValue());
  }

  /**
   * Pins the no-reconciliation single-call contract on the {@code PageOperation} branch
   * of {@code restoreAtomicUnit}: each PageOperation triggers exactly one
   * {@code readCache.loadOrAddForWrite} call at the recorded pageIndex. The prior
   * implementation wrapped the call in a {@code do/while} that re-allocated via a
   * since-deleted legacy allocator until the cache returned the requested index;
   * after the collapse, totality is supplied by {@code WriteCache.loadOrAdd}
   * (gap-fills intermediate pages on the disk engine) so the reconciliation loop is
   * provably unreachable. End-to-end gap-fill mechanics are not exercised here because
   * {@code readCache} is mocked; durable gap-fill coverage lives in
   * {@code LocalPaginatedStorageRestoreFromWALIT}. WAL replay runs on a single
   * recovery thread before the storage engine accepts concurrent transactions, so
   * this contract is single-threaded by construction; MT coverage is not in scope
   * for the replay path. Other PageOperation-branch contracts (redo applied, LSN
   * updated, releaseFromWrite called) are pinned by
   * {@link #testPageOperationRedoAppliedAndLsnUpdated()}.
   */
  @Test
  public void testPageOperationGapFillReplaySingleLoadCall() throws Exception {
    var pageLsn = new LogSequenceNumber(0, 0);
    var walLsn = new LogSequenceNumber(1, 100);
    var initialLsn = new LogSequenceNumber(0, 0);

    // High pageIndex visibly out-of-range for any fresh file in this test — makes
    // the gap-fill symbolic role obvious. The actual value is immaterial: the mocked
    // readCache returns a valid entry regardless of the requested index.
    final int gapPageIndex = 1_000;
    var pageOp = spy(new TestPageOperation(
        gapPageIndex, DURABLE_EXTERNAL_ID, 1, initialLsn, 42));
    pageOp.setLsn(walLsn);

    var cacheEntry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, gapPageIndex, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq((long) gapPageIndex), eq(writeCache), eq(true), any()))
        .thenReturn(cacheEntry);

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(pageOp);
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    verify(readCache, never()).addFile(any(), anyLong(), any());
    verify(writeCache, never()).restoreFileById(anyLong());

    // Exactly one loadOrAddForWrite call at the recorded pageIndex — no reconciliation
    // loop, no retry through the since-deleted legacy allocator. These are the only
    // assertions unique to this test; the other PageOperation-branch contracts are
    // already pinned by testPageOperationRedoAppliedAndLsnUpdated.
    verify(readCache, times(1)).loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq((long) gapPageIndex), eq(writeCache), eq(true), any());

    assertTrue(atLeastOnePageUpdate.getValue());
  }

  /**
   * Pins the no-reconciliation single-call contract on the {@code UpdatePageRecord}
   * branch of {@code restoreAtomicUnit}: each UpdatePageRecord triggers exactly one
   * {@code readCache.loadOrAddForWrite} call at the recorded pageIndex. Mirrors the
   * PageOperation-branch contract pinned by
   * {@link #testPageOperationGapFillReplaySingleLoadCall()}; covers the sibling
   * branch that the prior collapse touched. End-to-end gap-fill mechanics are not
   * exercised here because {@code readCache} is mocked. WAL replay runs on a single
   * recovery thread before the storage engine accepts concurrent transactions, so
   * this contract is single-threaded by construction; MT coverage is not in scope
   * for the replay path.
   */
  @Test
  public void testUpdatePageRecordGapFillReplaySingleLoadCall() throws Exception {
    var pageLsn = new LogSequenceNumber(0, 0);
    var walLsn = new LogSequenceNumber(1, 100);

    final int gapPageIndex = 1_000;
    var updateRecord = mock(UpdatePageRecord.class);
    when(updateRecord.getFileId()).thenReturn(DURABLE_EXTERNAL_ID);
    when(updateRecord.getPageIndex()).thenReturn((long) gapPageIndex);
    when(updateRecord.getInitialLsn()).thenReturn(pageLsn);
    when(updateRecord.getLsn()).thenReturn(walLsn);
    // WALChanges mock for restoreChanges — driven only when pageLsn < walRecord.getLsn().
    var walChanges = mock(WALChanges.class);
    when(updateRecord.getChanges()).thenReturn(walChanges);

    var cacheEntry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, gapPageIndex, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq((long) gapPageIndex), eq(writeCache), eq(true), any()))
        .thenReturn(cacheEntry);

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(updateRecord);
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    verify(readCache, never()).addFile(any(), anyLong(), any());
    verify(writeCache, never()).restoreFileById(anyLong());

    // Exactly one loadOrAddForWrite call at the recorded pageIndex — no reconciliation
    // loop, no retry through the since-deleted legacy allocator.
    verify(readCache, times(1)).loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq((long) gapPageIndex), eq(writeCache), eq(true), any());

    assertTrue(atLeastOnePageUpdate.getValue());
  }

  /**
   * Declarations after a page operation keep their WAL positions in the scan map.
   * Replay loads pages in ascending order before redo.
   */
  @Test
  public void declaredPagesMaterializeInAscendingOrderBeforeRedo() throws Exception {
    var pageOp = spy(new TestPageOperation(
        2, DURABLE_EXTERNAL_ID, 1, new LogSequenceNumber(0, 0), 42));
    pageOp.setLsn(new LogSequenceNumber(1, 100));
    var pageZero = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0, new LogSequenceNumber(0, 0));
    var pageTwo = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 2, new LogSequenceNumber(0, 0));
    when(readCache.loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), anyLong(), eq(writeCache), eq(true), any()))
        .thenAnswer(inv -> inv.getArgument(1, Long.class) == 0L ? pageZero : pageTwo);

    var high = new PageAllocatedWALRecord(2, DURABLE_EXTERNAL_ID, 1);
    high.setLsn(new LogSequenceNumber(1, 20));
    var low = new PageAllocatedWALRecord(0, DURABLE_EXTERNAL_ID, 1);
    low.setLsn(new LogSequenceNumber(1, 10));
    var unit = new ArrayList<WALRecord>();
    unit.add(new AtomicUnitStartRecord(false, 1));
    unit.add(pageOp); // The operations may precede their declarations in the WAL.
    unit.add(high);
    unit.add(low); // Deliberately out of page-index order.
    unit.add(new AtomicUnitEndRecord(1, false, null));

    var declared = storage.scanDeclaredAllocationPages(unit);
    assertEquals(1, declared.size());
    assertEquals(Map.of(
        0L, new LogSequenceNumber(1, 10),
        2L, new LogSequenceNumber(1, 20)), declared.get(DURABLE_INTERNAL_ID));

    var updated = new ModifiableBoolean();
    storage.restoreAtomicUnit(unit, updated);

    var order = inOrder(readCache, pageOp);
    order.verify(readCache).loadOrAddForWrite(DURABLE_EXTERNAL_ID, 0, writeCache, true,
        new LogSequenceNumber(1, 10));
    order.verify(readCache).releaseFromWrite(pageZero, writeCache, true);
    order.verify(readCache).loadOrAddForWrite(DURABLE_EXTERNAL_ID, 2, writeCache, true,
        new LogSequenceNumber(1, 20));
    order.verify(readCache).releaseFromWrite(pageTwo, writeCache, true);
    order.verify(pageOp).redo(any(DurablePage.class));
    assertTrue(updated.getValue());
  }

  /**
   * A declaration without redo still publishes its loaded page as changed. Otherwise the
   * dirty-page table retains its allocation position without a flushable write-cache page.
   */
  @Test
  public void unchangedDeclaredPreloadPublishesPageForFlush() throws Exception {
    var declaration = new PageAllocatedWALRecord(0, DURABLE_EXTERNAL_ID, 1);
    var allocationPosition = new LogSequenceNumber(2, 30);
    declaration.setLsn(allocationPosition);
    var page = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0,
        new LogSequenceNumber(-1, -1));
    when(readCache.loadOrAddForWrite(DURABLE_EXTERNAL_ID, 0, writeCache, true,
        allocationPosition)).thenReturn(page);
    var unit = new ArrayList<WALRecord>();
    unit.add(new AtomicUnitStartRecord(false, 1));
    unit.add(declaration);
    unit.add(new AtomicUnitEndRecord(1, false, null));

    storage.restoreAtomicUnit(unit, new ModifiableBoolean());

    var order = inOrder(readCache);
    order.verify(readCache).loadOrAddForWrite(DURABLE_EXTERNAL_ID, 0, writeCache, true,
        allocationPosition);
    order.verify(readCache).releaseFromWrite(page, writeCache, true);
    assertEquals(new LogSequenceNumber(-1, -1),
        DurablePage.getLogSequenceNumberFromPage(page.getCachePointer().getBuffer()));
  }

  /** Crash replay installs and clears provenance even when WAL reading fails. */
  @Test
  public void crashReplayClearsContextOnReadFailure() throws Exception {
    var wal = mock(WriteAheadLog.class);
    var lsn = new LogSequenceNumber(1, 1);
    when(wal.begin()).thenReturn(lsn);
    when(wal.read(eq(lsn), eq(1_000))).thenThrow(new IOException("failed read"));
    setField(storage, "writeAheadLog", wal);
    var method = AbstractStorage.class.getDeclaredMethod("restoreFromBeginning");
    method.setAccessible(true);

    var thrown = assertThrows(InvocationTargetException.class,
        () -> method.invoke(storage));
    assertTrue(thrown.getCause() instanceof IOException);
    var order = inOrder(writeCache);
    order.verify(writeCache).restoreModeOn();
    order.verify(writeCache).setRecoveryPageContext(any(RecoveryPageContext.class));
    order.verify(writeCache).setRecoveryPageContext(isNull());
    order.verify(writeCache).restoreModeOff();
  }

  /**
   * Declaration membership is false in an empty context and for other pages or files. An
   * allocation position makes only that page declared, and clearing the unit removes it.
   * The current redo position does not grant declaration membership.
   */
  @Test
  public void declarationMembershipMatchesOnlyPagesInCurrentUnit() {
    var context = new RecoveryPageContext();
    var position = new LogSequenceNumber(3, 12);
    context.setCurrentRecord(new LogSequenceNumber(3, 20));
    assertFalse(context.isDeclaredPage(DURABLE_INTERNAL_ID, 0));

    var pages = new TreeMap<Long, LogSequenceNumber>();
    pages.put(0L, position);
    context.setDeclaredPages(Map.of(DURABLE_INTERNAL_ID, pages));
    assertTrue(context.isDeclaredPage(DURABLE_INTERNAL_ID, 0));
    assertEquals(position, context.declaredPosition(DURABLE_INTERNAL_ID, 0));
    assertFalse(context.isDeclaredPage(DURABLE_INTERNAL_ID, 1));
    assertFalse(context.isDeclaredPage(ND_INTERNAL_ID, 0));

    context.setDeclaredPages(null);
    assertFalse(context.isDeclaredPage(DURABLE_INTERNAL_ID, 0));
  }

  /** A unit exposes declarations only while it preloads and applies its own records. */
  @Test
  public void declaredContextIsClearedAfterSuccessfulUnit() throws Exception {
    var context = new RecoveryPageContext();
    var position = new LogSequenceNumber(3, 12);
    var declaration = new PageAllocatedWALRecord(0, DURABLE_EXTERNAL_ID, 1);
    declaration.setLsn(position);
    var page = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0,
        new LogSequenceNumber(-1, -1));
    when(readCache.loadOrAddForWrite(DURABLE_EXTERNAL_ID, 0, writeCache, true, position))
        .thenAnswer(inv -> {
          assertEquals(position, context.declaredPosition(DURABLE_INTERNAL_ID, 0));
          return page;
        });
    var unit = new ArrayList<WALRecord>();
    unit.add(new AtomicUnitStartRecord(false, 1));
    unit.add(declaration);
    unit.add(new AtomicUnitEndRecord(1, false, null));
    var method = AbstractStorage.class.getDeclaredMethod("restoreAtomicUnit", java.util.List.class,
        ModifiableBoolean.class, RecoveryPageContext.class);
    method.setAccessible(true);
    method.invoke(storage, unit, new ModifiableBoolean(), context);
    assertEquals(null, context.declaredPosition(DURABLE_INTERNAL_ID, 0));
    verify(readCache).releaseFromWrite(page, writeCache, true);
  }

  /**
   * A checkpoint may remove the beginning of a committed unit. Its remaining allocation
   * record still needs a preload, but it cannot authorize rebuilding an invalid page without
   * a DWL copy: earlier page changes may have been cut from the WAL. A complete unit can
   * authorize the same load. The mocked read cache models the broken-page exception from
   * WOWCache when no recovery declaration is available.
   */
  @Test
  public void headlessUnitPreloadsButRejectsBrokenPageWithoutDwlCopy() throws Exception {
    var context = new RecoveryPageContext();
    var position = new LogSequenceNumber(3, 12);
    var declaration = new PageAllocatedWALRecord(0, DURABLE_EXTERNAL_ID, 1);
    declaration.setLsn(position);
    var page = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0,
        new LogSequenceNumber(-1, -1));
    when(readCache.loadOrAddForWrite(DURABLE_EXTERNAL_ID, 0, writeCache, true, position))
        .thenAnswer(inv -> {
          if (context.declaredPosition(DURABLE_INTERNAL_ID, 0) == null) {
            throw new StorageException("testStorage", "broken page without DWL copy");
          }
          return page;
        });
    var headless = new ArrayList<WALRecord>();
    headless.add(declaration);
    headless.add(new AtomicUnitEndRecord(1, false, null));
    var method = AbstractStorage.class.getDeclaredMethod("restoreAtomicUnit", java.util.List.class,
        ModifiableBoolean.class, RecoveryPageContext.class);
    method.setAccessible(true);

    var failure = assertThrows(InvocationTargetException.class,
        () -> method.invoke(storage, headless, new ModifiableBoolean(), context));
    assertTrue(failure.getCause() instanceof StorageException);
    assertEquals(null, context.declaredPosition(DURABLE_INTERNAL_ID, 0));
    verify(readCache, never()).releaseFromWrite(page, writeCache, true);

    var complete = new ArrayList<WALRecord>();
    complete.add(new AtomicUnitStartRecord(false, 1));
    complete.add(declaration);
    complete.add(new AtomicUnitEndRecord(1, false, null));
    method.invoke(storage, complete, new ModifiableBoolean(), context);
    verify(readCache).releaseFromWrite(page, writeCache, true);
    assertEquals(null, context.declaredPosition(DURABLE_INTERNAL_ID, 0));
  }

  /** A replay starting after a WAL cut still dispatches a unit without its start record. */
  @Test
  public void walReplayDispatchesHeadlessUnitAfterCheckpointCut() throws Exception {
    setField(storage, "idGen", new AtomicOperationIdGen());
    var wal = mock(WriteAheadLog.class);
    var position = new LogSequenceNumber(3, 12);
    var endPosition = new LogSequenceNumber(3, 20);
    var declaration = new PageAllocatedWALRecord(0, DURABLE_EXTERNAL_ID, 1);
    declaration.setLsn(position);
    var end = new AtomicUnitEndRecord(1, false, null);
    end.setLsn(endPosition);
    when(wal.read(position, 1_000)).thenReturn(java.util.List.of(declaration, end));
    when(wal.next(endPosition, 1_000)).thenReturn(java.util.List.of());
    var page = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0,
        new LogSequenceNumber(-1, -1));
    when(readCache.loadOrAddForWrite(DURABLE_EXTERNAL_ID, 0, writeCache, true, position))
        .thenReturn(page);

    assertEquals(endPosition, storage.restoreFrom(wal, position));
    verify(readCache).releaseFromWrite(page, writeCache, true);
  }

  /** Failure during declared-page loading cannot leak provenance into the next unit. */
  @Test
  public void failedUnitClearsDeclaredContext() throws Exception {
    var context = new RecoveryPageContext();
    var position = new LogSequenceNumber(3, 12);
    var declaration = new PageAllocatedWALRecord(0, DURABLE_EXTERNAL_ID, 1);
    declaration.setLsn(position);
    when(readCache.loadOrAddForWrite(DURABLE_EXTERNAL_ID, 0, writeCache, true, position))
        .thenThrow(new StorageException("testStorage", "failed load"));
    var unit = new ArrayList<WALRecord>();
    unit.add(new AtomicUnitStartRecord(false, 1));
    unit.add(declaration);
    unit.add(new AtomicUnitEndRecord(1, false, null));
    var method = AbstractStorage.class.getDeclaredMethod("restoreAtomicUnit", java.util.List.class,
        ModifiableBoolean.class, RecoveryPageContext.class);
    method.setAccessible(true);
    assertThrows(InvocationTargetException.class,
        () -> method.invoke(storage, unit, new ModifiableBoolean(), context));
    assertEquals(null, context.declaredPosition(DURABLE_INTERNAL_ID, 0));
  }

  /** Backup WAL replay takes the two-argument entry point and installs no recovery context. */
  @Test
  public void backupReplayHasNoRecoveryContext() throws Exception {
    var wal = mock(WriteAheadLog.class);
    var lsn = new LogSequenceNumber(1, 1);
    when(wal.read(eq(lsn), eq(1_000))).thenReturn(java.util.List.of());
    storage.restoreFrom(wal, lsn);
    verify(writeCache, never()).setRecoveryPageContext(any());
  }

  /** A declaration alone can forward-create a file whose create record comes later. */
  @Test
  public void declarationConsultsPendingCreateBeforeAnyApply() throws Exception {
    wireMissingFileRecoverableViaConsult();
    var page = createCacheEntryWithLsn(CREATED_EXTERNAL_ID, 0, new LogSequenceNumber(0, 0));
    when(readCache.loadOrAddForWrite(
        eq(CREATED_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(page);
    var declaration = new PageAllocatedWALRecord(0, CREATED_EXTERNAL_ID, 1);
    declaration.setLsn(new LogSequenceNumber(1, 1));
    var unit = new ArrayList<WALRecord>();
    unit.add(new AtomicUnitStartRecord(false, 1));
    unit.add(declaration);
    unit.add(new FileCreatedWALRecord(1, "created.dat", CREATED_EXTERNAL_ID));
    unit.add(new AtomicUnitEndRecord(1, false, null));

    var updated = new ModifiableBoolean();
    storage.restoreAtomicUnit(unit, updated);

    var order = inOrder(readCache);
    order.verify(readCache).addFile("created.dat", CREATED_EXTERNAL_ID, writeCache);
    order.verify(readCache).loadOrAddForWrite(CREATED_EXTERNAL_ID, 0, writeCache, true,
        declaration.getLsn());
    order.verify(readCache).releaseFromWrite(page, writeCache, true);
    assertFalse(updated.getValue()); // An allocation is not a page update.
  }

  // ---------------------------------------------------------------------------------------------
  // Lazy-consult crash-replay regression (issue YTDB-1099).
  //
  // A file-creating atomic unit logs its page-redo records BEFORE its FileCreatedWALRecord, then
  // its physical addFile during the apply phase. If a crash makes the unit's end record durable but
  // loses the physical apply, replay re-applies the unit in list order: a page redo for the
  // not-yet-created file hits !writeCache.exists. Before the fix, restoreFileById returned null
  // (the create was never persisted as a deleted-file entry) and restoreAtomicUnit threw, which
  // restoreFrom's catch(RuntimeException) turned into a discard of EVERY later committed unit. The
  // fix's pending-create consult scans the current unit forward for the matching FileCreatedWALRecord
  // and materializes the file, so the redo proceeds and replay continues across units. A genuinely
  // incomplete unit (no FileCreatedWALRecord, restoreFileById null) must still throw, so a
  // blanket-restore fix is caught.
  // ---------------------------------------------------------------------------------------------

  /** An existing file makes both the early page consult and its later create record no-ops. */
  @Test
  public void testExistingFileWithPendingCreateDoesNotMaterializeAgain() throws Exception {
    when(writeCache.exists("durable.dat")).thenReturn(true);
    var pageOp = spy(new TestPageOperation(0, DURABLE_EXTERNAL_ID, 1,
        new LogSequenceNumber(0, 0), 42));
    pageOp.setLsn(new LogSequenceNumber(1, 100));
    var entry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0, new LogSequenceNumber(0, 0));
    when(readCache.loadOrAddForWrite(eq(DURABLE_EXTERNAL_ID), eq(0L), eq(writeCache),
        eq(true), any())).thenReturn(entry);
    var unit = spy(new ArrayList<WALRecord>(List.of(new AtomicUnitStartRecord(false, 1), pageOp,
        new FileCreatedWALRecord(1, "durable.dat", DURABLE_EXTERNAL_ID),
        new AtomicUnitEndRecord(1, false, null))));

    storage.restoreAtomicUnit(unit, new ModifiableBoolean());

    verify(pageOp).redo(any(DurablePage.class));
    verify(readCache, never()).addFile(any(), anyLong(), any());
    verify(writeCache, never()).restoreFileById(anyLong());
    // The allocation pre-scan and outer replay loop traverse the unit, but no create scan runs.
    verify(unit, times(2)).iterator();
  }

  /** EOF without an end record drops the buffered unit, even if it contains a pending create. */
  @Test
  public void testRestoreFromDropsUnitWithoutEndRecord() throws Exception {
    var wal = mock(WriteAheadLog.class);
    var startLsn = new LogSequenceNumber(1, 1);
    var start = new AtomicUnitStartRecord(false, 1);
    start.setLsn(startLsn);
    var pageOp = spy(new TestPageOperation(0, CREATED_EXTERNAL_ID, 1,
        new LogSequenceNumber(0, 0), 42));
    pageOp.setLsn(new LogSequenceNumber(1, 2));
    var create = new FileCreatedWALRecord(1, "created.dat", CREATED_EXTERNAL_ID);
    create.setLsn(new LogSequenceNumber(1, 3));
    when(wal.read(startLsn, 1_000)).thenReturn(List.of(start, pageOp, create));
    when(wal.next(create.getLsn(), 1_000)).thenReturn(List.of());

    assertNull(storage.restoreFrom(wal, startLsn));

    verify(wal).next(create.getLsn(), 1_000);
    verify(readCache, never()).addFile(any(), anyLong(), any());
    verify(readCache, never()).loadOrAddForWrite(anyLong(), anyLong(), any(), anyBoolean(), any());
    verify(writeCache, never()).restoreFileById(anyLong());
    verify(pageOp, never()).redo(any(DurablePage.class));
  }

  private static final int CREATED_INTERNAL_ID = 11;
  private static final long CREATED_EXTERNAL_ID = (1L << 32) | CREATED_INTERNAL_ID;

  /**
   * PageOperation arm: a unit whose page redo precedes its own FileCreatedWALRecord (the
   * intra-unit record ordering) recovers via the pending-create consult. The missing file is
   * materialized through readCache.addFile and the redo is applied, with no exception. Before the
   * fix this unit threw because restoreFileById returned null for a never-persisted create.
   */
  @Test
  public void testPageOperationConsultsPendingCreateInSameUnit() throws Exception {
    // The file does not exist yet (its physical addFile was lost in the crash window) and is not
    // recoverable via restoreFileById (the create was never persisted as a deleted-file entry).
    wireMissingFileRecoverableViaConsult();

    var pageLsn = new LogSequenceNumber(0, 0);
    var walLsn = new LogSequenceNumber(1, 100);
    var pageOp = spy(new TestPageOperation(0, CREATED_EXTERNAL_ID, 1, pageLsn, 42));
    pageOp.setLsn(walLsn);

    var cacheEntry = createCacheEntryWithLsn(CREATED_EXTERNAL_ID, 0, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(CREATED_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(cacheEntry);

    // Record order mirrors the real machinery: page redo first, then the create, then the end.
    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(pageOp);
    atomicUnit.add(new FileCreatedWALRecord(1, "created.dat", CREATED_EXTERNAL_ID));
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // The consult materialized the file via addFile (NOT the restoreFileById fallback), then the
    // redo proceeded — and crucially no StorageException escaped to abort later units.
    verify(readCache).addFile("created.dat", CREATED_EXTERNAL_ID, writeCache);
    verify(pageOp).redo(any(DurablePage.class));
    assertTrue(atLeastOnePageUpdate.getValue());
  }

  /**
   * UpdatePageRecord arm: same lazy-consult recovery as the PageOperation arm. The sibling missing-file
   * branch must also consult the unit's pending FileCreatedWALRecord rather than throw.
   */
  @Test
  public void testUpdatePageRecordConsultsPendingCreateInSameUnit() throws Exception {
    wireMissingFileRecoverableViaConsult();

    var pageLsn = new LogSequenceNumber(0, 0);
    var walLsn = new LogSequenceNumber(1, 100);
    var updateRecord = mock(UpdatePageRecord.class);
    when(updateRecord.getFileId()).thenReturn(CREATED_EXTERNAL_ID);
    when(updateRecord.getPageIndex()).thenReturn(0L);
    when(updateRecord.getInitialLsn()).thenReturn(pageLsn);
    when(updateRecord.getLsn()).thenReturn(walLsn);
    when(updateRecord.getChanges()).thenReturn(mock(WALChanges.class));

    var cacheEntry = createCacheEntryWithLsn(CREATED_EXTERNAL_ID, 0, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(CREATED_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(cacheEntry);

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(updateRecord);
    atomicUnit.add(new FileCreatedWALRecord(1, "created.dat", CREATED_EXTERNAL_ID));
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    verify(readCache).addFile("created.dat", CREATED_EXTERNAL_ID, writeCache);
    verify(readCache).loadOrAddForWrite(eq(CREATED_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true),
        any());
    assertTrue(atLeastOnePageUpdate.getValue());
  }

  /**
   * Cross-unit survival, code-observable: restoreFrom applies units one at a time and a throw from
   * any restoreAtomicUnit call aborts every later unit. Here unit 1 is the recoverable file-creating
   * unit (page redo before its own create) and unit 2 writes a durable file. Applying them in
   * sequence as restoreFrom does, the second unit's redo MUST still run, proving the lazy-consult
   * fix stops unit 1 from throwing and discarding unit 2. Before the fix, unit 1 threw and unit 2
   * was lost.
   */
  @Test
  public void testLaterUnitSurvivesAfterRecoverableFileCreatingUnit() throws Exception {
    // Unit 1: recoverable file-creating unit.
    wireMissingFileRecoverableViaConsult();

    var pageLsn = new LogSequenceNumber(0, 0);
    var unit1Lsn = new LogSequenceNumber(1, 100);
    var unit1Op = spy(new TestPageOperation(0, CREATED_EXTERNAL_ID, 1, pageLsn, 1));
    unit1Op.setLsn(unit1Lsn);
    var unit1Entry = createCacheEntryWithLsn(CREATED_EXTERNAL_ID, 0, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(CREATED_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(unit1Entry);

    var unit1 = new ArrayList<WALRecord>();
    unit1.add(new AtomicUnitStartRecord(false, 1));
    unit1.add(unit1Op);
    unit1.add(new FileCreatedWALRecord(1, "created.dat", CREATED_EXTERNAL_ID));
    unit1.add(new AtomicUnitEndRecord(1, false, null));

    // Unit 2: writes the pre-existing durable file (the "later committed unit").
    var unit2Lsn = new LogSequenceNumber(1, 200);
    var unit2Op = spy(new TestPageOperation(0, DURABLE_EXTERNAL_ID, 2, pageLsn, 2));
    unit2Op.setLsn(unit2Lsn);
    var unit2Entry = createCacheEntryWithLsn(DURABLE_EXTERNAL_ID, 0, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(unit2Entry);

    var unit2 = new ArrayList<WALRecord>();
    unit2.add(new AtomicUnitStartRecord(false, 2));
    unit2.add(unit2Op);
    unit2.add(new AtomicUnitEndRecord(2, false, null));

    // Drive both units in log order, exactly as restoreFrom's loop does.
    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(unit1, atLeastOnePageUpdate);
    storage.restoreAtomicUnit(unit2, atLeastOnePageUpdate);

    // Unit 1 recovered its file; unit 2's redo ran — the later unit was NOT discarded.
    verify(readCache).addFile("created.dat", CREATED_EXTERNAL_ID, writeCache);
    verify(unit1Op).redo(any(DurablePage.class));
    verify(unit2Op).redo(any(DurablePage.class));

    // Survival is a durable page mutation, not just a redo() invocation: assert unit 2's page LSN
    // actually advanced to its WAL LSN after replay. A refactor that still calls redo on the later
    // unit but drops or misorders its durable effect (e.g. an inverted pageLsn guard, or setLsn
    // removed) would leave the verify(...).redo(...) green while the later unit's page is silently
    // not advanced — exactly the data-loss class this regression exists to catch. Mirrors
    // testPageOperationRedoAppliedAndLsnUpdated's LSN read-back.
    var unit2Buffer = unit2Entry.getCachePointer().getBuffer();
    assertEquals(
        "Later unit's page LSN must advance to its WAL LSN after replay (the survival effect)",
        unit2Lsn,
        DurablePage.getLogSequenceNumberFromPage(unit2Buffer));
  }

  /**
   * A genuinely incomplete unit — a page redo for a missing file with NO matching
   * FileCreatedWALRecord in the unit and a null restoreFileById — must still throw, so a fix that
   * blindly materializes every missing file would be caught. This holds the discard path the
   * lazy-consult fix narrows but must not erase.
   */
  @Test
  public void testGenuinelyIncompleteUnitStillThrows() throws Exception {
    when(writeCache.exists(CREATED_EXTERNAL_ID)).thenReturn(false);
    when(writeCache.internalFileId(CREATED_EXTERNAL_ID)).thenReturn(CREATED_INTERNAL_ID);
    when(writeCache.restoreFileById(CREATED_EXTERNAL_ID)).thenReturn(null);

    var pageOp =
        spy(new TestPageOperation(0, CREATED_EXTERNAL_ID, 1, new LogSequenceNumber(0, 0), 7));
    pageOp.setLsn(new LogSequenceNumber(1, 100));

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(pageOp);
    // No FileCreatedWALRecord for the missing file — the unit is genuinely incomplete.
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    assertThrows(
        StorageException.class,
        () -> storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate));

    // Effect-absent postcondition: the incomplete unit must produce NO effect before throwing —
    // no file materialized via the consult, no redo applied. The throw fires in the missing-file
    // consult before loadOrAddForWrite and before any redo, so nothing leaks today; pinning the
    // throw-before-apply ordering catches a future reorder that partially applied the incomplete
    // unit (e.g. materialize the file, then fail later) while still passing assertThrows.
    verify(readCache, never()).addFile(any(), anyLong(), any());
    verify(pageOp, never()).redo(any(DurablePage.class));
  }

  /**
   * When a missing file has no pending FileCreatedWALRecord in the unit but IS recoverable via
   * restoreFileById (a file deleted by a later, already-applied unit), the consult must fall
   * through to that load-bearing fallback rather than throw — the existing restore-via-fallback
   * behavior is preserved, and addFile (the consult path) is not used.
   */
  @Test
  public void testRestoreFileByIdFallbackPreservedWhenNoPendingCreate() throws Exception {
    when(writeCache.exists(CREATED_EXTERNAL_ID)).thenReturn(false);
    when(writeCache.internalFileId(CREATED_EXTERNAL_ID)).thenReturn(CREATED_INTERNAL_ID);
    when(writeCache.externalFileId(CREATED_INTERNAL_ID)).thenReturn(CREATED_EXTERNAL_ID);
    when(writeCache.restoreFileById(CREATED_EXTERNAL_ID)).thenReturn("recovered.dat");

    var pageLsn = new LogSequenceNumber(0, 0);
    var walLsn = new LogSequenceNumber(1, 100);
    var pageOp = spy(new TestPageOperation(0, CREATED_EXTERNAL_ID, 1, pageLsn, 9));
    pageOp.setLsn(walLsn);
    var cacheEntry = createCacheEntryWithLsn(CREATED_EXTERNAL_ID, 0, pageLsn);
    when(readCache.loadOrAddForWrite(
        eq(CREATED_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any()))
        .thenReturn(cacheEntry);

    var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(pageOp);
    // No FileCreatedWALRecord — the consult finds nothing and must use restoreFileById.
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    verify(writeCache).restoreFileById(CREATED_EXTERNAL_ID);
    verify(readCache, never()).addFile(any(), anyLong(), any());
    verify(pageOp).redo(any(DurablePage.class));
    assertTrue(atLeastOnePageUpdate.getValue());
  }

  /**
   * Models the WriteCache state transition the production cache makes: once the lazy-consult
   * materializes the missing file via {@code readCache.addFile}, the file exists, so the unit's
   * own later {@link FileCreatedWALRecord} replays as an idempotent no-op (its {@code !exists}
   * guard fails). Without this, the mock would keep reporting the file absent and the later
   * create record would call {@code addFile} a second time.
   */
  /**
   * Wires the CREATED_* file as present-but-unrecoverable and arms the consult's materialize
   * transition, the shared Arrange shape for the three pending-create consult happy-path tests:
   * the file does not exist (its physical {@code addFile} was lost in the crash window), its
   * id mapping resolves both ways, {@code restoreFileById} returns {@code null} (the create was
   * never persisted as a deleted-file entry), and {@code readCache.addFile} flips {@code exists}
   * to true. Each caller then differs only in the WAL record list and the verification — the
   * per-test delta the boilerplate would otherwise bury.
   */
  private void wireMissingFileRecoverableViaConsult() throws IOException {
    when(writeCache.exists(CREATED_EXTERNAL_ID)).thenReturn(false);
    when(writeCache.exists("created.dat")).thenReturn(false);
    when(writeCache.internalFileId(CREATED_EXTERNAL_ID)).thenReturn(CREATED_INTERNAL_ID);
    when(writeCache.externalFileId(CREATED_INTERNAL_ID)).thenReturn(CREATED_EXTERNAL_ID);
    when(writeCache.restoreFileById(CREATED_EXTERNAL_ID)).thenReturn(null);
    wireConsultMaterializes("created.dat", CREATED_EXTERNAL_ID);
  }

  private void wireConsultMaterializes(String name, long externalId) throws IOException {
    doAnswer(
        inv -> {
          when(writeCache.exists(name)).thenReturn(true);
          when(writeCache.exists(externalId)).thenReturn(true);
          return null;
        })
        .when(readCache)
        .addFile(name, externalId, writeCache);
  }

  private static void setField(Object target, String fieldName, Object value)
      throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Field findField(Class<?> clazz, String fieldName) {
    while (clazz != null) {
      try {
        return clazz.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new RuntimeException("Field not found: " + fieldName);
  }
}
