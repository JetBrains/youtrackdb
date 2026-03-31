package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecord;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.lang.reflect.Field;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests that {@code AbstractStorage.restoreAtomicUnit()} gracefully skips WAL records
 * referencing non-durable files that were deleted during crash recovery. Verifies all three
 * record types (UpdatePageRecord, FileCreatedWALRecord, FileDeletedWALRecord) are skipped
 * for non-durable file IDs, while durable file records are still processed normally.
 */
public class RestoreAtomicUnitNonDurableSkipTest {

  private static final int PAGE_SIZE = 8192;

  // Internal file IDs (lower 32 bits of external ID)
  private static final int ND_INTERNAL_ID = 42;
  private static final int DURABLE_INTERNAL_ID = 7;

  // External file IDs (storageId=1 in upper 32 bits)
  private static final long ND_EXTERNAL_ID = (1L << 32) | ND_INTERNAL_ID;
  private static final long DURABLE_EXTERNAL_ID = (1L << 32) | DURABLE_INTERNAL_ID;

  private WriteCache writeCache;
  private ReadCache readCache;
  private AbstractStorage storage;

  @Before
  public void setUp() throws Exception {
    writeCache = mock(WriteCache.class);
    readCache = mock(ReadCache.class);

    // internalFileId extracts lower 32 bits
    when(writeCache.internalFileId(ND_EXTERNAL_ID)).thenReturn(ND_INTERNAL_ID);
    when(writeCache.internalFileId(DURABLE_EXTERNAL_ID)).thenReturn(DURABLE_INTERNAL_ID);

    // Durable file exists in write cache
    when(writeCache.exists(DURABLE_EXTERNAL_ID)).thenReturn(true);
    when(writeCache.exists("durable.dat")).thenReturn(true);
    when(writeCache.externalFileId(DURABLE_INTERNAL_ID)).thenReturn(DURABLE_EXTERNAL_ID);
    when(writeCache.fileNameById(DURABLE_EXTERNAL_ID)).thenReturn("durable.dat");

    // Non-durable file does NOT exist (already deleted by recovery)
    when(writeCache.exists(ND_EXTERNAL_ID)).thenReturn(false);
    when(writeCache.exists("nd.dat")).thenReturn(false);

    // Create AbstractStorage mock that delegates to real restoreAtomicUnit
    storage = Mockito.mock(AbstractStorage.class, Mockito.CALLS_REAL_METHODS);

    // Set the fields needed by restoreAtomicUnit via reflection
    setField(storage, "writeCache", writeCache);
    setField(storage, "readCache", readCache);
    setField(storage, "name", "testStorage");

    // Set deletedNonDurableFileIds to contain the non-durable file ID
    final var deletedIds = new IntOpenHashSet();
    deletedIds.add(ND_INTERNAL_ID);
    setField(storage, "deletedNonDurableFileIds", deletedIds);
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

  /**
   * Verifies that UpdatePageRecord for a non-durable file is skipped — no restoreFileById,
   * no loadForWrite, no exception thrown.
   */
  @Test
  public void testUpdatePageRecordSkippedForNonDurableFile() throws Exception {
    final var ndRecord = mock(UpdatePageRecord.class);
    when(ndRecord.getFileId()).thenReturn(ND_EXTERNAL_ID);
    when(ndRecord.getLsn()).thenReturn(new LogSequenceNumber(1, 50));

    final var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(ndRecord);
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    final var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // Non-durable file should NOT trigger restoreFileById or loadForWrite
    verify(writeCache, never()).restoreFileById(ND_EXTERNAL_ID);
    verify(readCache, never()).loadForWrite(
        eq(ND_EXTERNAL_ID), anyLong(), any(), anyBoolean(), any());

    assertFalse("No page update should occur for non-durable file only",
        atLeastOnePageUpdate.getValue());
  }

  /**
   * Verifies that FileCreatedWALRecord for a non-durable file is skipped — the file is not
   * re-created.
   */
  @Test
  public void testFileCreatedRecordSkippedForNonDurableFile() throws Exception {
    final var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(new FileCreatedWALRecord(1, "nd.dat", ND_EXTERNAL_ID));
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    final var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // Non-durable file should NOT be re-created
    verify(readCache, never()).addFile(eq("nd.dat"), anyLong(), any());
  }

  /**
   * Verifies that FileDeletedWALRecord for a non-durable file is skipped — the file was
   * already deleted during crash recovery. The file is configured as existing in the write
   * cache so that without the skip logic, the delete WOULD be processed.
   */
  @Test
  public void testFileDeletedRecordSkippedForNonDurableFile() throws Exception {
    // Configure the non-durable file as existing so the skip logic is the only
    // reason deleteFile is not called (prevents false positive)
    when(writeCache.exists(ND_EXTERNAL_ID)).thenReturn(true);

    final var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(new FileDeletedWALRecord(1, ND_EXTERNAL_ID));
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    final var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // Non-durable file should NOT trigger a cache delete (already gone)
    verify(readCache, never()).deleteFile(eq(ND_EXTERNAL_ID), any());
  }

  /**
   * Verifies that FileDeletedWALRecord for a durable file is still processed normally,
   * even when non-durable files are being skipped.
   */
  @Test
  public void testFileDeletedRecordProcessedForDurableFile() throws Exception {
    final var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    // Non-durable file delete — skipped
    atomicUnit.add(new FileDeletedWALRecord(1, ND_EXTERNAL_ID));
    // Durable file delete — processed
    atomicUnit.add(new FileDeletedWALRecord(1, DURABLE_EXTERNAL_ID));
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    final var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // Non-durable file skip
    verify(readCache, never()).deleteFile(eq(ND_EXTERNAL_ID), any());

    // Durable file processed
    verify(readCache).deleteFile(DURABLE_EXTERNAL_ID, writeCache);
  }

  /**
   * Verifies that a mixed atomic unit with both a non-durable and a durable UpdatePageRecord
   * correctly skips the non-durable record while processing the durable one. This is the most
   * common real-world scenario — a transaction touching both durable and non-durable files.
   */
  @Test
  public void testUpdatePageRecordMixedDurableAndNonDurable() throws Exception {
    // Non-durable UpdatePageRecord — should be skipped
    final var ndRecord = mock(UpdatePageRecord.class);
    when(ndRecord.getFileId()).thenReturn(ND_EXTERNAL_ID);
    when(ndRecord.getLsn()).thenReturn(new LogSequenceNumber(1, 50));

    // Durable UpdatePageRecord — should be processed
    final var durableRecord = mock(UpdatePageRecord.class);
    when(durableRecord.getFileId()).thenReturn(DURABLE_EXTERNAL_ID);
    when(durableRecord.getPageIndex()).thenReturn(0L);
    when(durableRecord.getLsn()).thenReturn(new LogSequenceNumber(1, 100));
    when(durableRecord.getInitialLsn()).thenReturn(new LogSequenceNumber(0, 0));
    // WALChanges mock for applyChanges — called when page LSN < WAL record LSN
    final var walChanges = mock(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALChanges.class);
    when(durableRecord.getChanges()).thenReturn(walChanges);

    // Configure writeCache for durable file processing
    when(writeCache.externalFileId(DURABLE_INTERNAL_ID)).thenReturn(DURABLE_EXTERNAL_ID);

    // Configure readCache.loadForWrite to return a mock CacheEntry for the durable file.
    // The CacheEntry → CachePointer → ByteBuffer chain must be set up so DurablePage can
    // read the page LSN (needed by restoreAtomicUnit's comparison logic).
    final var cacheEntry =
        mock(com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry.class);
    final var cachePointer =
        mock(com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer.class);
    final var buffer =
        java.nio.ByteBuffer.allocateDirect(PAGE_SIZE).order(java.nio.ByteOrder.nativeOrder());
    when(cacheEntry.getCachePointer()).thenReturn(cachePointer);
    when(cachePointer.getBuffer()).thenReturn(buffer);
    when(readCache.loadForWrite(
        eq(DURABLE_EXTERNAL_ID), eq(0L), eq(writeCache), anyBoolean(), any()))
        .thenReturn(cacheEntry);

    final var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(ndRecord);
    atomicUnit.add(durableRecord);
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    final var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // Non-durable file must not trigger any cache operations
    verify(writeCache, never()).restoreFileById(ND_EXTERNAL_ID);
    verify(readCache, never()).loadForWrite(
        eq(ND_EXTERNAL_ID), anyLong(), any(), anyBoolean(), any());

    // Durable file must be processed
    verify(readCache).loadForWrite(
        eq(DURABLE_EXTERNAL_ID), eq(0L), eq(writeCache), eq(true), any());

    assertTrue("Durable page update should set atLeastOnePageUpdate",
        atLeastOnePageUpdate.getValue());
  }

  /**
   * Verifies that a mixed atomic unit with both a non-durable and a durable FileCreatedWALRecord
   * correctly skips the non-durable record while processing the durable one.
   */
  @Test
  public void testFileCreatedRecordMixedDurableAndNonDurable() throws Exception {
    // Durable file does not yet exist (needs to be created by WAL replay)
    when(writeCache.exists("durable.dat")).thenReturn(false);

    final var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    // Non-durable file create — skipped
    atomicUnit.add(new FileCreatedWALRecord(1, "nd.dat", ND_EXTERNAL_ID));
    // Durable file create — processed
    atomicUnit.add(new FileCreatedWALRecord(1, "durable.dat", DURABLE_EXTERNAL_ID));
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    final var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // Non-durable file should NOT be re-created
    verify(readCache, never()).addFile(eq("nd.dat"), anyLong(), any());

    // Durable file should be created
    verify(readCache).addFile("durable.dat", DURABLE_EXTERNAL_ID, writeCache);
  }

  /**
   * Verifies that when deletedNonDurableFileIds is empty (no non-durable files deleted during
   * recovery), all WAL records are processed normally — the skip logic is a no-op.
   */
  @Test
  public void testAllRecordsProcessedWhenNoNonDurableFilesDeleted() throws Exception {
    // Clear the non-durable IDs
    setField(storage, "deletedNonDurableFileIds", new IntOpenHashSet());

    // Both files exist
    when(writeCache.exists(ND_EXTERNAL_ID)).thenReturn(true);

    final var atomicUnit = new ArrayList<WALRecord>();
    atomicUnit.add(new AtomicUnitStartRecord(false, 1));
    atomicUnit.add(new FileDeletedWALRecord(1, ND_EXTERNAL_ID));
    atomicUnit.add(new FileDeletedWALRecord(1, DURABLE_EXTERNAL_ID));
    atomicUnit.add(new AtomicUnitEndRecord(1, false, null));

    final var atLeastOnePageUpdate = new ModifiableBoolean();
    storage.restoreAtomicUnit(atomicUnit, atLeastOnePageUpdate);

    // Both files should be deleted (no skip)
    verify(readCache).deleteFile(ND_EXTERNAL_ID, writeCache);
    verify(readCache).deleteFile(DURABLE_EXTERNAL_ID, writeCache);
  }
}
