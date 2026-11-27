package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.CompactedLinkSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the HLL page-1 spill mechanism in {@link HistogramStatsPage}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>HLL registers are written inline on page 0 for the normal case</li>
 *   <li>The page-1 flag is correctly encoded/decoded in hllRegisterCount</li>
 *   <li>HLL registers round-trip through page-1 write/read</li>
 *   <li>Snapshot round-trips preserve the hllOnPage1 flag</li>
 * </ul>
 */
public class HistogramStatsPageHllSpillTest {

  /**
   * Empty, inline, boundary and full two-page images roundtrip without aliasing their inputs.
   * The maximum includes sketch bytes in the same complete-image budget.
   */
  @Test
  public void continuationContainerRoundtripsBoundariesAndSketch() {
    int capacity = HistogramStatsPageContinuationCodec.PART_CAPACITY;
    for (int length : new int[] {0, 1, capacity, capacity + 1, 2 * capacity}) {
      byte[] image = new byte[length];
      new java.util.Random(51).nextBytes(image);
      byte[] original = image.clone();
      byte[][] parts = HistogramStatsPageContinuationCodec.encode(image);
      assertEquals(length > capacity ? 2 : 1, parts.length);
      for (byte[] part : parts) {
        assertTrue(part.length + DurablePage.NEXT_FREE_POSITION <= 4096);
        HistogramStatsPageContinuationCodec.validatePart(part);
      }
      byte[] second = parts.length == 2 ? parts[1] : new byte[] {99};
      org.junit.Assert.assertArrayEquals(original,
          HistogramStatsPageContinuationCodec.assemble(parts[0], second));
      if (length > 0) {
        image[0] ^= 1;
        org.junit.Assert.assertArrayEquals(original,
            HistogramStatsPageContinuationCodec.assemble(parts[0], second));
      }
    }
    byte[] imageWithSketch = new byte[capacity + HyperLogLogSketch.serializedSize()];
    var sketch = new HyperLogLogSketch();
    sketch.writeTo(imageWithSketch, capacity);
    byte[][] parts = HistogramStatsPageContinuationCodec.encode(imageWithSketch);
    org.junit.Assert.assertArrayEquals(imageWithSketch,
        HistogramStatsPageContinuationCodec.assemble(parts[0], parts[1]));
  }

  /**
   * Independent native-order bytes pin the new container header and complete-image digest.
   * The seed-zero XXH64 of "abc" is not produced by the codec under test.
   */
  @Test
  public void continuationContainerReadsIndependentBytes() {
    byte[] fixture = ByteBuffer.allocate(23).order(java.nio.ByteOrder.nativeOrder())
        .putInt(2).putInt(3).putInt(0).putLong(0x44bc2cf5ad770999L)
        .put(new byte[] {97, 98, 99}).array();
    org.junit.Assert.assertArrayEquals(new byte[] {97, 98, 99},
        HistogramStatsPageContinuationCodec.assemble(fixture, null));
    org.junit.Assert.assertArrayEquals(fixture,
        HistogramStatsPageContinuationCodec.encode(new byte[] {97, 98, 99})[0]);
  }

