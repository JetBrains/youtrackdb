package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
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

    // Verify redo was called
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

    // Exactly one loadOrAddForWrite call at the recorded pageIndex — no reconciliation
    // loop, no retry through the since-deleted legacy allocator.
    verify(readCache, times(1)).loadOrAddForWrite(
        eq(DURABLE_EXTERNAL_ID), eq((long) gapPageIndex), eq(writeCache), eq(true), any());

    assertTrue(atLeastOnePageUpdate.getValue());
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
