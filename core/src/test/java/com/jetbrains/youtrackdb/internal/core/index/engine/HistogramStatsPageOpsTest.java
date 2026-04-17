package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperationRegistry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for HistogramStatsPage PageOperation subclasses: record IDs, serialization roundtrips,
 * factory roundtrips, redo correctness (byte-level), redo suppression, and equals/hashCode.
 */
public class HistogramStatsPageOpsTest {

  @Before
  public void setUp() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  // ---- Record ID verification ----

  @Test
  public void testWriteEmptyOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_EMPTY_OP,
        HistogramStatsPageWriteEmptyOp.RECORD_ID);
    Assert.assertEquals(279, HistogramStatsPageWriteEmptyOp.RECORD_ID);
  }

  @Test
  public void testWriteSnapshotOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_SNAPSHOT_OP,
        HistogramStatsPageWriteSnapshotOp.RECORD_ID);
    Assert.assertEquals(280, HistogramStatsPageWriteSnapshotOp.RECORD_ID);
  }

  @Test
  public void testWriteHllToPage1OpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_HLL_TO_PAGE1_OP,
        HistogramStatsPageWriteHllToPage1Op.RECORD_ID);
    Assert.assertEquals(281, HistogramStatsPageWriteHllToPage1Op.RECORD_ID);
  }

  // ---- Serialization roundtrip ----

  @Test
  public void testWriteEmptyOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new HistogramStatsPageWriteEmptyOp(
        10, 20, 30, initialLsn, (byte) 7);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new HistogramStatsPageWriteEmptyOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(),
        deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals((byte) 7, deserialized.getSerializerId());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testWriteSnapshotOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    byte[] histData = {1, 2, 3, 4, 5};
    byte[] inlineHllData = {10, 20, 30};
    var original = new HistogramStatsPageWriteSnapshotOp(
        10, 20, 30, initialLsn,
        (byte) 5, 1000L, 500L, 50L, 10L, 900L,
        1024, histData, inlineHllData);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new HistogramStatsPageWriteSnapshotOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals((byte) 5, deserialized.getSerializerId());
    Assert.assertEquals(1000L, deserialized.getTotalCount());
    Assert.assertEquals(500L, deserialized.getDistinctCount());
    Assert.assertEquals(50L, deserialized.getNullCount());
    Assert.assertEquals(10L, deserialized.getMutationsSinceRebalance());
    Assert.assertEquals(900L, deserialized.getTotalCountAtLastBuild());
    Assert.assertEquals(1024, deserialized.getPersistedHllCount());
    Assert.assertArrayEquals(histData, deserialized.getHistData());
    Assert.assertArrayEquals(inlineHllData, deserialized.getInlineHllData());
    Assert.assertEquals(original, deserialized);
  }

  /**
   * Roundtrip with empty histData and empty inlineHllData — the counters-only case
   * (no histogram built yet, no HLL).
   */
  @Test
  public void testWriteSnapshotOpSerializationRoundtrip_emptyArrays() {
    var initialLsn = new LogSequenceNumber(1, 50);
    var original = new HistogramStatsPageWriteSnapshotOp(
        0, 0, 0, initialLsn,
        (byte) 3, 42L, 10L, 2L, 5L, 30L,
        0, new byte[0], new byte[0]);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new HistogramStatsPageWriteSnapshotOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(0, deserialized.getHistData().length);
    Assert.assertEquals(0, deserialized.getInlineHllData().length);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testWriteHllToPage1OpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 300);
    byte[] hllData = new byte[1024];
    for (int i = 0; i < hllData.length; i++) {
      hllData[i] = (byte) (i & 0xFF);
    }
    var original = new HistogramStatsPageWriteHllToPage1Op(
        10, 20, 30, initialLsn, hllData);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new HistogramStatsPageWriteHllToPage1Op();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertArrayEquals(hllData, deserialized.getHllData());
    Assert.assertEquals(original, deserialized);
  }

  // ---- Factory roundtrip ----

  @Test
  public void testWriteEmptyOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new HistogramStatsPageWriteEmptyOp(
        10, 20, 30, initialLsn, (byte) 9);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof HistogramStatsPageWriteEmptyOp);
    var result = (HistogramStatsPageWriteEmptyOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals((byte) 9, result.getSerializerId());
  }

  @Test
  public void testWriteSnapshotOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    byte[] histData = {1, 2, 3};
    byte[] inlineHllData = {4, 5};
    var original = new HistogramStatsPageWriteSnapshotOp(
        10, 20, 30, initialLsn,
        (byte) 5, 100L, 50L, 5L, 3L, 80L,
        1024, histData, inlineHllData);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof HistogramStatsPageWriteSnapshotOp);
    var result = (HistogramStatsPageWriteSnapshotOp) deserialized;
    Assert.assertEquals(100L, result.getTotalCount());
    Assert.assertArrayEquals(histData, result.getHistData());
    Assert.assertArrayEquals(inlineHllData, result.getInlineHllData());
  }

  @Test
  public void testWriteHllToPage1OpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    byte[] hllData = new byte[1024];
    hllData[0] = 42;
    hllData[1023] = 99;
    var original = new HistogramStatsPageWriteHllToPage1Op(
        10, 20, 30, initialLsn, hllData);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof HistogramStatsPageWriteHllToPage1Op);
    Assert.assertArrayEquals(hllData,
        ((HistogramStatsPageWriteHllToPage1Op) deserialized).getHllData());
  }

  // ---- Redo correctness ----

  /**
   * writeEmpty: apply directly on page1, redo on page2. Byte-level identical.
   */
  @Test
  public void testWriteEmptyOpRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      // Apply writeEmpty directly on page1
      var page1 = new HistogramStatsPage(entry1);
      page1.writeEmpty((byte) 5);

      // Apply writeEmpty via redo on page2
      new HistogramStatsPageWriteEmptyOp(
          0, 0, 0, new LogSequenceNumber(0, 0), (byte) 5).redo(page2(entry2));

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));

      // Verify field values
      var readPage = new HistogramStatsPage(entry2);
      Assert.assertEquals(0, readPage.getTotalCount());
      Assert.assertEquals(0, readPage.getDistinctCount());
      Assert.assertEquals(0, readPage.getNullCount());
      Assert.assertEquals(0, readPage.getHistogramDataLength());
      Assert.assertEquals(0, readPage.getRawHllRegisterCount());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * writeSnapshotRaw with histogram data and inline HLL: apply directly on page1,
   * redo on page2. Byte-level identical.
   */
  @Test
  public void testWriteSnapshotOpRedoCorrectness_withHistogramAndInlineHll() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      byte[] histData = {10, 20, 30, 40, 50};
      byte[] inlineHllData = {1, 2, 3, 4};

      // Apply directly on page1
      var page1 = new HistogramStatsPage(entry1);
      page1.writeSnapshotRaw((byte) 7, 1000L, 500L, 50L, 10L, 900L,
          4, histData, inlineHllData);

      // Apply via redo on page2
      new HistogramStatsPageWriteSnapshotOp(
          0, 0, 0, new LogSequenceNumber(0, 0),
          (byte) 7, 1000L, 500L, 50L, 10L, 900L,
          4, histData, inlineHllData).redo(page2(entry2));

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));

      // Verify field values
      var readPage = new HistogramStatsPage(entry2);
      Assert.assertEquals(1000L, readPage.getTotalCount());
      Assert.assertEquals(500L, readPage.getDistinctCount());
      Assert.assertEquals(50L, readPage.getNullCount());
      Assert.assertEquals(5, readPage.getHistogramDataLength());
      Assert.assertEquals(4, readPage.getRawHllRegisterCount());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * writeSnapshotRaw with histogram data and page-1 HLL flag set (no inline HLL).
   */
  @Test
  public void testWriteSnapshotOpRedoCorrectness_withPage1HllFlag() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      byte[] histData = {1, 2, 3};
      int persistedHllCount = 1024 | HistogramStatsPage.HLL_PAGE1_FLAG;

      // Apply directly on page1
      var page1 = new HistogramStatsPage(entry1);
      page1.writeSnapshotRaw((byte) 3, 200L, 100L, 10L, 5L, 180L,
          persistedHllCount, histData, new byte[0]);

      // Apply via redo on page2
      new HistogramStatsPageWriteSnapshotOp(
          0, 0, 0, new LogSequenceNumber(0, 0),
          (byte) 3, 200L, 100L, 10L, 5L, 180L,
          persistedHllCount, histData, new byte[0]).redo(page2(entry2));

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));

      // Verify HLL page-1 flag is set
      var readPage = new HistogramStatsPage(entry2);
      int rawHllCount = readPage.getRawHllRegisterCount();
      Assert.assertTrue("HLL_PAGE1_FLAG should be set",
          (rawHllCount & HistogramStatsPage.HLL_PAGE1_FLAG) != 0);
      Assert.assertEquals(1024, rawHllCount & ~HistogramStatsPage.HLL_PAGE1_FLAG);
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * writeSnapshotRaw counters-only (no histogram, no HLL).
   */
  @Test
  public void testWriteSnapshotOpRedoCorrectness_countersOnly() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      // Apply directly on page1 — counters only
      var page1 = new HistogramStatsPage(entry1);
      page1.writeSnapshotRaw((byte) 2, 500L, 250L, 25L, 7L, 450L,
          0, new byte[0], new byte[0]);

      // Apply via redo on page2
      new HistogramStatsPageWriteSnapshotOp(
          0, 0, 0, new LogSequenceNumber(0, 0),
          (byte) 2, 500L, 250L, 25L, 7L, 450L,
          0, new byte[0], new byte[0]).redo(page2(entry2));

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * writeHllRaw: apply directly on page1, redo on page2. Byte-level identical.
   */
  @Test
  public void testWriteHllToPage1OpRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      byte[] hllData = new byte[1024];
      for (int i = 0; i < hllData.length; i++) {
        hllData[i] = (byte) (i % 127);
      }

      // Apply directly on page1
      HistogramStatsPage.writeHllRaw(entry1, hllData);

      // Apply via redo on page2
      new HistogramStatsPageWriteHllToPage1Op(
          0, 0, 0, new LogSequenceNumber(0, 0), hllData).redo(page2(entry2));

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  // ---- Redo suppression (CacheEntryImpl does not register ops) ----

  @Test
  public void testRedoSuppression_writeEmptyDoesNotRegister() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();
    try {
      // First write non-zero data to the page to ensure writeEmpty actually resets it
      var page = new HistogramStatsPage(entry);
      page.writeSnapshotRaw((byte) 9, 999L, 888L, 77L, 66L, 55L,
          1024, new byte[] {1, 2, 3}, new byte[0]);
      Assert.assertEquals(999L, page.getTotalCount());
      Assert.assertEquals(3, page.getHistogramDataLength());

      // CacheEntryImpl is not CacheEntryChanges — instanceof check returns false
      page.writeEmpty((byte) 5);
      // Verify writeEmpty actually reset the page (non-zero → zero)
      Assert.assertEquals(0, page.getTotalCount());
      Assert.assertEquals(0, page.getDistinctCount());
      Assert.assertEquals(0, page.getNullCount());
      Assert.assertEquals(0, page.getHistogramDataLength());
      Assert.assertEquals(0, page.getRawHllRegisterCount());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  // ---- Equals/hashCode ----

  @Test
  public void testWriteEmptyOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new HistogramStatsPageWriteEmptyOp(10, 20, 30, lsn, (byte) 5);
    var op2 = new HistogramStatsPageWriteEmptyOp(10, 20, 30, lsn, (byte) 5);
    var op3 = new HistogramStatsPageWriteEmptyOp(10, 20, 30, lsn, (byte) 9);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testWriteSnapshotOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    byte[] hist = {1, 2};
    byte[] hll = {3, 4};
    var op1 = new HistogramStatsPageWriteSnapshotOp(
        10, 20, 30, lsn, (byte) 5, 100L, 50L, 5L, 3L, 80L, 1024, hist, hll);
    var op2 = new HistogramStatsPageWriteSnapshotOp(
        10, 20, 30, lsn, (byte) 5, 100L, 50L, 5L, 3L, 80L, 1024, hist, hll);
    var op3 = new HistogramStatsPageWriteSnapshotOp(
        10, 20, 30, lsn, (byte) 5, 999L, 50L, 5L, 3L, 80L, 1024, hist, hll);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testWriteHllToPage1OpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    byte[] hll1 = {1, 2, 3};
    byte[] hll2 = {4, 5, 6};
    var op1 = new HistogramStatsPageWriteHllToPage1Op(10, 20, 30, lsn, hll1);
    var op2 = new HistogramStatsPageWriteHllToPage1Op(10, 20, 30, lsn, hll1);
    var op3 = new HistogramStatsPageWriteHllToPage1Op(10, 20, 30, lsn, hll2);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  // ---- Helper ----

  /**
   * Creates a DurablePage wrapping the given cache entry — used as the redo target.
   * HistogramStatsPage is package-private so we create it directly.
   */
  private static HistogramStatsPage page2(CacheEntry entry) {
    return new HistogramStatsPage(entry);
  }
}