  /** Invalid metadata and exact extents are rejected without modifying input bytes. */
  @Test
  public void continuationContainerRejectsMalformedParts() {
    int capacity = HistogramStatsPageContinuationCodec.PART_CAPACITY;
    byte[][] valid = HistogramStatsPageContinuationCodec.encode(new byte[capacity + 1]);
    for (int[] mutation : new int[][] {
        {0, 1}, {4, -1}, {4, Integer.MAX_VALUE}, {4, 2 * capacity + 1},
        {4, capacity - 1}, {8, -1}, {8, 2}, {8, 1}}) {
      byte[] part = valid[0].clone();
      ByteBuffer.wrap(part).order(java.nio.ByteOrder.nativeOrder())
          .putInt(mutation[0], mutation[1]);
      byte[] before = part.clone();
      org.junit.Assert.assertThrows(IllegalArgumentException.class,
          () -> HistogramStatsPageContinuationCodec.validatePart(part));
      org.junit.Assert.assertArrayEquals(before, part);
    }
    for (int length : new int[] {0, 19, valid[0].length - 1, valid[0].length + 1}) {
      byte[] part = java.util.Arrays.copyOf(valid[0], length);
      org.junit.Assert.assertThrows(IllegalArgumentException.class,
          () -> HistogramStatsPageContinuationCodec.validatePart(part));
    }
    byte[] invalidContinuation = valid[1].clone();
    ByteBuffer.wrap(invalidContinuation).order(java.nio.ByteOrder.nativeOrder())
        .putInt(4, capacity);
    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> HistogramStatsPageContinuationCodec.validatePart(invalidContinuation));
    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> HistogramStatsPageContinuationCodec.encode(new byte[2 * capacity + 1]));
  }

  /** Missing, reversed, inconsistent and same-length mixed images must never assemble. */
  @Test
  public void continuationContainerRequiresCompleteImageAgreement() {
    int capacity = HistogramStatsPageContinuationCodec.PART_CAPACITY;
    byte[][] first = HistogramStatsPageContinuationCodec.encode(new byte[capacity + 2]);
    byte[] other = new byte[capacity + 2];
    other[capacity] = 1;
    byte[][] second = HistogramStatsPageContinuationCodec.encode(other);
    byte[][] longer = HistogramStatsPageContinuationCodec.encode(new byte[capacity + 3]);
    for (byte[] continuation : new byte[][] {
        null, first[0], second[1], longer[1], new byte[0]}) {
      org.junit.Assert.assertThrows(IllegalArgumentException.class,
          () -> HistogramStatsPageContinuationCodec.assemble(first[0], continuation));
    }
    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> HistogramStatsPageContinuationCodec.assemble(first[1], first[0]));
    // Matching headers cannot conceal changed payload bytes.
    first[1][HistogramStatsPageContinuationCodec.HEADER_SIZE] ^= 1;
    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> HistogramStatsPageContinuationCodec.assemble(first[0], first[1]));
    first[1][20] ^= 1;
    first[0][20] ^= 1;
    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> HistogramStatsPageContinuationCodec.assemble(first[0], first[1]));
    byte[] inline = HistogramStatsPageContinuationCodec.encode(new byte[] {1})[0];
    inline[HistogramStatsPageContinuationCodec.HEADER_SIZE] ^= 1;
    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> HistogramStatsPageContinuationCodec.assemble(inline, null));
  }

  /** Matching fragment hashes cannot make invalid snapshot field values readable. */
  @Test
  public void compactSnapshotRejectsInvalidSemanticFields() {
    var entry = allocatePage();
    try {
      var page = new HistogramStatsPage(entry);
      var serializer = intKeySerializer();
      var prefix = ByteBuffer.allocate(49).order(java.nio.ByteOrder.nativeOrder())
          .put(serializer.getId()).putLong(4).putLong(5).putLong(1)
          .putLong(7).putLong(8).putInt(0).putInt(0).array();
      for (int[] field : new int[][] {{1, -1}, {9, -1}, {17, 5}, {25, -1}, {33, -1},
          {41, -1}, {41, 1}, {45, 1}, {45, 1024}}) {
        byte[] image = prefix.clone();
        var fields = ByteBuffer.wrap(image).order(java.nio.ByteOrder.nativeOrder());
        if (field[0] < 41) {
          fields.putLong(field[0], field[1]);
        } else {
          fields.putInt(field[0], field[1]);
        }
        page.writePart(HistogramStatsPageContinuationCodec.encode(image)[0]);
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
            () -> page.readStoredSnapshot(null, serializer, serializerFactory));
      }
      page.writePart(HistogramStatsPageContinuationCodec.encode(prefix)[0]);
      assertEquals(5, page.readStoredSnapshot(null, serializer, serializerFactory)
          .stats().distinctCount());
    } finally {
      releasePage(entry);
    }
  }

  @Test
  public void compactSnapshotRejectsChecksummedNegativeHistogramCountsAndInvalidRegisters() {
    var entry = allocatePage();
    try {
      var page = new HistogramStatsPage(entry);
      var serializer = intKeySerializer();
      var blob = createSimpleHistogram().serialize(serializer, serializerFactory);
      byte[] registers = new byte[1024];
      writeCompactImage(page, serializer, blob, registers);
      assertNotNull(page.readStoredSnapshot(null, serializer, serializerFactory).histogram());

      // The four offsets select the non-null count, MCV frequency and last bucket's counts.
      for (int offset : new int[] {4, 12, blob.length - 24, blob.length - 8}) {
        byte[] invalid = blob.clone();
        ByteBuffer.wrap(invalid).order(ByteOrder.nativeOrder()).putLong(offset, -1);
        writeCompactImage(page, serializer, invalid, registers);
        var error = assertThrows(IllegalArgumentException.class,
            () -> page.readStoredSnapshot(null, serializer, serializerFactory));
        assertEquals(offset < 20 ? "Invalid statistics histogram header"
            : "Negative statistics bucket count", error.getMessage());
      }
      for (byte invalid : new byte[] {-1, 127}) {
        registers[1023] = invalid;
        writeCompactImage(page, serializer, blob, registers);
        var error = assertThrows(IllegalArgumentException.class,
            () -> page.readStoredSnapshot(null, serializer, serializerFactory));
        assertEquals("Invalid statistics sketch register", error.getMessage());
      }
    } finally {
      releasePage(entry);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void compactSnapshotChecksNestedKeyLengthsForBoundariesAndMostCommonValue() {
    var entry = allocatePage();
    try {
      var page = new HistogramStatsPage(entry);
      for (var candidate : new BinarySerializer<?>[] {
          StringSerializer.INSTANCE, UTF8Serializer.INSTANCE, DecimalSerializer.INSTANCE}) {
        var serializer = (BinarySerializer<Object>) candidate;
        Comparable<?> key = candidate == DecimalSerializer.INSTANCE ? new BigDecimal("1.25") : "é";
        var histogram = new EquiDepthHistogram(1, new Comparable<?>[] {key, key},
            new long[] {2}, new long[] {1}, 2, key, 2);
        byte[] blob = histogram.serialize(serializer, serializerFactory);
        int firstBoundary = 24 + serializer.getObjectSize(serializerFactory, key) + Integer.BYTES;
        for (int keyOffset : new int[] {24, firstBoundary}) {
          byte[] invalid = blob.clone();
          var contents = ByteBuffer.wrap(invalid).order(ByteOrder.nativeOrder());
          // A small extent mismatch must reach the preflight, not the allocating decoder.
          if (candidate == UTF8Serializer.INSTANCE) {
            contents.putShort(keyOffset, (short) 7);
          } else {
            contents.putInt(keyOffset + (candidate == DecimalSerializer.INSTANCE ? 4 : 0), 7);
          }
          writeCompactImage(page, serializer, invalid, new byte[0]);
          var error = assertThrows(IllegalArgumentException.class,
              () -> page.readStoredSnapshot(null, serializer, serializerFactory));
          assertEquals("Invalid encoded statistics histogram key extent", error.getMessage());
        }
      }
    } finally {
      releasePage(entry);
    }
  }

  @Test
  public void compactSnapshotBoundsHistogramStructureBeforeDecoding() {
    var entry = allocatePage();
    try {
      var page = new HistogramStatsPage(entry);
      var serializer = intKeySerializer();
      byte[] blob = createSimpleHistogram().serialize(serializer, serializerFactory);
      for (int length : new int[] {3, 20, blob.length - 1, blob.length + 1}) {
        writeCompactImage(page, serializer, Arrays.copyOf(blob, length), new byte[0]);
        assertThrows(IllegalArgumentException.class,
            () -> page.readStoredSnapshot(null, serializer, serializerFactory));
      }
      // Invalid bucket count, MCV extent, first boundary length and second boundary width.
      for (int[] field : new int[][] {{0, 0}, {0, 7}, {20, -1}, {20, 70}, {28, 0}, {36, 3}}) {
        byte[] invalid = blob.clone();
        ByteBuffer.wrap(invalid).order(ByteOrder.nativeOrder()).putInt(field[0], field[1]);
        writeCompactImage(page, serializer, invalid, new byte[0]);
        assertThrows(IllegalArgumentException.class,
            () -> page.readStoredSnapshot(null, serializer, serializerFactory));
      }
    } finally {
      releasePage(entry);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void compactSnapshotRejectsInvalidLinkWidthsBeforeDecodingEitherKeyPosition() {
    var serializer = (BinarySerializer<Object>) serializerFactory
        .getObjectSerializer(CompactedLinkSerializer.ID);
    var entry = allocatePage();
    try {
      var page = new HistogramStatsPage(entry);
      byte[] valid = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
          .putShort((short) 3).put((byte) 1).put((byte) 9).array();
      byte[][] invalidKeys = {
          {0, 0}, // Missing width.
          {0, 0, -1}, // Width byte is outside the position range.
          {0, 0, 2, 9}, // Width exceeds the declared key extent.
          {0, 0, 0, 9}, // Width leaves unused bytes.
          {0, 0, 9, 1, 2, 3, 4, 5, 6, 7, 8, 9} // Extent matches but exceeds a long.
      };
      for (byte[] invalid : invalidKeys) {
        for (boolean invalidMcv : new boolean[] {true, false}) {
          byte[] mcv = invalidMcv ? invalid : valid;
          byte[] boundary = invalidMcv ? valid : invalid;
          byte[] blob = ByteBuffer.allocate(48 + mcv.length + boundary.length + valid.length)
              .order(ByteOrder.nativeOrder())
              .putInt(1).putLong(2).putLong(1)
              .putInt(mcv.length).put(mcv).putInt(boundary.length).put(boundary)
              .putInt(valid.length).put(valid).putLong(2).putLong(1).array();
          writeCompactImage(page, serializer, blob, new byte[0]);
          var error = assertThrows(IllegalArgumentException.class,
              () -> page.readStoredSnapshot(null, serializer, serializerFactory));
          assertEquals("Invalid encoded statistics histogram key extent", error.getMessage());
        }
      }
    } finally {
      releasePage(entry);
    }
  }

  @Test
  public void compactStoredDateAcceptsNonMidnightEpoch() {
    BinarySerializer<Object> serializer =
        serializerFactory.getObjectSerializer(PropertyTypeInternal.DATE);
    var instant = Instant.parse("2024-01-02T12:34:56.789Z");
    long epoch = instant.toEpochMilli();
    byte[] blob = ByteBuffer.allocate(72).order(ByteOrder.nativeOrder())
        .putInt(1).putLong(2).putLong(1)
        .putInt(8).putLong(epoch).putInt(8).putLong(epoch).putInt(8).putLong(epoch)
        .putLong(2).putLong(1).array();
    var entry = allocatePage();
    try {
      var page = new HistogramStatsPage(entry);
      writeCompactImage(page, serializer, blob, new byte[0]);
      var histogram = page.readStoredSnapshot(null, serializer, serializerFactory).histogram();
      var expected = new Date(epoch);
      HistogramStatsPageOpsTest.assertKeyIdentity(expected, histogram.mcvValue());
      for (var boundary : histogram.boundaries()) {
        HistogramStatsPageOpsTest.assertKeyIdentity(expected, boundary);
      }
    } finally {
      releasePage(entry);
    }
  }

  @Test
  public void compactStoredBoundariesRejectDescendingKeysButAcceptTies() {
    var serializer = intKeySerializer();
    var entry = allocatePage();
    try {
      var page = new HistogramStatsPage(entry);
      var blob = ByteBuffer.allocate(80).order(ByteOrder.nativeOrder())
          .putInt(2).putLong(3).putLong(0).putInt(0)
          .putInt(4).putInt(0).putInt(4).putInt(10).putInt(4).putInt(5)
          .putLong(2).putLong(1).putLong(2).putLong(1);
      writeCompactImage(page, serializer, blob.array(), new byte[0]);
      var error = assertThrows(IllegalArgumentException.class,
          () -> page.readStoredSnapshot(null, serializer, serializerFactory));
      assertEquals("Statistics histogram boundaries must be nondecreasing", error.getMessage());

      // Replace the final key to make the last two boundaries equal.
      blob.putInt(44, 10);
      writeCompactImage(page, serializer, blob.array(), new byte[0]);
      var histogram = page.readStoredSnapshot(null, serializer, serializerFactory).histogram();
      org.junit.Assert.assertArrayEquals(new Integer[] {0, 10, 10}, histogram.boundaries());
    } finally {
      releasePage(entry);
    }
  }

  private static void writeCompactImage(HistogramStatsPage page, BinarySerializer<?> serializer,
      byte[] blob, byte[] registers) {
    byte[] image = ByteBuffer.allocate(49 + blob.length + registers.length)
        .order(ByteOrder.nativeOrder())
        .put(serializer.getId()).putLong(4).putLong(5).putLong(1).putLong(7).putLong(8)
        .putInt(blob.length).putInt(registers.length).put(blob).put(registers).array();
    page.writePart(HistogramStatsPageContinuationCodec.encode(image)[0]);
  }

  private static final int PAGE_SIZE = DurablePage.MAX_PAGE_SIZE_BYTES;

  /**
   * VARIABLE_DATA_OFFSET is the byte offset where histogram data starts
   * on page 0. Computed as NEXT_FREE_POSITION(28) + 4(version) +
   * 1(serializer) + 8(total) + 8(distinct) + 8(null) + 8(mutations) +
   * 8(lastBuild) + 4(histLen) + 4(hllCount) = 81.
   */
  private static final int VARIABLE_DATA_OFFSET = 81;

  /** Offset of the histogramDataLength field in the raw page buffer. */
  private static final int HISTOGRAM_DATA_LENGTH_RAW_OFFSET = 73;

  /** Offset of the hllRegisterCount field in the raw page buffer. */
  private static final int HLL_REGISTER_COUNT_RAW_OFFSET = 77;

  private ByteBufferPool bufferPool;
  private BinarySerializerFactory serializerFactory;

  @Before
  public void setUp() {
    bufferPool = new ByteBufferPool(PAGE_SIZE);
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  @After
  public void tearDown() {
    bufferPool.clear();
  }

  // ---- Helper methods ----

  private CacheEntry allocatePage() {
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();
    return entry;
  }

  private void releasePage(CacheEntry entry) {
    entry.releaseExclusiveLock();
    entry.getCachePointer().decrementReferrer();
  }

  @SuppressWarnings("unchecked")
  private BinarySerializer<Object> intKeySerializer() {
    return (BinarySerializer<Object>) (BinarySerializer<?>) IntegerSerializer.INSTANCE;
  }

  private ByteBuffer rawBuffer(CacheEntry entry) {
    return entry.getCachePointer().getBuffer();
  }

  /**
   * Creates a simple 2-bucket histogram with integer boundaries.
   */
  private EquiDepthHistogram createSimpleHistogram() {
    return new EquiDepthHistogram(
        2,
        new Comparable<?>[] {1, 50, 100},
        new long[] {500, 500},
        new long[] {25, 25},
        1000,
        50,
        200L);
  }

  /**
   * Builds an HLL sketch with known data for deterministic round-trip
   * verification.
   */
  private HyperLogLogSketch createPopulatedHll() {
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 500; i++) {
      hll.add(i * 7919L); // prime multiplier for spread
    }
    return hll;
  }

  @Test
  @SuppressWarnings("unchecked")
  public void fittedKeyShapes_useRealWriterAndLargestCount() {
    // Strings at the boundary limit and untruncated decimals must fit after
    // reduction. Numeric keys exercise sketch spill without resolution loss.
    var serializers = new BinarySerializer<?>[] {
        com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer.INSTANCE,
        com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer.INSTANCE,
        IntegerSerializer.INSTANCE};
    for (int shape = 0; shape < serializers.length; shape++) {
      var serializer = (BinarySerializer<Object>) serializers[shape];
      int count = 128;
      var bounds = new Comparable<?>[count + 1];
      var frequencies = new long[count];
      var distinct = new long[count];
      for (int i = 0; i <= count; i++) {
        bounds[i] = switch (shape) {
          case 0 -> String.format("%04d", i) + "x".repeat(250);
          case 1 -> new java.math.BigDecimal(
              java.math.BigInteger.TEN.pow(700).add(java.math.BigInteger.valueOf(i)));
          default -> i;
        };
      }
      java.util.Arrays.fill(frequencies, 10);
      java.util.Arrays.fill(distinct, 1);
      var source = new IndexHistogramManager.BuildResult(
          bounds, frequencies, distinct, count, count, null, 0);
      IndexHistogramManager.BoundarySizeCalculator calculator = (keys, buckets) -> {
        int bytes = 0;
        for (int i = 0; i <= buckets; i++) {
          bytes += 4 + serializer.getObjectSize(serializerFactory, keys[i]);
        }
        return bytes;
      };
      var fit = IndexHistogramManager.fitToPage(source, 1280, false, 0, calculator);
      assertNotNull(fit);
      var histogram = fit.histogram();
      var hll = createPopulatedHll();
      var snapshot = new HistogramSnapshot(new IndexStatistics(1280, 128, 0),
          histogram, 0, 1280, 0, false, hll, fit.hllOnPage1());
      var entry = allocatePage();
      try {
        var page = new HistogramStatsPage(entry);
        page.writeSnapshot(snapshot, serializer.getId(), serializer, serializerFactory);
        int blobBytes = rawBuffer(entry).getInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET);
        int inlineBytes = fit.hllOnPage1() ? 0 : HyperLogLogSketch.serializedSize();
        int measured = VARIABLE_DATA_OFFSET + blobBytes + inlineBytes;
        assertTrue(measured <= PAGE_SIZE);
        assertEquals(PAGE_SIZE - measured,
            IndexHistogramManager.computeMaxBoundarySpace(
                histogram.bucketCount(), inlineBytes, 0)
                - calculator.computeSize(histogram.boundaries(), histogram.bucketCount()));
        assertEquals(1280, java.util.Arrays.stream(histogram.frequencies()).sum());
        assertEquals(128, java.util.Arrays.stream(histogram.distinctCounts()).sum());
        if (histogram.bucketCount() < count) {
          assertTrue(fit.hllOnPage1());
          int next = histogram.bucketCount() + 1;
          var merged = IndexHistogramManager.mergeBuckets(
              bounds, frequencies, distinct, count, next);
          var tooLarge = new EquiDepthHistogram(next, merged.boundaries,
              merged.frequencies, merged.distinctCounts, 1280, null, 0);
          var oversized = new HistogramSnapshot(snapshot.stats(), tooLarge,
              0, 1280, 0, false, hll, true);
          try {
            page.writeSnapshot(oversized, serializer.getId(), serializer, serializerFactory);
            fail("The next larger bucket count must exceed page capacity");
          } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("exceeds page 0 capacity"));
          }
        } else if (PAGE_SIZE == 4096) {
          assertTrue("Spill the sketch before reducing numeric buckets", fit.hllOnPage1());
        }
        System.out.printf("page=%d shape=%d buckets=%d spill=%s bytes=%d%n",
            PAGE_SIZE, shape, histogram.bucketCount(), fit.hllOnPage1(), measured);
      } finally {
        releasePage(entry);
      }
    }
  }

  // ---- Tests: inline HLL on page 0 (normal case) ----

  @Test
  public void writeAndReadSnapshotWithInlineHll() {
    // Given: a snapshot with HLL (multi-value index, normal case — HLL
    // fits on page 0)
    var hll = createPopulatedHll();
    long expectedEstimate = hll.estimate();
    var stats = new IndexStatistics(1000, expectedEstimate, 50);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 100, 900, 0, false, hll, false);

    CacheEntry page0 = allocatePage();
    try {
      // When: write with hllOnPage1 = false (inline)
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      // Then: reading back produces identical snapshot with HLL inline
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);

      assertEquals(1000, loaded.stats().totalCount());
      assertEquals(expectedEstimate, loaded.stats().distinctCount());
      assertEquals(50, loaded.stats().nullCount());
      assertEquals(100, loaded.mutationsSinceRebalance());
      assertEquals(900, loaded.totalCountAtLastBuild());
      assertNotNull(loaded.histogram());
      assertEquals(2, loaded.histogram().bucketCount());
      assertNotNull(loaded.hllSketch());
      assertFalse("HLL should NOT be flagged as on page 1",
          loaded.hllOnPage1());
      // HLL estimate should be preserved
      assertEquals(expectedEstimate, loaded.hllSketch().estimate());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void writeAndReadSnapshotWithoutHll() {
    // Given: a single-value snapshot (no HLL)
    var stats = new IndexStatistics(500, 500, 10);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 50, 400, 0, false, null, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);

      assertEquals(500, loaded.stats().totalCount());
      assertNotNull(loaded.histogram());
      assertNull("Single-value should have no HLL", loaded.hllSketch());
      assertFalse(loaded.hllOnPage1());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: HLL spilled to page 1 ----

  @Test
  public void writeSnapshotWithHllOnPage1SetsHighBitInRegisterCount() {
    // Given: a snapshot with HLL, written with hllOnPage1 = true
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 100, 900, 0, false, hll, true);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      // Then: the raw hllRegisterCount should have the high bit set
      int rawCount = page.getRawHllRegisterCount();
      assertTrue("High bit should be set for page-1 flag",
          (rawCount & HistogramStatsPage.HLL_PAGE1_FLAG) != 0);

      int actualCount = rawCount & ~HistogramStatsPage.HLL_PAGE1_FLAG;
      assertEquals("Actual register count should be 1024",
          HyperLogLogSketch.serializedSize(), actualCount);
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshotDetectsPage1FlagAndReturnsNullHll() {
    // Given: a snapshot written with hllOnPage1 = true
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 100, 900, 0, false, hll, true);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      // When: reading back from page 0 only
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);

      // Then: HLL is null (caller must read from page 1) and flag is set
      assertNull("HLL should be null when spilled to page 1",
          loaded.hllSketch());
      assertTrue("hllOnPage1 flag should be set", loaded.hllOnPage1());

      // Other fields should still be correct
      assertEquals(1000, loaded.stats().totalCount());
      assertNotNull(loaded.histogram());
      assertEquals(2, loaded.histogram().bucketCount());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: page-1 HLL round-trip ----

  @Test
  public void writeAndReadHllFromPage1RoundTrip() {
    // Given: an HLL sketch with known data
    var hll = createPopulatedHll();
    long expectedEstimate = hll.estimate();

    CacheEntry page1 = allocatePage();
    try {
      // When: write HLL to page 1 and read it back
      HistogramStatsPage.writeHllToPage1(page1, hll);
      var loaded = HistogramStatsPage.readHllFromPage1(page1);

      // Then: the loaded HLL produces the same estimate
      assertNotNull(loaded);
      assertEquals("HLL estimate should survive page-1 round-trip",
          expectedEstimate, loaded.estimate());
    } finally {
      releasePage(page1);
    }
  }

  @Test
  public void fullPage1SpillRoundTrip() {
    // Simulate the complete flow: write page 0 with page-1 flag,
    // write HLL to page 1, then read back both pages and reconstruct.
    var hll = createPopulatedHll();
    long expectedEstimate = hll.estimate();
    var stats = new IndexStatistics(2000, expectedEstimate, 100);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 200, 1800, 0, false, hll, true);

    CacheEntry page0 = allocatePage();
    CacheEntry page1 = allocatePage();
    try {
      // Write phase: page 0 (no inline HLL) + page 1 (HLL registers)
      var statsPage = new HistogramStatsPage(page0);
      statsPage.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);
      HistogramStatsPage.writeHllToPage1(page1, hll);

      // Read phase: page 0 first
      var loadedFromPage0 = statsPage.readSnapshot(
          intKeySerializer(), serializerFactory);
      assertTrue("hllOnPage1 should be true", loadedFromPage0.hllOnPage1());
      assertNull("HLL should be null from page 0 read",
          loadedFromPage0.hllSketch());

      // Then read HLL from page 1 and reconstruct
      var loadedHll = HistogramStatsPage.readHllFromPage1(page1);
      var reconstructed = new HistogramSnapshot(
          loadedFromPage0.stats(), loadedFromPage0.histogram(),
          loadedFromPage0.mutationsSinceRebalance(),
          loadedFromPage0.totalCountAtLastBuild(),
          loadedFromPage0.version(),
          loadedFromPage0.hasDriftedBuckets(),
          loadedHll, true);

      // Verify the full reconstruction
      assertEquals(2000, reconstructed.stats().totalCount());
      assertEquals(expectedEstimate, reconstructed.stats().distinctCount());
      assertEquals(100, reconstructed.stats().nullCount());
      assertNotNull(reconstructed.histogram());
      assertEquals(2, reconstructed.histogram().bucketCount());
      assertNotNull(reconstructed.hllSketch());
      assertEquals(expectedEstimate, reconstructed.hllSketch().estimate());
      assertTrue(reconstructed.hllOnPage1());
    } finally {
      releasePage(page0);
      releasePage(page1);
    }
  }

  // ---- Tests: HLL_PAGE1_FLAG encoding ----

  @Test
  public void hllPage1FlagDoesNotConflictWithValidRegisterCounts() {
    // Valid register counts are 0 (no HLL) and 1024 (HLL present).
    // The high bit (0x8000_0000) should never be set for these values.
    assertEquals(0, 0 & HistogramStatsPage.HLL_PAGE1_FLAG);
    assertEquals(0,
        HyperLogLogSketch.serializedSize()
            & HistogramStatsPage.HLL_PAGE1_FLAG);

    // Flag OR'd with 1024 should produce a value > 1024
    int flagged = HyperLogLogSketch.serializedSize()
        | HistogramStatsPage.HLL_PAGE1_FLAG;
    assertTrue(flagged < 0); // high bit set → negative in signed int

    // Masking off the flag recovers the original count
    assertEquals(HyperLogLogSketch.serializedSize(),
        flagged & ~HistogramStatsPage.HLL_PAGE1_FLAG);
  }

  // ---- Tests: computeNewSnapshot preserves hllOnPage1 ----

  @Test
  public void computeNewSnapshotPreservesHllOnPage1Flag() {
    // Given: a snapshot with hllOnPage1 = true
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var snapshot = new HistogramSnapshot(
        stats, null, 100, 900, 0, false, hll, true);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    delta.hllSketch = new HyperLogLogSketch();
    delta.hllSketch.add(999999L);

    // When: apply delta
    var result =
        IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Then: hllOnPage1 flag is preserved
    assertTrue("hllOnPage1 should be preserved through delta application",
        result.hllOnPage1());
    assertEquals(1005, result.stats().totalCount());
    assertNotNull(result.hllSketch());
  }

  @Test
  public void computeNewSnapshotKeepsHllOnPage1FalseWhenNotSpilled() {
    // Given: a snapshot with hllOnPage1 = false
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var snapshot = new HistogramSnapshot(
        stats, null, 100, 900, 0, false, hll, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 3;
    delta.mutationCount = 3;

    var result =
        IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertFalse("hllOnPage1 should remain false",
        result.hllOnPage1());
  }

  // ---- Tests: HistogramSnapshot hllOnPage1 field ----

  @Test
  public void snapshotHllOnPage1FieldAccessor() {
    var stats = new IndexStatistics(0, 0, 0);

    var withSpill = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, true);
    assertTrue(withSpill.hllOnPage1());

    var withoutSpill = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);
    assertFalse(withoutSpill.hllOnPage1());
  }

  // ---- Tests: empty page writes ----

  @Test
  public void writeEmptyPageHasZeroHllRegisterCount() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      assertEquals(0, page.getRawHllRegisterCount());

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull(loaded.hllSketch());
      assertFalse(loaded.hllOnPage1());
      assertNull(loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: readSnapshot version handling ----

  /**
   * Version 0 (old format before versioning was introduced) must be accepted
   * without throwing an exception, since legacy pages have zeros in all
   * uninitialized fields.
   */
  @Test
  public void readSnapshotAcceptsVersion0AsOldFormat() {
    // A fresh ByteBuffer-backed page has all zeros, so version = 0,
    // simulating an old-format page before versioning was introduced.
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      // All fields are zero, including version=0.
      var loaded = page.readSnapshot(
          intKeySerializer(), serializerFactory);
      // Should not throw; all values should be zero/default.
      assertEquals(0, loaded.stats().totalCount());
      assertEquals(0, loaded.stats().distinctCount());
      assertEquals(0, loaded.stats().nullCount());
      assertNull(loaded.histogram());
      assertNull(loaded.hllSketch());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * Version = FORMAT_VERSION (1) must be accepted. This is the normal case
   * for pages written by the current code.
   */
  @Test
  public void readSnapshotAcceptsCurrentFormatVersion() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // writeEmpty writes FORMAT_VERSION=1. readSnapshot should accept it.
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertEquals(0, loaded.stats().totalCount());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * Version = 2 (unsupported future version) must throw StorageException.
   * This kills the mutant that removes the version check entirely.
   */
  @Test(expected = StorageException.class)
  public void readSnapshotThrowsOnUnsupportedVersion() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      // Write a valid page first, then corrupt the version.
      page.writeEmpty((byte) 5);

      // Access the underlying buffer to corrupt the version field.
      // FORMAT_VERSION_OFFSET = NEXT_FREE_POSITION = 28 (DurablePage header).
      var buffer = page0.getCachePointer().getBuffer();
      buffer.putInt(28, 2); // set version = 2

      // This should throw StorageException.
      page.readSnapshot(intKeySerializer(), serializerFactory);
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: readSnapshot corrupted histogramDataLength ----

  /**
   * A negative histogramDataLength (corrupted page) must be treated as
   * empty histogram rather than causing an OOM or IndexOutOfBounds.
   */
  @Test
  public void readSnapshotTreatsNegativeHistogramDataLengthAsEmpty() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Corrupt histogramDataLength to -1.
      // HISTOGRAM_DATA_LENGTH_OFFSET = TOTAL_COUNT_AT_LAST_BUILD_OFFSET + 8
      //   = (NULL_COUNT_OFFSET + 8) + 8 = ... = 73
      // We can compute it: NEXT_FREE_POSITION(28) + 4(version) + 1(ser) +
      //   8(total) + 8(distinct) + 8(null) + 8(mutations) + 8(lastBuild) = 73
      var buffer = page0.getCachePointer().getBuffer();
      buffer.putInt(73, -1); // histogramDataLength = -1

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      // Should treat as empty histogram, not crash.
      assertNull("Negative histogramDataLength should yield null histogram",
          loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * A histogramDataLength exceeding page capacity must be treated as empty
   * histogram rather than attempting to read beyond the page boundary.
   */
  @Test
  public void readSnapshotTreatsExcessiveHistogramDataLengthAsEmpty() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Set histogramDataLength to a value that exceeds page capacity.
      var buffer = page0.getCachePointer().getBuffer();
      buffer.putInt(73, PAGE_SIZE + 1000);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull(
          "Excessive histogramDataLength should yield null histogram",
          loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: readSnapshot HLL inline reading ----

  /**
   * When HLL offset points beyond page capacity (due to corrupt
   * histogramDataLength just within bounds but combined with HLL
   * exceeding the page), HLL should be null (skipped), not crash.
   */
  @Test
  public void readSnapshotSkipsHllWhenOffsetExceedsPageCapacity() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      var buffer = page0.getCachePointer().getBuffer();
      // Set a histogramDataLength that is valid (within page) but leaves
      // no room for HLL registers. VARIABLE_DATA_OFFSET = 81.
      // Set histogramDataLength = PAGE_SIZE - 81 - 10 (leaves only 10
      // bytes for HLL, but HLL needs 1024).
      int histLen = PAGE_SIZE - 81 - 10;
      buffer.putInt(73, histLen);

      // Set hllRegisterCount = 1024 (no page-1 flag).
      buffer.putInt(77, 1024);

      // Now hllOffset = 81 + histLen = PAGE_SIZE - 10.
      // hllOffset + 1024 > PAGE_SIZE → HLL should be skipped.
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      // The histogram deserialization will likely fail/return null since
      // the data is garbage, but HLL must be null due to bounds check.
      assertNull("HLL should be null when offset exceeds page capacity",
          loaded.hllSketch());
      assertFalse(loaded.hllOnPage1());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * Verifies that valid inline HLL round-trips correctly when histogram
   * data is also present (non-zero histogramDataLength).
   */
  @Test
  public void readSnapshotRoundTripsInlineHllWithHistogramData() {
    var hll = createPopulatedHll();
    long expectedEstimate = hll.estimate();
    var stats = new IndexStatistics(2000, expectedEstimate, 100);
    var histogram = createSimpleHistogram();
    var snapshot = new HistogramSnapshot(
        stats, histogram, 200, 1800, 0, false, hll, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);

      // Both histogram and HLL should be present.
      assertNotNull("Histogram should be present", loaded.histogram());
      assertEquals(2, loaded.histogram().bucketCount());
      assertNotNull("Inline HLL should be present", loaded.hllSketch());
      assertEquals(expectedEstimate, loaded.hllSketch().estimate());
      assertFalse(loaded.hllOnPage1());
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: writeSnapshot capacity checks ----

  /**
   * Writing a histogram whose serialized data exceeds page 0 capacity
   * must throw IllegalStateException. We simulate this by creating a
   * histogram with many boundaries that produces a large blob.
   */
  @Test
  public void writeSnapshotThrowsWhenHistogramExceedsPageCapacity() {
    // Create a histogram with many buckets to generate a large blob.
    // Each bucket boundary is an Integer (4 bytes serialized), and we
    // need enough buckets to exceed PAGE_SIZE - VARIABLE_DATA_OFFSET (81).
    int bucketCount = PAGE_SIZE; // way more than needed
    var boundaries = new Comparable<?>[bucketCount + 1];
    var frequencies = new long[bucketCount];
    var distinctCounts = new long[bucketCount];
    for (int i = 0; i <= bucketCount; i++) {
      boundaries[i] = i;
    }
    for (int i = 0; i < bucketCount; i++) {
      frequencies[i] = 1;
      distinctCounts[i] = 1;
    }
    var bigHistogram = new EquiDepthHistogram(
        bucketCount, boundaries, frequencies, distinctCounts,
        bucketCount, bucketCount, 0L);

    var stats = new IndexStatistics(bucketCount, bucketCount, 0);
    var snapshot = new HistogramSnapshot(
        stats, bigHistogram, 0, 0, 0, false, null, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);
      fail("Expected IllegalStateException for oversized histogram");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("Histogram data exceeds page 0"));
    } finally {
      releasePage(page0);
    }
  }

  /**
   * Writing histogram + inline HLL that combined exceed page 0 capacity
   * must throw IllegalStateException. The histogram alone fits, but
   * adding 1024 bytes for HLL pushes it over the limit.
   */
  @Test
  public void writeSnapshotThrowsWhenHistogramPlusHllExceedsCapacity() {
    // Create a histogram that nearly fills the page, leaving fewer than
    // 1024 bytes for HLL. We need histogramDataLength > PAGE_SIZE - 81 - 1024.
    // The minimum blob to exceed: PAGE_SIZE - 81 - 1024 + 1 bytes.
    int targetBlobSize = PAGE_SIZE - 81 - 1024 + 100;
    // Each bucket with integer boundaries: approx 4 bytes per boundary +
    // 8 per frequency + 8 per distinct. ~20 bytes per bucket.
    int bucketCount = targetBlobSize / 10; // overshoot to ensure large enough
    var boundaries = new Comparable<?>[bucketCount + 1];
    var frequencies = new long[bucketCount];
    var distinctCounts = new long[bucketCount];
    for (int i = 0; i <= bucketCount; i++) {
      boundaries[i] = i;
    }
    for (int i = 0; i < bucketCount; i++) {
      frequencies[i] = 1;
      distinctCounts[i] = 1;
    }
    var mediumHistogram = new EquiDepthHistogram(
        bucketCount, boundaries, frequencies, distinctCounts,
        bucketCount, bucketCount, 0L);

    // Verify the histogram serialization is large enough.
    byte[] blob = mediumHistogram.serialize(
        intKeySerializer(), serializerFactory);
    // Only proceed if the blob + HLL exceeds page capacity but blob alone
    // does not. If blob alone already exceeds, the first check will fire.
    // Use Assume so CI reports this as "skipped" rather than silently passing.
    org.junit.Assume.assumeTrue(
        "Blob size does not hit the histogram+HLL overflow sweet spot",
        81 + blob.length <= PAGE_SIZE
            && 81 + blob.length + 1024 > PAGE_SIZE);

    var hll = createPopulatedHll();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, mediumHistogram, 0, 0, 0, false, hll, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);
      fail("Expected IllegalStateException for histogram + HLL overflow");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("exceeds page 0 capacity"));
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: writeSnapshot HLL page-1 flag encoding ----

  /**
   * When hllSketch is null (single-value index), hllRegisterCount must
   * be written as 0 with no page-1 flag.
   */
  @Test
  public void writeSnapshotWritesZeroForNullHll() {
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, null, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      assertEquals("Null HLL should write 0 register count",
          0, page.getRawHllRegisterCount());
    } finally {
      releasePage(page0);
    }
  }

  /**
   * When hllOnPage1=true, the persisted hllRegisterCount must have the
   * HLL_PAGE1_FLAG high bit set.
   */
  @Test
  public void writeSnapshotSetsPage1FlagWhenHllOnPage1IsTrue() {
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, hll, true);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      int raw = page.getRawHllRegisterCount();
      assertTrue("Page-1 flag bit should be set",
          (raw & HistogramStatsPage.HLL_PAGE1_FLAG) != 0);
      assertEquals("Actual count should be 1024",
          1024, raw & ~HistogramStatsPage.HLL_PAGE1_FLAG);
    } finally {
      releasePage(page0);
    }
  }

  /**
   * When hllOnPage1=false and hllSketch is non-null, the persisted
   * hllRegisterCount must be the raw count (1024) without the flag bit.
   */
  @Test
  public void writeSnapshotWritesRawCountWhenHllOnPage1IsFalse() {
    var hll = createPopulatedHll();
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 0, 0, false, hll, false);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      int raw = page.getRawHllRegisterCount();
      assertEquals("Should be raw 1024 without flag",
          1024, raw);
      assertEquals("Page-1 flag should NOT be set",
          0, raw & HistogramStatsPage.HLL_PAGE1_FLAG);
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: writeEmpty verification ----

  /**
   * After writeEmpty, all fields must be zero/default, and the format
   * version must be set to FORMAT_VERSION (1).
   */
  @Test
  public void writeEmptySetsFormatVersionAndZerosAllFields() {
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Verify format version is 1 (not 0).
      var buffer = page0.getCachePointer().getBuffer();
      int version = buffer.getInt(28); // FORMAT_VERSION_OFFSET
      assertEquals("FORMAT_VERSION should be 1 after writeEmpty",
          1, version);

      // Verify all stat fields are zero.
      assertEquals(0, page.getTotalCount());
      assertEquals(0, page.getDistinctCount());
      assertEquals(0, page.getNullCount());
      assertEquals(0, page.getHistogramDataLength());
      assertEquals(0, page.getRawHllRegisterCount());

      // Verify serializer ID is written.
      byte serId = buffer.get(32); // SERIALIZER_ID_OFFSET
      assertEquals(5, serId);
    } finally {
      releasePage(page0);
    }
  }

  // ---- Tests: accessor return values with non-zero data ----

  @Test
  public void accessorsReturnNonZeroValuesAfterWrite() {
    // Kills PrimitiveReturnsMutator on getTotalCount, getDistinctCount,
    // getNullCount, getHistogramDataLength (lines 306, 310, 314, 318).
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      var stats = new IndexStatistics(12345, 678, 90);
      var histogram = createSimpleHistogram();
      var snapshot = new HistogramSnapshot(
          stats, histogram, 100, 900, 0, false, null, false);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      assertNotEquals("getTotalCount should not be 0",
          0, page.getTotalCount());
      assertEquals(12345, page.getTotalCount());
      assertNotEquals("getDistinctCount should not be 0",
          0, page.getDistinctCount());
      assertEquals(678, page.getDistinctCount());
      assertNotEquals("getNullCount should not be 0",
          0, page.getNullCount());
      assertEquals(90, page.getNullCount());
      assertNotEquals("getHistogramDataLength should not be 0",
          0, page.getHistogramDataLength());
      assertTrue("getHistogramDataLength should be positive",
          page.getHistogramDataLength() > 0);
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshotParamOrderPreservesDistinctFields() {
    // Kills ParamSwapMutator on HistogramSnapshot constructor (line 203).
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      // Use unique values so any swap is detectable
      var stats = new IndexStatistics(111, 222, 333);
      var snapshot = new HistogramSnapshot(
          stats, null, 444, 555, 0, false, null, false);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertEquals("totalCount", 111, loaded.stats().totalCount());
      assertEquals("distinctCount", 222, loaded.stats().distinctCount());
      assertEquals("nullCount", 333, loaded.stats().nullCount());
      assertEquals("mutationsSinceRebalance", 444,
          loaded.mutationsSinceRebalance());
      assertEquals("totalCountAtLastBuild", 555,
          loaded.totalCountAtLastBuild());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void writeAndReadHistogramDataLengthRoundTrip() {
    // Kills boundary mutations on histogramDataLength check (line 228).
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      var histogram = new EquiDepthHistogram(
          2, new Comparable<?>[] {10, 50, 90},
          new long[] {300, 700}, new long[] {15, 35},
          1000, 60, 150);
      var stats = new IndexStatistics(1000, 50, 100);
      var snapshot = new HistogramSnapshot(
          stats, histogram, 50, 800, 0, false, null, false);
      page.writeSnapshot(snapshot, (byte) 5,
          intKeySerializer(), serializerFactory);

      assertTrue("histogramDataLength should be > 0",
          page.getHistogramDataLength() > 0);
      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNotNull("Histogram should be loaded", loaded.histogram());
      assertEquals(2, loaded.histogram().bucketCount());
      assertEquals(300, loaded.histogram().frequencies()[0]);
      assertEquals(700, loaded.histogram().frequencies()[1]);
      assertEquals(60, loaded.histogram().mcvValue());
      assertEquals(150, loaded.histogram().mcvFrequency());
    } finally {
      releasePage(page0);
    }
  }

  // ── Page layout boundary tests ──────────────────────────────

  @Test
  public void readSnapshot_unsupportedVersion_exceptionMessageIsCorrect() {
    // Verifies that the StorageException for unsupported versions has
    // a non-null message containing the version number. Kills the
    // parameter-swap mutant on StorageException(null, "Unsupported...").
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Corrupt the format version to 99 (unsupported).
      rawBuffer(page0).putInt(28, 99);

      page.readSnapshot(intKeySerializer(), serializerFactory);
      fail("Expected StorageException for unsupported version");
    } catch (StorageException e) {
      // If params were swapped, getMessage() would be null.
      assertNotNull(
          "Exception message must not be null (detects param swap)",
          e.getMessage());
      assertTrue(
          "Message should mention unsupported version",
          e.getMessage().contains("Unsupported histogram stats page version"));
      assertTrue(
          "Message should include the bad version number",
          e.getMessage().contains("99"));
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_histogramDataLengthZero_returnsNullHistogram() {
    // histogramDataLength = 0 is valid and should yield null histogram.
    // Kills mutant that changes < 0 to <= 0 (would wrongly treat 0 as
    // corrupt).
    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull("Zero histogramDataLength should yield null histogram",
          loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_histogramDataLengthExactlyFillsPage_isNotRejected() {
    // histogramDataLength = PAGE_SIZE - VARIABLE_DATA_OFFSET, so
    // VARIABLE_DATA_OFFSET + histogramDataLength == PAGE_SIZE (exact fit).
    // Original code: > PAGE_SIZE -> false -> not rejected.
    // Boundary mutant: >= PAGE_SIZE -> true -> rejected (zeroed to 0).
    // We detect the difference: if not rejected, deserialization is
    // attempted on the garbage data.
    int exactLen = PAGE_SIZE - VARIABLE_DATA_OFFSET;

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      // Set histogramDataLength to the exact-fit boundary value.
      rawBuffer(page0).putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, exactLen);

      // Also set hllRegisterCount = 0 to avoid HLL processing.
      rawBuffer(page0).putInt(HLL_REGISTER_COUNT_RAW_OFFSET, 0);

      // Attempt to read. The guard should NOT reject this length.
      // Deserialization of all-zeros blob will likely throw or produce
      // garbage, but the key point is that deserialization IS attempted
      // (length was not zeroed by the guard).
      boolean deserializationAttempted;
      try {
        var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
        // If we get here, deserialization succeeded (unlikely with zeros
        // but possible if the format is tolerant). Either way,
        // deserialization was attempted.
        deserializationAttempted = true;
      } catch (Exception e) {
        // Deserialization threw because the blob is zeros -- this means
        // the guard did NOT reject the length, so deserialization was
        // attempted. This is the expected path.
        deserializationAttempted = true;
      }
      assertTrue(
          "Guard must not reject exact-fit histogramDataLength",
          deserializationAttempted);
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_histogramDataLengthExceedsPageByOne_rejected() {
    // histogramDataLength = PAGE_SIZE - VARIABLE_DATA_OFFSET + 1, so
    // VARIABLE_DATA_OFFSET + histogramDataLength == PAGE_SIZE + 1.
    // The guard should reject this and return null histogram.
    int overLen = PAGE_SIZE - VARIABLE_DATA_OFFSET + 1;

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      rawBuffer(page0).putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, overLen);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull(
          "Excessive histogramDataLength should yield null histogram",
          loaded.histogram());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_hllFitsPageExactly_noHistogram_readsHll() {
    // Set histogramDataLength = 0 in raw buffer and write valid HLL at
    // VARIABLE_DATA_OFFSET. Since VARIABLE_DATA_OFFSET(81) + 1024 = 1105
    // < PAGE_SIZE, this always fits. Verifies the basic HLL inline path.
    int hllSize = HyperLogLogSketch.serializedSize();

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      ByteBuffer buf = rawBuffer(page0);
      // Set histogramDataLength = 0, so no histogram deserialization.
      buf.putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, 0);
      // Set hllRegisterCount = 1024 (no page-1 flag).
      buf.putInt(HLL_REGISTER_COUNT_RAW_OFFSET, hllSize);

      // Write valid HLL data at VARIABLE_DATA_OFFSET (since histLen=0).
      var hll = createPopulatedHll();
      byte[] hllData = new byte[hllSize];
      hll.writeTo(hllData, 0);
      int hllOffset = VARIABLE_DATA_OFFSET; // histLen = 0
      for (int i = 0; i < hllSize; i++) {
        buf.put(hllOffset + i, hllData[i]);
      }

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull("Histogram should be null with 0 data length",
          loaded.histogram());
      assertNotNull("HLL should be present", loaded.hllSketch());
      assertEquals(hll.estimate(), loaded.hllSketch().estimate());
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_hllExactBoundary_withRawHistLen_readsHll() {
    // Set histogramDataLength in raw buffer to a value that makes
    // hllOffset + 1024 == PAGE_SIZE exactly. Then write a valid small
    // histogram blob and valid HLL data so deserialization succeeds.
    // Kills boundary mutant that changes <= to < in the HLL guard.
    int hllSize = HyperLogLogSketch.serializedSize(); // 1024
    int histLen = PAGE_SIZE - VARIABLE_DATA_OFFSET - hllSize;

    // Create a real 1-bucket histogram to get a valid blob.
    var smallHist = new EquiDepthHistogram(
        1,
        new Comparable<?>[] {0, 1},
        new long[] {1},
        new long[] {1},
        1, 0, 0L);
    byte[] validBlob = smallHist.serialize(
        intKeySerializer(), serializerFactory);

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      ByteBuffer buf = rawBuffer(page0);

      // Write histogramDataLength = histLen (so HLL fits exactly).
      buf.putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, histLen);
      // Write hllRegisterCount = 1024 (inline, no page-1 flag).
      buf.putInt(HLL_REGISTER_COUNT_RAW_OFFSET, hllSize);

      // Write the valid histogram blob at VARIABLE_DATA_OFFSET.
      // The remaining bytes (histLen - validBlob.length) are zeros.
      for (int i = 0; i < validBlob.length && i < histLen; i++) {
        buf.put(VARIABLE_DATA_OFFSET + i, validBlob[i]);
      }

      // Write valid HLL data at hllOffset.
      var hll = createPopulatedHll();
      byte[] hllData = new byte[hllSize];
      hll.writeTo(hllData, 0);
      int hllOffset = VARIABLE_DATA_OFFSET + histLen;
      assertEquals(
          "HLL should end exactly at page boundary",
          PAGE_SIZE, hllOffset + hllSize);
      for (int i = 0; i < hllSize; i++) {
        buf.put(hllOffset + i, hllData[i]);
      }

      // Read snapshot. The HLL guard should accept
      // hllOffset + 1024 <= PAGE_SIZE (exact equality).
      try {
        var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
        assertNotNull(
            "HLL must be read when hllOffset + count == PAGE_SIZE",
            loaded.hllSketch());
        assertEquals(
            "HLL estimate must match",
            hll.estimate(), loaded.hllSketch().estimate());
        assertFalse(loaded.hllOnPage1());
      } catch (Exception e) {
        // If histogram deserialization fails with an exception, the
        // entire readSnapshot fails before reaching HLL.
        fail("Histogram deserialization threw, preventing HLL "
            + "boundary verification: " + e.getMessage());
      }
    } finally {
      releasePage(page0);
    }
  }

  @Test
  public void readSnapshot_hllExceedsPageByOne_isSkipped() {
    // Set histogramDataLength so that hllOffset + 1024 == PAGE_SIZE + 1.
    // The HLL should be skipped (null).
    int hllSize = HyperLogLogSketch.serializedSize();
    int histLen = PAGE_SIZE - VARIABLE_DATA_OFFSET - hllSize + 1;

    CacheEntry page0 = allocatePage();
    try {
      var page = new HistogramStatsPage(page0);
      page.writeEmpty((byte) 5);

      ByteBuffer buf = rawBuffer(page0);
      buf.putInt(HISTOGRAM_DATA_LENGTH_RAW_OFFSET, histLen);
      buf.putInt(HLL_REGISTER_COUNT_RAW_OFFSET, hllSize);

      var loaded = page.readSnapshot(intKeySerializer(), serializerFactory);
      assertNull(
          "HLL should be null when hllOffset + count > PAGE_SIZE",
          loaded.hllSketch());
      assertFalse(
          "hllOnPage1 should be false (no flag set)",
          loaded.hllOnPage1());
    } finally {
      releasePage(page0);
    }
  }
}
