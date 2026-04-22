package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.FileHandler;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
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
    when(readCache.loadForWrite(
        any(FileHandler.class), eq(0L), eq(writeCache), eq(true), any()))
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
    when(readCache.loadForWrite(
        any(FileHandler.class), eq(0L), eq(writeCache), eq(true), any()))
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
    verify(readCache, never()).loadForWrite(
        any(FileHandler.class), anyLong(), any(), anyBoolean(), any());
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
    when(readCache.loadForWrite(
        any(FileHandler.class), eq(0L), eq(writeCache), eq(true), any()))
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
