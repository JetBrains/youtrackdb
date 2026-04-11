package com.jetbrains.youtrackdb.internal.core.storage.collection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * Tests for {@link CollectionPageAppendRecordOp} — the most complex collection page
 * operation. Covers serialization roundtrip, factory roundtrip, redo correctness
 * (simple append, hole reuse, defragmentation trigger), registration from mutation
 * methods, and failure non-registration.
 */
public class CollectionPageAppendRecordOpTest {

  private static final ByteBufferPool BUFFER_POOL = ByteBufferPool.instance(null);

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPageAppendRecordOp.RECORD_ID, CollectionPageAppendRecordOp.class);
  }

  private CacheEntry createRawCacheEntry() {
    var pointer = BUFFER_POOL.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, BUFFER_POOL, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();
    return entry;
  }

  private void releaseEntry(CacheEntry entry) {
    entry.releaseExclusiveLock();
    entry.getCachePointer().decrementReferrer();
  }

  // --- Record ID ---

  @Test
  public void testRecordId() {
    Assert.assertEquals(
        WALRecordTypes.COLLECTION_PAGE_APPEND_RECORD_OP,
        CollectionPageAppendRecordOp.RECORD_ID);
    Assert.assertEquals(207, CollectionPageAppendRecordOp.RECORD_ID);
  }

  // --- Serialization roundtrip ---

  @Test
  public void testSerializationRoundtrip() {
    var record = new byte[] {1, 2, 3, 4, 5};
    var original = new CollectionPageAppendRecordOp(
        10, 20, 30, new LogSequenceNumber(5, 100), 42L, record, 7, 4096, 0);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new CollectionPageAppendRecordOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original.getRecordVersion(), deserialized.getRecordVersion());
    Assert.assertArrayEquals(original.getRecord(), deserialized.getRecord());
    Assert.assertEquals(original.getAllocatedIndex(), deserialized.getAllocatedIndex());
    Assert.assertEquals(original.getEntryPosition(), deserialized.getEntryPosition());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSerializationEmptyRecord() {
    var original = new CollectionPageAppendRecordOp(
        0, 0, 0, new LogSequenceNumber(0, 0), 0L, new byte[0], 0, 0, 0);

    var content = new byte[original.serializedSize()];
    original.toStream(content, 0);

    var deserialized = new CollectionPageAppendRecordOp();
    deserialized.fromStream(content, 0);

    Assert.assertArrayEquals(new byte[0], deserialized.getRecord());
  }

  @Test
  public void testSerializationLargeRecord() {
    var record = new byte[1024];
    Arrays.fill(record, (byte) 0xAB);
    var original = new CollectionPageAppendRecordOp(
        0, 0, 0, new LogSequenceNumber(0, 0), Long.MAX_VALUE, record,
        Integer.MAX_VALUE, 2048, 0);

    var content = new byte[original.serializedSize()];
    original.toStream(content, 0);

    var deserialized = new CollectionPageAppendRecordOp();
    deserialized.fromStream(content, 0);

    Assert.assertArrayEquals(record, deserialized.getRecord());
    Assert.assertEquals(Long.MAX_VALUE, deserialized.getRecordVersion());
    Assert.assertEquals(Integer.MAX_VALUE, deserialized.getAllocatedIndex());
    Assert.assertEquals(2048, deserialized.getEntryPosition());
  }

  // --- Factory roundtrip ---

  @Test
  public void testFactoryRoundtrip() {
    var original = new CollectionPageAppendRecordOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 99L, new byte[] {10, 20, 30}, 5, 7000, 0);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var result = (CollectionPageAppendRecordOp) WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertEquals(99L, result.getRecordVersion());
    Assert.assertArrayEquals(new byte[] {10, 20, 30}, result.getRecord());
    Assert.assertEquals(5, result.getAllocatedIndex());
    Assert.assertEquals(7000, result.getEntryPosition());
  }

  // --- Redo correctness ---

  /**
   * Simple append on an empty page: direct apply vs redo produces the same page state.
   */
  @Test
  public void testRedoSimpleAppend() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      var record = new byte[] {1, 2, 3, 4, 5};

      // Direct path
      var page1 = new CollectionPage(entry1);
      page1.init();
      var idx1 = page1.appendRecord(1L, record, -1, IntSets.emptySet());
      Assert.assertNotEquals(-1, idx1);
      var entryPos = page1.getPointerValuePosition(idx1);

      // Redo path — use allocatedIndex and entryPosition from the direct path
      var page2 = new CollectionPage(entry2);
      page2.init();
      var op = new CollectionPageAppendRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 1L, record, idx1, entryPos, 0);
      op.redo(page2);

      // Compare state
      Assert.assertEquals(page1.getRecordsCount(), page2.getRecordsCount());
      Assert.assertEquals(page1.getFreeSpace(), page2.getFreeSpace());
      Assert.assertEquals(page1.getFreePosition(), page2.getFreePosition());
      Assert.assertArrayEquals(page1.getRecordBinaryValue(idx1, 0, record.length),
          page2.getRecordBinaryValue(idx1, 0, record.length));
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  /**
   * Append with hole reuse: fill the page so contiguous free space is insufficient,
   * delete a record to create a hole, then append a same-sized record into the hole.
   * The redo path must produce identical page state (same record at same position).
   */
  @Test
  public void testRedoAppendWithHoleReuse() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      // Use same-sized records so the hole is an exact fit (findHole accepts it).
      var fillRecord = new byte[200];

      // Direct path — fill page, create hole, then append into the hole
      var page1 = new CollectionPage(entry1);
      page1.init();

      int firstIdx = page1.appendRecord(1L, fillRecord, -1, IntSets.emptySet());
      while (page1.appendRecord(1L, fillRecord, -1, IntSets.emptySet()) != -1) {
        // fill until full
      }

      // Delete the first record — creates a hole of exactly the entry size
      page1.deleteRecord(firstIdx, true);

      // Append a record of the same size — should reuse the hole
      var holeRecord = new byte[200];
      int holeIdx = page1.appendRecord(2L, holeRecord, -1, IntSets.emptySet());
      Assert.assertNotEquals("append into hole should succeed", -1, holeIdx);
      int holeEntryPos = page1.getPointerValuePosition(holeIdx);
      // Verify hole was actually reused (position should match deleted record's position)
      int freePosBefore = page1.getFreePosition();

      // Redo path
      var page2 = new CollectionPage(entry2);
      page2.init();

      page2.appendRecord(1L, fillRecord, -1, IntSets.emptySet());
      while (page2.appendRecord(1L, fillRecord, -1, IntSets.emptySet()) != -1) {
        // fill until full
      }
      page2.deleteRecord(firstIdx, true);

      int entrySize = holeRecord.length + 3 * Integer.BYTES;
      var op = new CollectionPageAppendRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 2L, holeRecord,
          holeIdx, holeEntryPos, entrySize);
      op.redo(page2);

      // Page state must match exactly
      Assert.assertEquals(page1.getRecordsCount(), page2.getRecordsCount());
      Assert.assertEquals(page1.getFreeSpace(), page2.getFreeSpace());
      Assert.assertEquals(page1.getFreePosition(), page2.getFreePosition());
      Assert.assertArrayEquals(
          page1.getRecordBinaryValue(holeIdx, 0, holeRecord.length),
          page2.getRecordBinaryValue(holeIdx, 0, holeRecord.length));
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  /**
   * Verify no op registered when append fails (returns -1, no space).
   */
  @Test
  public void testNoRegistrationOnAppendFailure() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var entry = createRawCacheEntry();
    try {
      changes.setDelegate(entry);
      changes.setInitialLSN(new LogSequenceNumber(1, 50));

      var page = new CollectionPage(changes);
      page.init();

      // Fill the page until no space remains
      var fillRecord = new byte[CollectionPage.MAX_RECORD_SIZE];
      while (page.appendRecord(1L, fillRecord, -1, IntSets.emptySet()) != -1) {
        // keep filling
      }

      reset(atomicOp);

      // Now try to append again — should fail (returns -1)
      int result = page.appendRecord(1L, new byte[] {1}, -1, IntSets.emptySet());
      Assert.assertEquals(-1, result);

      // No registration should have occurred for the failed append
      verify(atomicOp, never()).registerPageOperation(
          ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(),
          ArgumentMatchers.any(CollectionPageAppendRecordOp.class));
    } finally {
      releaseEntry(entry);
    }
  }

  /**
   * Verify that appendRecord registers an op with the correct allocatedIndex.
   */
  @Test
  public void testAppendRegistersOpWithCorrectIndex() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var entry = createRawCacheEntry();
    try {
      changes.setDelegate(entry);
      changes.setInitialLSN(new LogSequenceNumber(1, 100));

      var page = new CollectionPage(changes);
      page.init();

      reset(atomicOp);

      var record = new byte[] {1, 2, 3};
      int idx = page.appendRecord(1L, record, -1, IntSets.emptySet());
      Assert.assertNotEquals(-1, idx);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(),
          opCaptor.capture());

      Assert.assertTrue(opCaptor.getValue() instanceof CollectionPageAppendRecordOp);
      var appendOp = (CollectionPageAppendRecordOp) opCaptor.getValue();
      Assert.assertEquals(idx, appendOp.getAllocatedIndex());
      Assert.assertEquals(1L, appendOp.getRecordVersion());
      Assert.assertArrayEquals(record, appendOp.getRecord());
      Assert.assertEquals(page.getPointerValuePosition(idx), appendOp.getEntryPosition());
      Assert.assertEquals(0, appendOp.getHoleSize()); // freePosition path
    } finally {
      releaseEntry(entry);
    }
  }

  // --- Equals / hashCode ---

  @Test
  public void testEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var record = new byte[] {1, 2, 3};
    var op1 = new CollectionPageAppendRecordOp(5, 10, 15, lsn, 42L, record, 7, 4000, 0);
    var op2 = new CollectionPageAppendRecordOp(5, 10, 15, lsn, 42L, record, 7, 4000, 0);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different allocatedIndex
    var op3 = new CollectionPageAppendRecordOp(5, 10, 15, lsn, 42L, record, 99, 4000, 0);
    Assert.assertNotEquals(op1, op3);

    // Different record
    var op4 = new CollectionPageAppendRecordOp(
        5, 10, 15, lsn, 42L, new byte[] {9, 8, 7}, 7, 4000, 0);
    Assert.assertNotEquals(op1, op4);

    // Different entryPosition
    var op5 = new CollectionPageAppendRecordOp(5, 10, 15, lsn, 42L, record, 7, 5000, 0);
    Assert.assertNotEquals(op1, op5);

    // Different holeSize
    var op6 = new CollectionPageAppendRecordOp(5, 10, 15, lsn, 42L, record, 7, 4000, 16);
    Assert.assertNotEquals(op1, op6);
  }

  // --- Multiple sequential redos ---

  /**
   * Multiple sequential append redos must produce the same cumulative page state
   * as the direct path. Validates that index area, free space, and free position
   * accounting are consistent across multiple redo replays.
   */
  @Test
  public void testRedoMultipleSequentialAppends() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      var r1 = new byte[] {1, 2, 3};
      var r2 = new byte[] {4, 5, 6, 7, 8};
      var r3 = new byte[] {9};

      // Direct path
      var page1 = new CollectionPage(entry1);
      page1.init();
      int idx1 = page1.appendRecord(1L, r1, -1, IntSets.emptySet());
      int pos1 = page1.getPointerValuePosition(idx1);
      int idx2 = page1.appendRecord(2L, r2, -1, IntSets.emptySet());
      int pos2 = page1.getPointerValuePosition(idx2);
      int idx3 = page1.appendRecord(3L, r3, -1, IntSets.emptySet());
      int pos3 = page1.getPointerValuePosition(idx3);

      // Redo path — replay all 3 appends via redo on a freshly initialized page
      var page2 = new CollectionPage(entry2);
      page2.init();
      new CollectionPageAppendRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 1L, r1, idx1, pos1, 0).redo(page2);
      new CollectionPageAppendRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 2L, r2, idx2, pos2, 0).redo(page2);
      new CollectionPageAppendRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 3L, r3, idx3, pos3, 0).redo(page2);

      // Cumulative state must match
      Assert.assertEquals(page1.getRecordsCount(), page2.getRecordsCount());
      Assert.assertEquals(page1.getFreeSpace(), page2.getFreeSpace());
      Assert.assertEquals(page1.getFreePosition(), page2.getFreePosition());
      Assert.assertArrayEquals(
          page1.getRecordBinaryValue(idx1, 0, r1.length),
          page2.getRecordBinaryValue(idx1, 0, r1.length));
      Assert.assertArrayEquals(
          page1.getRecordBinaryValue(idx2, 0, r2.length),
          page2.getRecordBinaryValue(idx2, 0, r2.length));
      Assert.assertArrayEquals(
          page1.getRecordBinaryValue(idx3, 0, r3.length),
          page2.getRecordBinaryValue(idx3, 0, r3.length));
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  /**
   * Append that triggers defragmentation: direct path vs redo (DefragOp + AppendRecordOp).
   * When contiguous free space is insufficient but total free space suffices, appendRecord
   * triggers doDefragmentation() internally. During WAL replay, DefragOp is replayed first,
   * then AppendRecordOp. Verifies logical equivalence.
   */
  @Test
  public void testRedoAppendWithDefragmentationTrigger() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      var smallRecord = new byte[100];

      // Direct path: fill page, delete non-adjacent records, append larger record
      var page1 = new CollectionPage(entry1);
      page1.init();

      int idx0 = page1.appendRecord(1L, smallRecord, -1, IntSets.emptySet());
      int idx1 = page1.appendRecord(1L, smallRecord, -1, IntSets.emptySet());
      int idx2 = page1.appendRecord(1L, smallRecord, -1, IntSets.emptySet());
      while (page1.appendRecord(1L, smallRecord, -1, IntSets.emptySet()) != -1) {
        // fill until full
      }

      // Delete two non-adjacent records — creates two separate holes (~112 bytes each)
      page1.deleteRecord(idx0, true);
      page1.deleteRecord(idx2, true);

      // Append a record of size 150 (entrySize ~162) — no single hole is big enough,
      // so defragmentation is triggered to consolidate free space
      var bigRecord = new byte[150];
      int idxNew = page1.appendRecord(2L, bigRecord, -1, IntSets.emptySet());
      Assert.assertNotEquals("append requiring defrag should succeed", -1, idxNew);

      var entryPosNew = page1.getPointerValuePosition(idxNew);

      // Redo path: same setup, then replay DefragOp + AppendRecordOp
      var page2 = new CollectionPage(entry2);
      page2.init();

      page2.appendRecord(1L, smallRecord, -1, IntSets.emptySet());
      page2.appendRecord(1L, smallRecord, -1, IntSets.emptySet());
      page2.appendRecord(1L, smallRecord, -1, IntSets.emptySet());
      while (page2.appendRecord(1L, smallRecord, -1, IntSets.emptySet()) != -1) {
        // fill until full
      }
      page2.deleteRecord(idx0, true);
      page2.deleteRecord(idx2, true);

      new CollectionPageDoDefragmentationOp(
          0, 0, 0, new LogSequenceNumber(0, 0)).redo(page2);
      new CollectionPageAppendRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 2L, bigRecord,
          idxNew, entryPosNew, 0).redo(page2);

      // Logical state must match
      Assert.assertEquals(page1.getRecordsCount(), page2.getRecordsCount());
      Assert.assertArrayEquals(
          page1.getRecordBinaryValue(idxNew, 0, bigRecord.length),
          page2.getRecordBinaryValue(idxNew, 0, bigRecord.length));
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  // --- Hole-reuse registration (CQ1 fix) ---

  /**
   * Verify that appendRecord into a hole (free-list reuse early-return path)
   * registers a CollectionPageAppendRecordOp. The hole-reuse path must not skip
   * registration.
   */
  @Test
  public void testAppendIntoHoleRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var entry = createRawCacheEntry();
    try {
      changes.setDelegate(entry);
      changes.setInitialLSN(new LogSequenceNumber(1, 100));

      var page = new CollectionPage(changes);
      page.init();

      // Fill the page with same-sized records until full
      var record = new byte[200];
      int firstIdx = page.appendRecord(1L, record, -1, IntSets.emptySet());
      while (page.appendRecord(1L, record, -1, IntSets.emptySet()) != -1) {
        // keep filling
      }

      // Delete the first record to create a hole of exactly the right size
      page.deleteRecord(firstIdx, true);

      reset(atomicOp);

      // Append a record of the same size — should reuse the hole
      int holeIdx = page.appendRecord(2L, record, -1, IntSets.emptySet());
      Assert.assertNotEquals("append into hole should succeed", -1, holeIdx);

      // Verify a CollectionPageAppendRecordOp was registered
      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(),
          opCaptor.capture());
      Assert.assertTrue(
          "Expected CollectionPageAppendRecordOp but got "
              + opCaptor.getValue().getClass(),
          opCaptor.getValue() instanceof CollectionPageAppendRecordOp);
      var appendOp = (CollectionPageAppendRecordOp) opCaptor.getValue();
      Assert.assertEquals(holeIdx, appendOp.getAllocatedIndex());
    } finally {
      releaseEntry(entry);
    }
  }

  // --- No registration during redo ---

  @Test
  public void testNoRegistrationDuringRedoPath() {
    var entry = createRawCacheEntry();
    try {
      var record = new byte[] {1, 2, 3};
      var entrySize = record.length + 3 * Integer.BYTES;
      var entryPosition = CollectionPage.PAGE_SIZE - entrySize;

      var page = new CollectionPage(entry);
      page.init();
      // Append via redo (plain CacheEntry, no CacheEntryChanges)
      var op = new CollectionPageAppendRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 1L, record, 0, entryPosition, 0);
      op.redo(page);

      Assert.assertEquals(1, page.getRecordsCount());
    } finally {
      releaseEntry(entry);
    }
  }
}
