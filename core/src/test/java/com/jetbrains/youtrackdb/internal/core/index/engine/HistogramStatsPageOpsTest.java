package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.CharSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.CompactedLinkSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream.StreamSerializerRID;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperationRegistry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.MultiValueEntry;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.MultiValueEntrySerializer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

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
    for (int kind = 0; kind < 5; kind++) {
      boolean unversioned = kind == 4;
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
        if (unversioned) {
          cp.getBuffer().putInt(28, 0);
          ByteBuffer.wrap(expected).order(ByteOrder.nativeOrder()).putInt(0, 0);
        }
        System.arraycopy(expected, 0, before, 28, expected.length);
        byte[] actual = new byte[before.length];
        cp.getBuffer().get(0, actual);
        Assert.assertArrayEquals(before, actual);
        var page = page2(entry);
        if (sketchOnly) {
          var read = HistogramStatsPage.readHllFromPage1(entry);
          cp.getBuffer().put(28, new byte[expected.length]);
          HistogramStatsPage.writeHllToPage1(entry, read);
        } else {
          BinarySerializer<Object> serializer = (BinarySerializer) IntegerSerializer.INSTANCE;
          var read = page.readStoredSnapshot(null, serializer, serializers);
          cp.getBuffer().put(28, new byte[expected.length]);
          if (empty) {
            page.writeEmpty((byte) 8);
          } else {
            var toWrite = spill ? new HistogramSnapshot(read.stats(), read.histogram(),
                read.mutationsSinceRebalance(), read.totalCountAtLastBuild(), 0, false,
                HyperLogLogSketch.readFrom(sketch, 0), true) : read;
            page.writeSnapshot(toWrite, (byte) 8, serializer, serializers);
          }
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
        if (unversioned) {
          ByteBuffer.wrap(before).order(ByteOrder.nativeOrder()).putInt(28, 1);
        }
        cp.getBuffer().get(0, actual);
        Assert.assertArrayEquals(before, actual);
      } finally {
        entry.releaseExclusiveLock();
        cp.decrementReferrer();
      }
    }
    pool.clear();
  }

  /** Real 4 KB pages preserve exact fragment bytes and read back the complete semantic image. */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void compactSnapshotsFitAndReplayThroughTheFactory() {
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    BinarySerializer<Object> serializer = (BinarySerializer) UTF8Serializer.INSTANCE;
    var pool = new ByteBufferPool(4096, new DirectMemoryAllocator(), 0);
    var page0 = page2(compactPage(pool, 0));
    var page1 = page2(compactPage(pool, 1));
    try {
      for (int length = 4025; length <= 4049; length++) {
        verifyCompactSnapshot(length, serializer, serializers, factory, page0, page1);
      }
      verifyCompactSnapshot(8096, serializer, serializers, factory, page0, page1);
      Assert.assertThrows(IllegalArgumentException.class,
          () -> HistogramStatsPage.prepareParts(compactSnapshot(8097, serializer, serializers),
              serializer, serializers));
    } finally {
      for (var page : new HistogramStatsPage[] {page0, page1}) {
        releaseCompactPage(page);
      }
      pool.clear();
    }
  }

  private static CacheEntry compactPage(ByteBufferPool pool, int index) {
    return compactPage(pool, 13, index);
  }

  private static CacheEntry compactPage(ByteBufferPool pool, long fileId, int index) {
    var cp = new CachePointer(pool.acquireDirect(true, Intention.TEST), pool, fileId, index);
    cp.incrementReferrer();
    var entry = new CacheEntryImpl(fileId, index, cp, false, null);
    entry.acquireExclusiveLock();
    return entry;
  }

  private static HistogramSnapshot compactSnapshot(int length, BinarySerializer<Object> serializer,
      BinarySerializerFactory factory) {
    int keyOverhead = serializer.getObjectSize(factory, "");
    // Fixed 49-byte prefix, 48 bytes of one-bucket histogram structure and 1024 HLL bytes.
    int keyBytes = length - 49 - 48 - 2 * keyOverhead - 1024;
    EquiDepthHistogram histogram;
    if (length == 8096) {
      // Reserve a second bucket so its final boundary starts entirely in the continuation.
      String middle = "y".repeat(keyBytes - 20 - keyOverhead - 1);
      histogram = new EquiDepthHistogram(2, new Comparable<?>[] {"", middle, "z"},
          new long[] {7, 3}, new long[] {2, 2}, 10, null, 0);
    } else {
      histogram = new EquiDepthHistogram(1, new Comparable<?>[] {"", "z".repeat(keyBytes)},
          new long[] {10}, new long[] {4}, 10, null, 0);
    }
    var sketch = new HyperLogLogSketch();
    sketch.add(4);
    return new HistogramSnapshot(new IndexStatistics(12, 14, 2), histogram, 7, 17, 0, false,
        sketch, false);
  }

  private static void verifyCompactSnapshot(int length, BinarySerializer<Object> serializer,
      BinarySerializerFactory serializers, WALRecordsFactory factory, HistogramStatsPage first,
      HistogramStatsPage second) {
    var snapshot = compactSnapshot(length, serializer, serializers);
    var parts = HistogramStatsPage.prepareParts(snapshot, serializer, serializers);
    var blob = snapshot.histogram().serialize(serializer, serializers);
    if (length == 8096) {
      // The final boundary is preceded by the large middle key and followed by four counters.
      int finalKeyStart = 49 + blob.length - 4 * Long.BYTES
          - Integer.BYTES - Short.BYTES - 1;
      Assert.assertTrue(finalKeyStart >= 4048);
      assertKeyIdentity("z", snapshot.histogram().boundaries()[2]);
    }
    var image = ByteBuffer.allocate(length).order(ByteOrder.nativeOrder())
        .put(serializer.getId()).putLong(12).putLong(14).putLong(2).putLong(7).putLong(17)
        .putInt(blob.length).putInt(1024).put(blob);
    snapshot.hllSketch().writeTo(image.array(), image.position());
    Assert.assertEquals(length > 4048 ? 2 : 1, parts.length);
    byte[] sentinel = new byte[4096];
    Arrays.fill(sentinel, (byte) 0x5a);
    first.getCacheEntry().getCachePointer().getBuffer().put(0, sentinel);
    second.getCacheEntry().getCachePointer().getBuffer().put(0, sentinel);
    for (int role = 0; role < parts.length; role++) {
      var part = ByteBuffer.wrap(parts[role]).order(ByteOrder.nativeOrder());
      int offset = role * 4048;
      int extent = Math.min(4048, length - offset);
      Assert.assertEquals(20 + extent, part.remaining());
      Assert.assertEquals(2, part.getInt());
      Assert.assertEquals(length, part.getInt());
      Assert.assertEquals(role, part.getInt());
      Assert.assertEquals(ByteBuffer.wrap(parts[0]).order(ByteOrder.nativeOrder()).getLong(12),
          part.getLong());
      Assert.assertArrayEquals(Arrays.copyOfRange(image.array(), offset, offset + extent),
          Arrays.copyOfRange(parts[role], 20, parts[role].length));

      var op = new HistogramStatsPageWritePartOp(role, 13, 99, new LogSequenceNumber(1, 100),
          parts[role]);
      var decoded = (HistogramStatsPageWritePartOp) factory.fromStream(frameBytes(op));
      Assert.assertEquals(op, decoded);
      Assert.assertEquals(role, decoded.getPageIndex());
      Assert.assertEquals(13, decoded.getFileId());
      var page = role == 0 ? first : second;
      decoded.redo(page);
      byte[] expected = sentinel.clone();
      System.arraycopy(parts[role], 0, expected, 28, parts[role].length);
      byte[] actual = new byte[4096];
      page.getCacheEntry().getCachePointer().getBuffer().get(0, actual);
      Assert.assertArrayEquals(expected, actual);
      if (role == 0 && parts.length == 2) {
        Assert.assertThrows(IllegalArgumentException.class,
            () -> first.readStoredSnapshot(second, serializer, serializers));
      }
    }
    var read = first.readStoredSnapshot(second, serializer, serializers);
    if (length == 8096) {
      assertKeyIdentity("z", read.histogram().boundaries()[2]);
    }
    assertDurableFields(snapshot, read);
    Assert.assertTrue(Arrays.deepEquals(parts,
        HistogramStatsPage.prepareParts(read, serializer, serializers)));
    if (parts.length == 2) {
      Assert.assertThrows(IllegalArgumentException.class,
          () -> first.readStoredSnapshot(null, serializer, serializers));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void compactRedoPreservesSupportedKeysInBothSlotsAndIndependentEstimates() {
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    record KeyLayout(BinarySerializer<?> serializer, Comparable<?>... orderedKeys) {
    }
    var layouts = new KeyLayout[] {
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.BOOLEAN), false, true),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.BYTE),
            Byte.MIN_VALUE, (byte) 3, Byte.MAX_VALUE),
        new KeyLayout(CharSerializer.INSTANCE, Character.MIN_VALUE, 'é', Character.MAX_VALUE),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.SHORT),
            Short.MIN_VALUE, (short) 4, Short.MAX_VALUE),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.INTEGER),
            Integer.MIN_VALUE, 5, Integer.MAX_VALUE),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.LONG),
            Long.MIN_VALUE, 6L, Long.MAX_VALUE),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.FLOAT),
            Float.NEGATIVE_INFINITY, -Float.MAX_VALUE, -0.0f, 0.0f, 7.5f, Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NaN),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.DOUBLE),
            Double.NEGATIVE_INFINITY, -Double.MAX_VALUE, -0.0, 0.0, 8.5, Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NaN),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.DATE),
            new Date(-1234), new Date(0), new Date(1234)),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.DATETIME),
            new Date(-5678), new Date(0), new Date(1234)),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.LINK),
            new RecordId(Short.MIN_VALUE, Long.MIN_VALUE), new RecordId(3, 9),
            new RecordId(Short.MAX_VALUE, Long.MAX_VALUE)),
        new KeyLayout(StreamSerializerRID.INSTANCE,
            new RecordId(Short.MIN_VALUE, Long.MIN_VALUE), new RecordId(-2, Long.MAX_VALUE),
            new RecordId(3, 9)),
        new KeyLayout(serializers.getObjectSerializer(CompactedLinkSerializer.ID),
            new RecordId(-2, Long.MIN_VALUE), new RecordId(3, 0), new RecordId(3, 9),
            new RecordId(3, 256), new RecordId(Short.MAX_VALUE, Long.MAX_VALUE)),
        new KeyLayout(MultiValueEntrySerializer.INSTANCE,
            multiValueEntry(10, 3, 9), multiValueEntry(11, Short.MIN_VALUE, Long.MAX_VALUE),
            multiValueEntry(12, Short.MAX_VALUE, Long.MIN_VALUE)),
        new KeyLayout(serializers.getObjectSerializer(PropertyTypeInternal.DECIMAL),
            new BigDecimal("-129"), new BigDecimal("-128"), new BigDecimal("-1.25"),
            new BigDecimal("0"), new BigDecimal("0.000"),
            new BigDecimal(BigInteger.valueOf(123), -2)),
        new KeyLayout(StringSerializer.INSTANCE, "", "é🎈", "\uD800", "\uDC00"),
        new KeyLayout(UTF8Serializer.INSTANCE, "", "é🎈", "🎈")
    };
    var pool = new ByteBufferPool(4096, new DirectMemoryAllocator(), 0);
    var page = page2(compactPage(pool, 0));
    try {
      for (var layout : layouts) {
        var serializer = (BinarySerializer<Object>) layout.serializer();
        var keys = layout.orderedKeys();
        for (int i = 0; i < keys.length; i++) {
          // Rotate keys through the MCV and boundaries without relying on the codec for order.
          // Boolean has only two values, so its boundaries tie but still differ from the MCV.
          int a = (i + 1) % keys.length;
          int b = (i + 2) % keys.length;
          if (keys.length == 2) {
            b = a;
          }
          var first = keys[Math.min(a, b)];
          var last = keys[Math.max(a, b)];
          var mcv = keys[i];
          var histogram = new EquiDepthHistogram(1, new Comparable<?>[] {first, last},
              new long[] {2}, new long[] {8}, 3, mcv, 9);
          var snapshot = new HistogramSnapshot(new IndexStatistics(4, 8, 0),
              histogram, 7, 11, 0, false, null, false);
          var read = replayInline(snapshot, serializer, serializers, factory, page);
          assertDurableFields(snapshot, read);
          for (var value : new EquiDepthHistogram[] {snapshot.histogram(), read.histogram()}) {
            assertKeyIdentity(first, value.boundaries()[0]);
            assertKeyIdentity(last, value.boundaries()[1]);
            assertKeyIdentity(mcv, value.mcvValue());
          }
        }
      }
    } finally {
      releaseCompactPage(page);
      pool.clear();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void compactKeylessRedoNeedsNoSupportedKeyLayoutAndPreservesLoadedSpill() {
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var serializer = (BinarySerializer<Object>) serializers
        .getObjectSerializer(CompositeKeySerializer.ID);
    var pool = new ByteBufferPool(4096, new DirectMemoryAllocator(), 0);
    var first = page2(compactPage(pool, 0));
    var second = page2(compactPage(pool, 1));
    try {
      var empty = new HistogramSnapshot(new IndexStatistics(0, 0, 0),
          null, 0, 0, 0, false, null, false);
      var counts = new HistogramSnapshot(new IndexStatistics(1000, 500, 5),
          null, 7, 900, 0, false, null, false);
      for (var snapshot : new HistogramSnapshot[] {empty, counts}) {
        assertDurableFields(snapshot, replayInline(snapshot, serializer, serializers, factory,
            first));
      }

      var sketch = new HyperLogLogSketch();
      for (int key = 0; key < 2048; key++) {
        sketch.add(key);
      }
      var spilled = new HistogramSnapshot(counts.stats(), null, 7, 900, 0, false, sketch, true);
      first.writeSnapshot(spilled, serializer.getId(), serializer, serializers);
      HistogramStatsPage.writeHllToPage1(second.getCacheEntry(), sketch);
      var unresolved = first.readSnapshot(serializer, serializers);
      Assert.assertTrue(unresolved.hllOnPage1());
      Assert.assertNull(unresolved.hllSketch());
      Assert.assertThrows(IllegalArgumentException.class,
          () -> HistogramStatsPage.prepareParts(unresolved, serializer, serializers));

      var loaded = new HistogramSnapshot(unresolved.stats(), unresolved.histogram(),
          unresolved.mutationsSinceRebalance(), unresolved.totalCountAtLastBuild(), 0, false,
          HistogramStatsPage.readHllFromPage1(second.getCacheEntry()), true);
      assertDurableFields(spilled, replayInline(loaded, serializer, serializers, factory, first));
    } finally {
      releaseCompactPage(first);
      releaseCompactPage(second);
      pool.clear();
    }
  }

  /** Also checks that lossy UTF-8 keys fail with page assertions disabled. */
  @Test
  @SuppressWarnings("unchecked")
  public void compactPreparationRefusesUnsupportedLayoutAndOverBudgetKeys() {
    expectPageAssertions();
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var tuple = new CompositeKey(1, 2);
    var tupleSerializer = (BinarySerializer<Object>) serializers
        .getObjectSerializer(CompositeKeySerializer.ID);
    for (var mcv : new CompositeKey[] {null, tuple}) {
      var error = Assert.assertThrows(IllegalArgumentException.class,
          () -> prepareWithKeys(tuple, mcv, tupleSerializer, serializers));
      Assert.assertEquals("Unsupported statistics histogram key layout", error.getMessage());
    }

    for (Object[] example : new Object[][] {
        {UTF8Serializer.INSTANCE, "z".repeat(8096)},
        {UTF8Serializer.INSTANCE, "é".repeat(4050)},
        {StringSerializer.INSTANCE, "z".repeat(4050)},
        {DecimalSerializer.INSTANCE, new BigDecimal(BigInteger.ONE.shiftLeft(8096 * Byte.SIZE))}}) {
      var serializer = (BinarySerializer<Object>) example[0];
      var key = (Comparable<?>) example[1];
      var error = Assert.assertThrows(IllegalArgumentException.class,
          () -> prepareWithKeys(key, null, serializer, serializers));
      Assert.assertEquals("Statistics image exceeds two-page budget", error.getMessage());
    }

    var utf8 = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    for (String key : new String[] {"\uD800", "\uDC00"}) {
      var boundaryError = Assert.assertThrows(IllegalArgumentException.class,
          () -> prepareWithKeys(key, null, utf8, serializers));
      Assert.assertEquals("Unrepresentable statistics histogram key", boundaryError.getMessage());
      var mcvError = Assert.assertThrows(IllegalArgumentException.class,
          () -> prepareWithKeys("valid", key, utf8, serializers));
      Assert.assertEquals("Unrepresentable statistics histogram key", mcvError.getMessage());
    }

    int buckets = 404;
    var boundaries = new Comparable<?>[buckets + 1];
    Arrays.fill(boundaries, 1);
    var histogram = new EquiDepthHistogram(buckets, boundaries, new long[buckets],
        new long[buckets], 0, null, 0);
    var error = Assert.assertThrows(IllegalArgumentException.class,
        () -> HistogramStatsPage.prepareParts(
            new HistogramSnapshot(new IndexStatistics(0, 0, 0), histogram, 0, 0, 0, false,
                null, false),
            serializers.getObjectSerializer(PropertyTypeInternal.INTEGER), serializers));
    Assert.assertEquals("Statistics image exceeds two-page budget", error.getMessage());
  }

  @Test
  public void compactPreparationAssertsEachUnrepresentableKeyBeforeReturningParts() {
    requirePageAssertions();
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    record KeyCase(BinarySerializer<?> serializer, Comparable<?> valid, Comparable<?> invalid) {
    }
    var rows = new KeyCase[] {
        new KeyCase(serializers.getObjectSerializer(PropertyTypeInternal.FLOAT),
            Float.NaN, Float.intBitsToFloat(0x7fc00001)),
        new KeyCase(serializers.getObjectSerializer(PropertyTypeInternal.DOUBLE),
            Double.NaN, Double.longBitsToDouble(0x7ff8000000000001L)),
        new KeyCase(DecimalSerializer.INSTANCE, new BigDecimal("1.25"), new BigDecimal("1.25") {
          private static final long serialVersionUID = 1L;
        }),
        new KeyCase(serializers.getObjectSerializer(PropertyTypeInternal.LINK),
            new RecordId(3, 9), new ContextualRecordId("#3:9")),
        new KeyCase(MultiValueEntrySerializer.INSTANCE,
            multiValueEntry(10, Short.MAX_VALUE, 9),
            multiValueEntry(10, Short.MAX_VALUE + 1, 9)),
        new KeyCase(serializers.getObjectSerializer(PropertyTypeInternal.DATE),
            new Date(1234), new Timestamp(1234))
    };
    for (var row : rows) {
      @SuppressWarnings("unchecked")
      var serializer = (BinarySerializer<Object>) row.serializer();
      var valid = row.valid();
      var control = singleBucketSnapshot(valid, valid, valid, 2, 1, 2, 2);
      Assert.assertEquals(1,
          HistogramStatsPage.prepareParts(control, serializer, serializers).length);
      // Replace just one key at a time, including both boundary checks.
      for (int slot = 0; slot < 3; slot++) {
        var keys = new Comparable<?>[] {valid, valid, valid};
        keys[slot] = row.invalid();
        var invalid = singleBucketSnapshot(keys[0], keys[1], keys[2], 2, 1, 2, 2);
        assertPreparationRefused("serializer " + serializer.getId() + ", key slot " + slot,
            "Unrepresentable statistics histogram key", invalid, serializer, serializers);
      }
    }
  }

  @Test
  public void compactPreparationAssertsDescendingBoundariesBeforeReturningParts() {
    requirePageAssertions();
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    BinarySerializer<Object> serializer =
        serializers.getObjectSerializer(PropertyTypeInternal.INTEGER);
    var control = singleBucketSnapshot(1, 2, 1, 2, 1, 2, 2);
    Assert.assertEquals(1,
        HistogramStatsPage.prepareParts(control, serializer, serializers).length);
    var invalid = singleBucketSnapshot(1, 0, 1, 2, 1, 2, 2);
    assertPreparationRefused("final boundary",
        "Statistics histogram boundaries must be nondecreasing",
        invalid, serializer, serializers);
  }

  @Test
  public void compactPreparationAssertsInvalidCountersBeforeReturningParts() {
    requirePageAssertions();
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    BinarySerializer<Object> serializer =
        serializers.getObjectSerializer(PropertyTypeInternal.INTEGER);
    // Zero non-null entries are valid, but negative bucket or MCV counters are not.
    var control = singleBucketSnapshot(1, 2, 1, 0, 0, 0, 0);
    Assert.assertEquals(1,
        HistogramStatsPage.prepareParts(control, serializer, serializers).length);
    assertPreparationRefused("bucket frequency", "Negative statistics bucket count",
        singleBucketSnapshot(1, 2, 1, -1, 0, 0, 0), serializer, serializers);
    assertPreparationRefused("bucket distinct count", "Negative statistics bucket count",
        singleBucketSnapshot(1, 2, 1, 0, -1, 0, 0), serializer, serializers);
    assertPreparationRefused("MCV frequency", "Negative statistics MCV frequency",
        singleBucketSnapshot(1, 2, 1, 0, 0, 0, -1), serializer, serializers);

    String[] fields = {"total count", "distinct count", "null count", "mutations",
        "total count at last build"};
    for (int i = 0; i < fields.length; i++) {
      long[] counts = new long[fields.length];
      counts[i] = -1;
      var invalid = new HistogramSnapshot(new IndexStatistics(counts[0], counts[1], counts[2]),
          control.histogram(), counts[3], counts[4], 0, false, null, false);
      assertPreparationRefused(fields[i], "Invalid statistics snapshot counts", invalid,
          serializer, serializers);
    }
    var excessNulls = new HistogramSnapshot(new IndexStatistics(0, 0, 1),
        control.histogram(), 0, 0, 0, false, null, false);
    assertPreparationRefused("null count exceeds total", "Invalid statistics snapshot counts",
        excessNulls, serializer, serializers);
  }

  private static HistogramSnapshot singleBucketSnapshot(Comparable<?> first, Comparable<?> last,
      Comparable<?> mcv, long frequency, long distinctCount, long nonNullCount, long mcvFrequency) {
    var histogram = new EquiDepthHistogram(1, new Comparable<?>[] {first, last},
        new long[] {frequency}, new long[] {distinctCount}, nonNullCount, mcv, mcvFrequency);
    return new HistogramSnapshot(new IndexStatistics(nonNullCount, nonNullCount, 0),
        histogram, 0, nonNullCount, 0, false, null, false);
  }

  private static void assertPreparationRefused(String field, String reason,
      HistogramSnapshot invalid, BinarySerializer<Object> serializer,
      BinarySerializerFactory serializers) {
    var error = Assert.assertThrows(field, AssertionError.class,
        () -> HistogramStatsPage.prepareParts(invalid, serializer, serializers));
    Assert.assertEquals(field, reason, error.getMessage());
  }

  private static boolean expectPageAssertions() {
    boolean expected = Boolean.parseBoolean(
        System.getProperty("histogram.prepare.expectAssertions", "true"));
    Assert.assertEquals(expected, HistogramStatsPage.class.desiredAssertionStatus());
    return expected;
  }

  private static void requirePageAssertions() {
    Assume.assumeTrue("Compact writer assertion checks are disabled", expectPageAssertions());
  }

  private static MultiValueEntry multiValueEntry(long id, int collectionId,
      long collectionPosition) {
    // The package-private constructor lets the fixture set all fields without using a codec.
    try {
      var constructor =
          MultiValueEntry.class.getDeclaredConstructor(long.class, int.class, long.class);
      constructor.setAccessible(true);
      return constructor.newInstance(id, collectionId, collectionPosition);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Cannot construct a MultiValueEntry test key", e);
    }
  }

  private static void prepareWithKeys(Comparable<?> boundary, Comparable<?> mcv,
      BinarySerializer<Object> serializer, BinarySerializerFactory serializers) {
    var histogram = new EquiDepthHistogram(1, new Comparable<?>[] {boundary, boundary},
        new long[] {2}, new long[] {1}, 2, mcv, mcv == null ? 0 : 2);
    var snapshot = new HistogramSnapshot(new IndexStatistics(2, 1, 0), histogram,
        0, 2, 0, false, null, false);
    HistogramStatsPage.prepareParts(snapshot, serializer, serializers);
  }

  private static HistogramSnapshot replayInline(HistogramSnapshot snapshot,
      BinarySerializer<Object> serializer, BinarySerializerFactory serializers,
      WALRecordsFactory factory, HistogramStatsPage page) {
    var parts = HistogramStatsPage.prepareParts(snapshot, serializer, serializers);
    Assert.assertEquals(1, parts.length);
    var op = new HistogramStatsPageWritePartOp(0, 13, 99, new LogSequenceNumber(1, 100), parts[0]);
    ((HistogramStatsPageWritePartOp) factory.fromStream(frameBytes(op))).redo(page);
    var read = page.readStoredSnapshot(null, serializer, serializers);
    Assert.assertTrue(Arrays.deepEquals(parts,
        HistogramStatsPage.prepareParts(read, serializer, serializers)));
    return read;
  }

  private static void assertDurableFields(HistogramSnapshot expected, HistogramSnapshot actual) {
    Assert.assertEquals(expected.stats(), actual.stats());
    Assert.assertEquals(expected.mutationsSinceRebalance(), actual.mutationsSinceRebalance());
    Assert.assertEquals(expected.totalCountAtLastBuild(), actual.totalCountAtLastBuild());
    Assert.assertFalse(actual.hllOnPage1());
    if (expected.histogram() == null) {
      Assert.assertNull(actual.histogram());
    } else {
      var histogram = expected.histogram();
      var read = actual.histogram();
      Assert.assertEquals(histogram.bucketCount(), read.bucketCount());
      for (int i = 0; i < histogram.boundaries().length; i++) {
        assertKeyIdentity(histogram.boundaries()[i], read.boundaries()[i]);
      }
      Assert.assertArrayEquals(histogram.frequencies(), read.frequencies());
      Assert.assertArrayEquals(histogram.distinctCounts(), read.distinctCounts());
      Assert.assertEquals(histogram.nonNullCount(), read.nonNullCount());
      assertKeyIdentity(histogram.mcvValue(), read.mcvValue());
      Assert.assertEquals(histogram.mcvFrequency(), read.mcvFrequency());
    }
    if (expected.hllSketch() == null) {
      Assert.assertNull(actual.hllSketch());
    } else {
      byte[] expectedRegisters = new byte[1024];
      byte[] actualRegisters = new byte[1024];
      expected.hllSketch().writeTo(expectedRegisters, 0);
      actual.hllSketch().writeTo(actualRegisters, 0);
      Assert.assertArrayEquals(expectedRegisters, actualRegisters);
    }
  }

  static void assertKeyIdentity(Comparable<?> expected, Comparable<?> actual) {
    if (expected == null) {
      Assert.assertNull(actual);
      return;
    }
    Assert.assertNotNull(actual);
    Assert.assertSame(expected.getClass(), actual.getClass());
    switch (expected) {
      case Boolean value -> Assert.assertTrue(value.booleanValue() == (Boolean) actual);
      case Byte value -> Assert.assertEquals(value.byteValue(), ((Byte) actual).byteValue());
      case Character value ->
          Assert.assertEquals(value.charValue(), ((Character) actual).charValue());
      case Short value -> Assert.assertEquals(value.shortValue(), ((Short) actual).shortValue());
      case Integer value -> Assert.assertEquals(value.intValue(), ((Integer) actual).intValue());
      case Long value -> Assert.assertEquals(value.longValue(), ((Long) actual).longValue());
      case Float value -> Assert.assertEquals(Float.floatToRawIntBits(value),
          Float.floatToRawIntBits((Float) actual));
      case Double value -> Assert.assertEquals(Double.doubleToRawLongBits(value),
          Double.doubleToRawLongBits((Double) actual));
      case Date value -> Assert.assertEquals(value.getTime(), ((Date) actual).getTime());
      case RecordId value -> {
        var read = (RecordId) actual;
        Assert.assertEquals(value.collectionId(), read.collectionId());
        Assert.assertEquals(value.collectionPosition(), read.collectionPosition());
      }
      case BigDecimal value -> {
        var read = (BigDecimal) actual;
        Assert.assertArrayEquals(value.unscaledValue().toByteArray(),
            read.unscaledValue().toByteArray());
        Assert.assertEquals(value.scale(), read.scale());
      }
      case String value ->
          Assert.assertArrayEquals(value.toCharArray(), ((String) actual).toCharArray());
      case MultiValueEntry value -> {
        var read = (MultiValueEntry) actual;
        Assert.assertEquals(value.id, read.id);
        Assert.assertEquals(value.collectionId, read.collectionId);
        Assert.assertEquals(value.collectionPosition, read.collectionPosition);
      }
      default -> Assert.fail("Unlisted compact key type: " + expected.getClass().getName());
    }
  }

  @Test
  @Category(SequentialTest.class)
  public void compactDateSnapshotKeepsWriterInstantsAfterSerializedRedoInAnotherTimeZone() {
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    BinarySerializer<Object> serializer =
        serializers.getObjectSerializer(PropertyTypeInternal.DATE);
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);
    var pool = new ByteBufferPool(4096, new DirectMemoryAllocator(), 0);
    var page = page2(compactPage(pool, 0));
    var originalZone = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      var first = Date.from(Instant.parse("2024-01-01T00:00:00Z"));
      var last = Date.from(Instant.parse("2024-01-03T00:00:00Z"));
      var mcv = Date.from(Instant.parse("2024-01-02T12:34:56.789Z"));
      var histogram = new EquiDepthHistogram(1, new Comparable<?>[] {first, last},
          new long[] {10}, new long[] {3}, 10, mcv, 2);
      var snapshot = new HistogramSnapshot(new IndexStatistics(10, 3, 0),
          histogram, 7, 11, 0, false, null, false);
      var parts = HistogramStatsPage.prepareParts(snapshot, serializer, serializers);
      Assert.assertEquals(1, parts.length);
      var op = new HistogramStatsPageWritePartOp(0, 13, 99, new LogSequenceNumber(1, 100),
          parts[0]);

      TimeZone.setDefault(TimeZone.getTimeZone("GMT+01:00"));
      Assert.assertNotEquals(first, serializer.preprocess(serializers, first));
      var decoded = (HistogramStatsPageWritePartOp) factory.fromStream(frameBytes(op));
      decoded.redo(page);
      var read = page.readStoredSnapshot(null, serializer, serializers);
      assertDurableFields(snapshot, read);
      Assert.assertTrue(Arrays.deepEquals(parts,
          HistogramStatsPage.prepareParts(read, serializer, serializers)));
    } finally {
      TimeZone.setDefault(originalZone);
      releaseCompactPage(page);
      pool.clear();
    }
  }

  /** Pins both WAL frames, including the one-byte continuation, without using writer bytes. */
  @Test
  @SuppressWarnings("unchecked")
  public void compactWalMatchesIndependentFirstSpillFrames() {
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var serializer = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    String end = "z".repeat(3948);
    var histogram = new EquiDepthHistogram(1, new Comparable<?>[] {"", end},
        new long[] {10}, new long[] {4}, 10, null, 0);
    var snapshot = new HistogramSnapshot(new IndexStatistics(12, 14, 2), histogram, 7, 17, 0,
        false, null, false);
    var parts = HistogramStatsPage.prepareParts(snapshot, serializer, serializers);

    byte[] image = ByteBuffer.allocate(4049).order(ByteOrder.nativeOrder())
        .put((byte) 25).putLong(12).putLong(14).putLong(2).putLong(7).putLong(17)
        .putInt(4000).putInt(0).putInt(1).putLong(10).putLong(0).putInt(0)
        .putInt(2).putShort((short) 0).putInt(3950).putShort((short) 3948)
        .put(end.getBytes(StandardCharsets.US_ASCII))
        .putLong(10).putLong(4).array();
    // Reference libxxhash XXH64 over the native-order fixture, with seed zero.
    long hash = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        ? 0x485620ad1de19fb0L : 0x57cc419514396168L;
    Assert.assertEquals(2, parts.length);
    for (int role = 0; role < 2; role++) {
      int payload = role == 0 ? 4048 : 1;
      byte[] expected = ByteBuffer.allocate(62 + payload).order(ByteOrder.nativeOrder())
          .putShort((short) 299).putInt(0).putLong(99)
          .putLong(role).putLong(13).putLong(1).putInt(100)
          .putInt(2).putInt(4049).putInt(role).putLong(hash)
          .put(image, role * 4048, payload).array();
      var op = new HistogramStatsPageWritePartOp(role, 13, 99,
          new LogSequenceNumber(1, 100), parts[role]);
      // The factory adds a two-byte type ID and a four-byte metadata slot.
      Assert.assertEquals(expected.length - 6, op.serializedSize());
      byte[] actual = ByteBuffer.allocate(expected.length).order(ByteOrder.nativeOrder())
          .putShort((short) 299).array();
      Assert.assertEquals(actual.length, op.toStream(actual, 6));
      Assert.assertArrayEquals(expected, actual);
      Assert.assertEquals(op, factory.fromStream(expected));
      Assert.assertEquals(op, factory.fromStream(frameBytes(op)));
    }
  }

  /** Matching continuation bytes cannot substitute for a page in the same file. */
  @Test
  @SuppressWarnings("unchecked")
  public void compactSnapshotRejectsByteIdenticalContinuationFromAnotherFile() {
    var serializers = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    var serializer = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    var snapshot = compactSnapshot(4049, serializer, serializers);
    var parts = HistogramStatsPage.prepareParts(snapshot, serializer, serializers);
    var pool = new ByteBufferPool(4096, new DirectMemoryAllocator(), 0);
    var first = page2(compactPage(pool, 0));
    HistogramStatsPage wrongFile = null;
    try {
      wrongFile = page2(compactPage(pool, 14, 1));
      first.writePart(parts[0]);
      wrongFile.writePart(parts[1]);
      var continuation = wrongFile;
      var error = Assert.assertThrows(IllegalArgumentException.class,
          () -> first.readStoredSnapshot(continuation, serializer, serializers));
      Assert.assertEquals("Statistics continuation is in a different file", error.getMessage());
    } finally {
      releaseCompactPage(first);
      if (wrongFile != null) {
        releaseCompactPage(wrongFile);
      }
      pool.clear();
    }
  }

  private static void releaseCompactPage(HistogramStatsPage page) {
    var entry = page.getCacheEntry();
    entry.releaseExclusiveLock();
    entry.getCachePointer().decrementReferrer();
  }

  /** Valid container extents can still be too short for a snapshot WAL record. */
  @Test
  public void compactWalRejectsOpaqueImagesShorterThanSnapshotPrefix() {
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);
    for (int length : new int[] {0, 48, 49}) {
      byte[] image = new byte[length];
      if (length == 49) {
        image[0] = 8;
      }
      byte[] part = HistogramStatsPageContinuationCodec.encode(image)[0];
      byte[] frame = ByteBuffer.allocate(42 + part.length).order(ByteOrder.nativeOrder())
          .putShort((short) 299).putInt(0).putLong(99).putLong(0).putLong(13)
          .putLong(1).putInt(100).put(part).array();
      if (length < 49) {
        var error = Assert.assertThrows(IllegalArgumentException.class,
            () -> factory.fromStream(frame));
        Assert.assertEquals("Statistics WAL image is shorter than its snapshot prefix",
            error.getMessage());
      } else {
        Assert.assertArrayEquals(frame, frameBytes(factory.fromStream(frame)));
      }
    }
  }

  private static byte[] frameBytes(WriteableWALRecord op) {
    var frame = WALRecordsFactory.toStream(op);
    byte[] bytes = new byte[frame.limit()];
    frame.get(0, bytes);
    return bytes;
  }

  /** The real factory rejects malformed record bodies without reaching page redo. */
  @Test
  public void compactWalRejectsMalformedExtentAndFullWidthAddress() {
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);
    var frame = ByteBuffer.allocate(111).order(ByteOrder.nativeOrder())
        .putShort((short) 299).putInt(0).putLong(99).putLong(0).putLong(13).putLong(1).putInt(100)
        .putInt(2).putInt(49).putInt(0).putLong(0x3215e2245d6bf28eL).put((byte) 8);
    for (int length : new int[] {6, 41, 61, 110, 112}) {
      Assert.assertThrows(RuntimeException.class,
          () -> factory.fromStream(Arrays.copyOf(frame.array(), length)));
    }
    for (long address : new long[] {1, -1, 1L << 32}) {
      frame.putLong(14, address);
      Assert.assertThrows(IllegalArgumentException.class, () -> factory.fromStream(frame.array()));
    }
    frame.putLong(14, 0);
    for (int[] field : new int[][] {{42, 1}, {46, -1}, {46, 8097}, {50, 2}}) {
      int original = frame.getInt(field[0]);
      frame.putInt(field[0], field[1]);
      Assert.assertThrows(IllegalArgumentException.class, () -> factory.fromStream(frame.array()));
      frame.putInt(field[0], original);
    }
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
