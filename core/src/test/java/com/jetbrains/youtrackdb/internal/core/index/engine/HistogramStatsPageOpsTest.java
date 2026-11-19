package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperationRegistry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
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

  /**
   * Independent predecessor bytes cover empty, inline and sketch-spill WAL records.
   * Expected legacy page bytes are built without today's page or operation encoders.
   * Decode through the startup registry and verify raw replay and interpreted values.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void predecessorBytesReplayExactlyAndRemainReadable() {
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    // Zero capacity frees each page in the finally block even when a replay assertion fails.
    var pool = new ByteBufferPool(DurablePage.MAX_PAGE_SIZE_BYTES, new DirectMemoryAllocator(), 0);
    // One bucket: nonNull=10, MCV=5 at frequency 3, boundaries 1..9, frequency=10, NDV=4.
    byte[] histogram = ByteBuffer.allocate(60).order(ByteOrder.nativeOrder())
        .putInt(1).putLong(10).putLong(3).putInt(4).putInt(5)
        .putInt(4).putInt(1).putInt(4).putInt(9).putLong(10).putLong(4).array();
    byte[] sketch = new byte[1024];
    Arrays.fill(sketch, (byte) 2);
    for (int kind = 0; kind < 4; kind++) {
      boolean empty = kind == 0;
      boolean spill = kind == 2;
      boolean sketchOnly = kind == 3;
      byte[] payload;
      byte[] expected;
      if (empty) {
        payload = new byte[] {8};
        expected = ByteBuffer.allocate(53).order(ByteOrder.nativeOrder())
            .putInt(1).put((byte) 8).array();
      } else if (sketchOnly) {
        payload = ByteBuffer.allocate(1028).order(ByteOrder.nativeOrder())
            .putInt(1024).put(sketch).array();
        expected = sketch;
      } else {
        int count = spill ? 0x80000400 : 1024;
        payload = ByteBuffer.allocate(53 + histogram.length + (spill ? 0 : 1024))
            .order(ByteOrder.nativeOrder()).put((byte) 8)
            .putLong(12).putLong(4).putLong(2).putLong(7).putLong(11).putInt(count)
            .putInt(histogram.length).put(histogram).putInt(spill ? 0 : 1024).array();
        if (!spill) {
          System.arraycopy(sketch, 0, payload, payload.length - 1024, 1024);
        }
        expected = ByteBuffer.allocate(53 + histogram.length + (spill ? 0 : 1024))
            .order(ByteOrder.nativeOrder()).putInt(1).put((byte) 8)
            .putLong(12).putLong(4).putLong(2).putLong(7).putLong(11)
            .putInt(histogram.length).putInt(count).put(histogram).array();
        if (!spill) {
          System.arraycopy(sketch, 0, expected, expected.length - 1024, 1024);
        }
      }
      int id = empty ? 279 : sketchOnly ? 281 : 280;
      byte[] fixture = ByteBuffer.allocate(42 + payload.length).order(ByteOrder.nativeOrder())
          .putShort((short) id).putInt(0).putLong(99).putLong(sketchOnly ? 1 : 0)
          .putLong(13).putLong(1).putInt(100).put(payload).array();
      var op = (PageOperation) factory.fromStream(fixture);
      Assert.assertEquals(empty ? HistogramStatsPageWriteEmptyOp.class
          : sketchOnly ? HistogramStatsPageWriteHllToPage1Op.class
              : HistogramStatsPageWriteSnapshotOp.class,
          op.getClass());
      Assert.assertEquals(99, op.getOperationUnitId());
      var pointer = pool.acquireDirect(true, Intention.TEST);
      var cp = new CachePointer(pointer, pool, 13, (int) op.getPageIndex());
      cp.incrementReferrer();
      var entry = new CacheEntryImpl(13, (int) op.getPageIndex(), cp, false, null);
      entry.acquireExclusiveLock();
      try {
        byte[] before = new byte[DurablePage.MAX_PAGE_SIZE_BYTES];
        Arrays.fill(before, (byte) 0x5a);
        cp.getBuffer().put(0, before);
        op.redo(page2(entry));
        System.arraycopy(expected, 0, before, 28, expected.length);
        byte[] actual = new byte[before.length];
        cp.getBuffer().get(0, actual);
        Assert.assertArrayEquals(before, actual);
        if (sketchOnly) {
          byte[] read = new byte[1024];
          HistogramStatsPage.readHllFromPage1(entry).writeTo(read, 0);
          Assert.assertArrayEquals(sketch, read);
        } else {
          var read = page2(entry).readSnapshot(
              (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer) IntegerSerializer.INSTANCE,
              serializers);
          Assert.assertEquals(empty ? 0 : 12, read.stats().totalCount());
          Assert.assertEquals(empty ? 0 : 4, read.stats().distinctCount());
          Assert.assertEquals(empty ? 0 : 2, read.stats().nullCount());
          Assert.assertEquals(empty ? 0 : 7, read.mutationsSinceRebalance());
          Assert.assertEquals(empty ? 0 : 11, read.totalCountAtLastBuild());
          Assert.assertEquals(spill, read.hllOnPage1());
          if (!empty) {
            Assert.assertArrayEquals(new Comparable<?>[] {1, 9}, read.histogram().boundaries());
            Assert.assertArrayEquals(new long[] {10}, read.histogram().frequencies());
            Assert.assertArrayEquals(new long[] {4}, read.histogram().distinctCounts());
            Assert.assertEquals(10, read.histogram().nonNullCount());
            Assert.assertEquals(5, read.histogram().mcvValue());
            Assert.assertEquals(3, read.histogram().mcvFrequency());
          }
          if (!empty && !spill) {
            byte[] registers = new byte[1024];
            read.hllSketch().writeTo(registers, 0);
            Assert.assertArrayEquals(sketch, registers);
          }
        }
      } finally {
        entry.releaseExclusiveLock();
        cp.decrementReferrer();
      }
    }
    pool.clear();
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
