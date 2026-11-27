/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DateSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DateTimeSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.DoubleSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.FloatSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.CompactedLinkSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.stream.StreamSerializerRID;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.MultiValueEntry;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.MultiValueEntrySerializer;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Page accessor for the histogram statistics page (.ixs file, page 0).
 *
 * <p>Version 0/1 layout (offsets absolute from page start):
 * <pre>
 * 28  formatVersion             4 bytes (int)
 * 32  serializerId              1 byte
 * 33  totalCount                8 bytes (long)
 * 41  distinctCount             8 bytes (long)
 * 49  nullCount                 8 bytes (long)
 * 57  mutationsSinceRebalance   8 bytes (long)
 * 65  totalCountAtLastBuild     8 bytes (long)
 * 73  histogramDataLength       4 bytes (int) — 0 if no histogram
 * 77  hllRegisterCount          4 bytes (int) — 0 for single-value;
 *                               high bit (0x8000_0000) set when HLL
 *                               registers are on page 1 (spill)
 * 81  [histogram data blob]     histogramDataLength bytes
 * ... [HLL registers]           hllRegisterCount bytes (only if inline)
 * </pre>
 *
 * <p>The histogram data blob is produced by
 * {@link EquiDepthHistogram#serialize} and includes bucketCount,
 * nonNullCount, mcvFrequency, mcvKeyLength, mcvKey, boundaries,
 * frequencies, and distinctCounts.
 *
 * <p>When histogram boundaries exhaust page 0 for multi-value indexes,
 * the HLL registers spill to page 1 (layout: DurablePage header at
 * offset 0–27, then 1024 HLL register bytes starting at offset 28).
 * The high bit of {@code hllRegisterCount} on page 0 signals this:
 * {@code (hllRegisterCount & 0x8000_0000) != 0} means page-1 spill.
 */
final class HistogramStatsPage extends DurablePage {

  private static final Logger logger =
      LoggerFactory.getLogger(HistogramStatsPage.class);

  /**
   * High bit flag in the persisted hllRegisterCount field indicating
   * that HLL registers are stored on page 1 instead of inline on page 0.
   * Valid register counts (0 or 1024) never set this bit.
   */
  static final int HLL_PAGE1_FLAG = 0x8000_0000;

  private static final int FORMAT_VERSION_OFFSET = NEXT_FREE_POSITION;
  private static final int SERIALIZER_ID_OFFSET =
      FORMAT_VERSION_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int TOTAL_COUNT_OFFSET = SERIALIZER_ID_OFFSET + 1;
  private static final int DISTINCT_COUNT_OFFSET =
      TOTAL_COUNT_OFFSET + LongSerializer.LONG_SIZE;
  private static final int NULL_COUNT_OFFSET =
      DISTINCT_COUNT_OFFSET + LongSerializer.LONG_SIZE;
  private static final int MUTATIONS_SINCE_REBALANCE_OFFSET =
      NULL_COUNT_OFFSET + LongSerializer.LONG_SIZE;
  private static final int TOTAL_COUNT_AT_LAST_BUILD_OFFSET =
      MUTATIONS_SINCE_REBALANCE_OFFSET + LongSerializer.LONG_SIZE;
  private static final int HISTOGRAM_DATA_LENGTH_OFFSET =
      TOTAL_COUNT_AT_LAST_BUILD_OFFSET + LongSerializer.LONG_SIZE;
  private static final int HLL_REGISTER_COUNT_OFFSET =
      HISTOGRAM_DATA_LENGTH_OFFSET + IntegerSerializer.INT_SIZE;
  private static final int VARIABLE_DATA_OFFSET =
      HLL_REGISTER_COUNT_OFFSET + IntegerSerializer.INT_SIZE;

  static final int FIXED_HEADER_SIZE = VARIABLE_DATA_OFFSET - NEXT_FREE_POSITION;

  static final int FORMAT_VERSION = 1;

  HistogramStatsPage(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  // ---- Write methods ----

  void writeEmpty(byte serializerId) {
    setIntValue(FORMAT_VERSION_OFFSET, FORMAT_VERSION);
    setByteValue(SERIALIZER_ID_OFFSET, serializerId);
    setLongValue(TOTAL_COUNT_OFFSET, 0);
    setLongValue(DISTINCT_COUNT_OFFSET, 0);
    setLongValue(NULL_COUNT_OFFSET, 0);
    setLongValue(MUTATIONS_SINCE_REBALANCE_OFFSET, 0);
    setLongValue(TOTAL_COUNT_AT_LAST_BUILD_OFFSET, 0);
    setIntValue(HISTOGRAM_DATA_LENGTH_OFFSET, 0);
    setIntValue(HLL_REGISTER_COUNT_OFFSET, 0);

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new HistogramStatsPageWriteEmptyOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(), serializerId));
    }
  }

  /**
   * Writes the snapshot to page 0.
   *
   * <p>When {@link HistogramSnapshot#hllOnPage1()} is {@code true}, the HLL
   * register count is written with the page-1 flag set and the register
   * bytes are NOT written to page 0 — the caller must write them to page 1
   * via {@link #writeHllToPage1(CacheEntry, HyperLogLogSketch)}.
   *
   * @param snapshot          the snapshot to persist
   * @param serializerId      serializer ID for the key type
   * @param keySerializer     key serializer for histogram boundaries
   * @param serializerFactory factory for key serialization
   */
  void writeSnapshot(HistogramSnapshot snapshot, byte serializerId,
      BinarySerializer<Object> keySerializer,
      BinarySerializerFactory serializerFactory) {
    boolean hllOnPage1 = snapshot.hllOnPage1();
    setIntValue(FORMAT_VERSION_OFFSET, FORMAT_VERSION);
    setByteValue(SERIALIZER_ID_OFFSET, serializerId);
    setLongValue(TOTAL_COUNT_OFFSET, snapshot.stats().totalCount());
    setLongValue(DISTINCT_COUNT_OFFSET, snapshot.stats().distinctCount());
    setLongValue(NULL_COUNT_OFFSET, snapshot.stats().nullCount());
    setLongValue(MUTATIONS_SINCE_REBALANCE_OFFSET,
        snapshot.mutationsSinceRebalance());
    setLongValue(TOTAL_COUNT_AT_LAST_BUILD_OFFSET,
        snapshot.totalCountAtLastBuild());

    int hllRegisterCount = 0;
    if (snapshot.hllSketch() != null) {
      hllRegisterCount = HyperLogLogSketch.serializedSize();
    }
    // Set the page-1 flag in the persisted register count when spilled.
    // The actual register count (1024) is recoverable by masking off the
    // high bit on read.
    int persistedHllCount = hllOnPage1
        ? (hllRegisterCount | HLL_PAGE1_FLAG)
        : hllRegisterCount;
    setIntValue(HLL_REGISTER_COUNT_OFFSET, persistedHllCount);

    // Write histogram data blob
    int histogramDataLength = 0;
    byte[] histData = null;
    if (snapshot.histogram() != null) {
      histData = snapshot.histogram().serialize(
          keySerializer, serializerFactory);
      histogramDataLength = histData.length;
      if (VARIABLE_DATA_OFFSET + histogramDataLength > MAX_PAGE_SIZE_BYTES) {
        throw new IllegalStateException(
            "Histogram data exceeds page 0 capacity: "
                + (VARIABLE_DATA_OFFSET + histogramDataLength)
                + " > " + MAX_PAGE_SIZE_BYTES);
      }
      setIntValue(HISTOGRAM_DATA_LENGTH_OFFSET, histogramDataLength);
      setBinaryValue(VARIABLE_DATA_OFFSET, histData);
    } else {
      setIntValue(HISTOGRAM_DATA_LENGTH_OFFSET, 0);
    }

    // Write HLL registers inline on page 0 only when NOT spilled to page 1
    byte[] inlineHllData = null;
    if (hllRegisterCount > 0 && !hllOnPage1) {
      int hllOffset = VARIABLE_DATA_OFFSET + histogramDataLength;
      if (hllOffset + hllRegisterCount > MAX_PAGE_SIZE_BYTES) {
        throw new IllegalStateException(
            "Histogram + inline HLL exceeds page 0 capacity: "
                + (hllOffset + hllRegisterCount) + " > "
                + MAX_PAGE_SIZE_BYTES);
      }
      inlineHllData = new byte[hllRegisterCount];
      snapshot.hllSketch().writeTo(inlineHllData, 0);
      setBinaryValue(hllOffset, inlineHllData);
    }

    var cacheEntry = getCacheEntry();
    if (cacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new HistogramStatsPageWriteSnapshotOp(
              cacheEntry.getPageIndex(), cacheEntry.getFileId(),
              0, cec.getInitialLSN(),
              serializerId,
              snapshot.stats().totalCount(),
              snapshot.stats().distinctCount(),
              snapshot.stats().nullCount(),
              snapshot.mutationsSinceRebalance(),
              snapshot.totalCountAtLastBuild(),
              persistedHllCount,
              histData != null ? histData : new byte[0],
              inlineHllData != null ? inlineHllData : new byte[0]));
    }
  }

  // ---- Redo helper methods (package-private, used by PageOperation subclasses) ----

  /**
   * Writes pre-serialized snapshot data directly to the page buffer. Used by
   * {@link HistogramStatsPageWriteSnapshotOp#redo} during recovery — avoids needing
   * BinarySerializer/BinarySerializerFactory which are unavailable during WAL replay.
   */
  void writeSnapshotRaw(byte serializerId, long totalCount, long distinctCount,
      long nullCount, long mutationsSinceRebalance, long totalCountAtLastBuild,
      int persistedHllCount, byte[] histData, byte[] inlineHllData) {
    setIntValue(FORMAT_VERSION_OFFSET, FORMAT_VERSION);
    setByteValue(SERIALIZER_ID_OFFSET, serializerId);
    setLongValue(TOTAL_COUNT_OFFSET, totalCount);
    setLongValue(DISTINCT_COUNT_OFFSET, distinctCount);
    setLongValue(NULL_COUNT_OFFSET, nullCount);
    setLongValue(MUTATIONS_SINCE_REBALANCE_OFFSET, mutationsSinceRebalance);
    setLongValue(TOTAL_COUNT_AT_LAST_BUILD_OFFSET, totalCountAtLastBuild);
    setIntValue(HLL_REGISTER_COUNT_OFFSET, persistedHllCount);

    int histogramDataLength = (histData != null) ? histData.length : 0;
    setIntValue(HISTOGRAM_DATA_LENGTH_OFFSET, histogramDataLength);
    if (histogramDataLength > 0) {
      setBinaryValue(VARIABLE_DATA_OFFSET, histData);
    }

    if (inlineHllData != null && inlineHllData.length > 0) {
      int hllOffset = VARIABLE_DATA_OFFSET + histogramDataLength;
      setBinaryValue(hllOffset, inlineHllData);
    }
  }

  /**
   * Writes raw HLL register bytes directly to a page. Used by
   * {@link HistogramStatsPageWriteHllToPage1Op#redo} during recovery.
   */
  static void writeHllRaw(CacheEntry cacheEntry, byte[] hllData) {
    var page = new HistogramStatsPage(cacheEntry);
    page.setBinaryValue(NEXT_FREE_POSITION, hllData);
  }

  // ---- Read methods ----

  /**
   * Reads the snapshot from page 0.
   *
   * <p>If the high bit of {@code hllRegisterCount} is set, the HLL
   * registers are on page 1 and not read here. The returned snapshot
   * will have {@code hllSketch = null} and {@code hllOnPage1 = true},
   * signaling the caller to load the HLL from page 1 via
   * {@link #readHllFromPage1(CacheEntry)}.
   */
  HistogramSnapshot readSnapshot(
      BinarySerializer<Object> keySerializer,
      BinarySerializerFactory serializerFactory) {
    int version = getIntValue(FORMAT_VERSION_OFFSET);
    if (version != 0 && version != FORMAT_VERSION) {
      throw new StorageException(null,
          "Unsupported histogram stats page version: " + version
              + " (expected " + FORMAT_VERSION + ")");
    }
    long totalCount = getLongValue(TOTAL_COUNT_OFFSET);
    long distinctCount = getLongValue(DISTINCT_COUNT_OFFSET);
    long nullCount = getLongValue(NULL_COUNT_OFFSET);
    long mutationsSinceRebalance =
        getLongValue(MUTATIONS_SINCE_REBALANCE_OFFSET);
    long totalCountAtLastBuild =
        getLongValue(TOTAL_COUNT_AT_LAST_BUILD_OFFSET);
    int histogramDataLength = getIntValue(HISTOGRAM_DATA_LENGTH_OFFSET);
    int rawHllRegisterCount = getIntValue(HLL_REGISTER_COUNT_OFFSET);

    // Detect page-1 spill flag in high bit
    boolean hllOnPage1 =
        (rawHllRegisterCount & HLL_PAGE1_FLAG) != 0;
    int hllRegisterCount =
        rawHllRegisterCount & ~HLL_PAGE1_FLAG;

    var stats = new IndexStatistics(totalCount, distinctCount, nullCount);

    // Guard against corrupted histogramDataLength: a value that is negative
    // or exceeds the page capacity would cause an OOM or IndexOutOfBounds
    // before EquiDepthHistogram.deserialize() gets to validate the blob.
    if (histogramDataLength < 0
        || VARIABLE_DATA_OFFSET + histogramDataLength > MAX_PAGE_SIZE_BYTES) {
      logger.warn("Histogram stats page has invalid histogramDataLength {}"
          + " (page capacity {}); treating as empty histogram",
          histogramDataLength, MAX_PAGE_SIZE_BYTES);
      histogramDataLength = 0;
    }

    EquiDepthHistogram histogram = null;
    if (histogramDataLength > 0) {
      byte[] histData =
          getBinaryValue(VARIABLE_DATA_OFFSET, histogramDataLength);
      histogram = EquiDepthHistogram.deserialize(
          histData, 0, keySerializer, serializerFactory);
    }

    // Read HLL inline from page 0 only when NOT spilled to page 1.
    // When spilled, hll is left null here; caller reads it from page 1.
    HyperLogLogSketch hll = null;
    if (hllRegisterCount > 0 && !hllOnPage1) {
      int hllOffset = VARIABLE_DATA_OFFSET + histogramDataLength;
      // Guard against corrupted hllOffset pointing past the page.
      if (hllOffset + hllRegisterCount <= MAX_PAGE_SIZE_BYTES) {
        byte[] hllData = getBinaryValue(hllOffset, hllRegisterCount);
        hll = HyperLogLogSketch.readFrom(hllData, 0);
      } else {
        logger.warn("Histogram stats page has HLL registers at offset {}"
            + " with count {} exceeding page capacity {}; skipping HLL",
            hllOffset, hllRegisterCount, MAX_PAGE_SIZE_BYTES);
      }
    }

    return new HistogramSnapshot(
        stats, histogram, mutationsSinceRebalance,
        totalCountAtLastBuild,
        0, // version resets to 0 on restart
        false, // hasDriftedBuckets resets to false on restart
        hll,
        hllOnPage1);
  }

  // Version 2 uses the legacy field sequence without the version word or HLL spill bit.
  static final int IMAGE_PREFIX_SIZE = FIXED_HEADER_SIZE - Integer.BYTES;
  private static final int HISTOGRAM_HEADER_SIZE = Integer.BYTES + 2 * Long.BYTES;
  // Each bucket adds a length-prefixed boundary and two counters.
  private static final int BUCKET_OVERHEAD = Integer.BYTES + 2 * Long.BYTES;

  static byte[][] prepareParts(HistogramSnapshot snapshot, BinarySerializer<Object> serializer,
      BinarySerializerFactory factory) {
    if (snapshot.hllOnPage1() && snapshot.hllSketch() == null) {
      throw new IllegalArgumentException("Statistics sketch must be loaded before encoding");
    }
    assert checkSnapshotInvariants(snapshot, serializer.getId());
    int registers = snapshot.hllSketch() == null ? 0 : HyperLogLogSketch.serializedSize();
    var histogram = snapshot.histogram();
    byte[] blob = new byte[0];
    if (histogram != null) {
      int budget = HistogramStatsPageContinuationCodec.IMAGE_CAPACITY
          - IMAGE_PREFIX_SIZE - registers;
      var keySerializer = nestedKeySerializer(serializer);
      checkHistogramFit(histogram, keySerializer, factory, budget);
      blob = histogram.serialize(keySerializer, factory);
    }
    var image = ByteBuffer.allocate(IMAGE_PREFIX_SIZE + blob.length + registers)
        .order(ByteOrder.nativeOrder());
    var stats = snapshot.stats();
    image.put(serializer.getId()).putLong(stats.totalCount()).putLong(stats.distinctCount())
        .putLong(stats.nullCount()).putLong(snapshot.mutationsSinceRebalance())
        .putLong(snapshot.totalCountAtLastBuild()).putInt(blob.length).putInt(registers).put(blob);
    if (registers != 0) {
      snapshot.hllSketch().writeTo(image.array(), image.position());
    }
    return HistogramStatsPageContinuationCodec.encode(image.array());
  }

  @SuppressWarnings("unchecked")
  private static BinarySerializer<Object> nestedKeySerializer(BinarySerializer<Object> serializer) {
    // Keep the outer DATE ID but store the nested instant without local midnight normalization.
    return serializer.getId() == DateSerializer.ID
        ? (BinarySerializer<Object>) (BinarySerializer<?>) DateTimeSerializer.INSTANCE : serializer;
  }

  private static boolean checkSnapshotInvariants(HistogramSnapshot snapshot, byte serializerId) {
    var stats = snapshot.stats();
    assert (stats.totalCount() | stats.distinctCount() | stats.nullCount()
        | snapshot.mutationsSinceRebalance() | snapshot.totalCountAtLastBuild()) >= 0
        && stats.nullCount() <= stats.totalCount() : "Invalid statistics snapshot counts";
    var histogram = snapshot.histogram();
    if (histogram != null) {
      // The constructor checks array lengths and nonNullCount. The arrays are mutable.
      var boundaries = histogram.boundaries();
      assert boundaries[0] != null : "Missing first statistics histogram boundary";
      assert histogram.mcvFrequency() >= 0 : "Negative statistics MCV frequency";
      assert histogram.mcvValue() == null || isBuilderKey(histogram.mcvValue(), serializerId)
          : "Unrepresentable statistics histogram key";
      assert isBuilderKey(boundaries[0], serializerId)
          : "Unrepresentable statistics histogram key";
      for (int i = 0; i < histogram.bucketCount(); i++) {
        assert histogram.frequencies()[i] >= 0 && histogram.distinctCounts()[i] >= 0
            : "Negative statistics bucket count";
        assert boundaries[i + 1] == null || isBuilderKey(boundaries[i + 1], serializerId)
            : "Unrepresentable statistics histogram key";
        assert boundaries[i + 1] != null
            && DefaultComparator.INSTANCE.compare(boundaries[i], boundaries[i + 1]) <= 0
            : "Statistics histogram boundaries must be nondecreasing";
      }
    }
    return true;
  }

  // Check the builder guarantees only under assertions. Link keys are plain RecordId values,
  // helper collection IDs fit signed shorts, and nested dates are plain Date values.
  // Floating-point NaNs have canonical bits, and decimals are plain BigDecimal values.
  private static boolean isBuilderKey(Comparable<?> key, byte serializerId) {
    return switch (serializerId) {
      case LinkSerializer.ID, StreamSerializerRID.ID, CompactedLinkSerializer.ID ->
          key.getClass() == RecordId.class;
      case MultiValueEntrySerializer.ID ->
          key instanceof MultiValueEntry entry && entry.collectionId == (short) entry.collectionId;
      case DateSerializer.ID, DateTimeSerializer.ID -> key.getClass() == Date.class;
      case FloatSerializer.ID ->
          Float.floatToRawIntBits((Float) key) == Float.floatToIntBits((Float) key);
      case DoubleSerializer.ID ->
          Double.doubleToRawLongBits((Double) key) == Double.doubleToLongBits((Double) key);
      case DecimalSerializer.ID -> key.getClass() == BigDecimal.class;
      default -> true;
    };
  }

  private static void checkHistogramFit(EquiDepthHistogram histogram,
      BinarySerializer<Object> serializer, BinarySerializerFactory factory, int budget) {
    var layout = histogramKeyLayout(serializer);
    // Include the MCV length and the final boundary length before charging key payloads.
    long overhead = HISTOGRAM_HEADER_SIZE + 2 * Integer.BYTES
        + (long) histogram.bucketCount() * BUCKET_OVERHEAD;
    if (overhead > budget) {
      throw new IllegalArgumentException("Statistics image exceeds two-page budget");
    }
    int remaining = budget - (int) overhead;
    if (histogram.mcvValue() != null) {
      remaining -= checkedKeySize(histogram.mcvValue(), layout, serializer, factory, remaining);
    }
    for (int i = 0; i <= histogram.bucketCount(); i++) {
      remaining -= checkedKeySize(histogram.boundaries()[i], layout, serializer, factory,
          remaining);
    }
  }

  private static int checkedKeySize(Comparable<?> key, HistogramKeyLayout layout,
      BinarySerializer<Object> serializer, BinarySerializerFactory factory, int remaining) {
    // Bound variable keys before sizing. UTF-8 and decimal sizing allocate temporary bytes,
    // and native UTF-16 sizing uses int arithmetic.
    long minimum = switch (layout) {
      case UTF8 -> Short.BYTES + (long) ((String) key).length();
      case UTF16 -> Integer.BYTES + (long) Character.BYTES * ((String) key).length();
      case DECIMAL -> 2 * Integer.BYTES + ((BigDecimal) key).unscaledValue().bitLength() / 8 + 1;
      case COMPACT_LINK -> Short.BYTES + Byte.BYTES;
      case FIXED -> serializer.getFixedLength();
    };
    if (minimum > remaining) {
      throw new IllegalArgumentException("Statistics image exceeds two-page budget");
    }
    if (layout == HistogramKeyLayout.UTF8 && hasUnpairedSurrogate((String) key)) {
      throw new IllegalArgumentException("Unrepresentable statistics histogram key");
    }
    int size = serializer.getObjectSize(factory, key);
    if (size <= 0 || size > remaining) {
      throw new IllegalArgumentException("Statistics image exceeds two-page budget");
    }
    return size;
  }

  private static boolean hasUnpairedSurrogate(String key) {
    // codePoints combines valid surrogate pairs and leaves lone surrogates as code units.
    return key.codePoints()
        .anyMatch(codePoint -> Character.getType(codePoint) == Character.SURROGATE);
  }

  void writePart(byte[] part) {
    if (part.length > partSpace()) {
      throw new IllegalArgumentException("Statistics fragment exceeds physical page");
    }
    setBinaryValue(NEXT_FREE_POSITION, part);
  }

  private int partSpace() {
    return getCacheEntry().getCachePointer().getBuffer().limit() - NEXT_FREE_POSITION;
  }

  private byte[] readPart() {
    int headerSize = HistogramStatsPageContinuationCodec.HEADER_SIZE;
    if (partSpace() < headerSize) {
      throw new IllegalArgumentException("Truncated statistics page");
    }
    var header = ByteBuffer.wrap(getBinaryValue(NEXT_FREE_POSITION, headerSize))
        .order(ByteOrder.nativeOrder());
    int size = HistogramStatsPageContinuationCodec.partSize(header, getPageIndex());
    if (size > partSpace()) {
      throw new IllegalArgumentException("Truncated statistics fragment");
    }
    return getBinaryValue(NEXT_FREE_POSITION, size);
  }

  /** Reads a complete stored image. Callers supply the continuation only when available. */
  HistogramSnapshot readStoredSnapshot(HistogramStatsPage continuation,
      BinarySerializer<Object> serializer, BinarySerializerFactory factory) {
    if (getIntValue(FORMAT_VERSION_OFFSET) != HistogramStatsPageContinuationCodec.FORMAT_VERSION) {
      return readSnapshot(serializer, factory);
    }
    byte[] first = readPart();
    byte[] second = null;
    if (ByteBuffer.wrap(first).order(ByteOrder.nativeOrder()).getInt(Integer.BYTES)
        > HistogramStatsPageContinuationCodec.PART_CAPACITY && continuation != null) {
      if (continuation.getCacheEntry().getFileId() != getCacheEntry().getFileId()) {
        throw new IllegalArgumentException("Statistics continuation is in a different file");
      }
      second = continuation.readPart();
    }
    byte[] bytes = HistogramStatsPageContinuationCodec.assemble(first, second);
    var image = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
    if (image.remaining() < IMAGE_PREFIX_SIZE || image.get() != serializer.getId()) {
      throw new IllegalArgumentException("Invalid statistics image prefix");
    }
    long total = image.getLong();
    long distinct = image.getLong();
    long nulls = image.getLong();
    long mutations = image.getLong();
    long lastBuild = image.getLong();
    int histLength = image.getInt();
    int registers = image.getInt();
    // Estimated distinct count and the previous build count may exceed the current total.
    if ((total | distinct | nulls | mutations | lastBuild) < 0 || nulls > total
        || (registers != 0 && registers != HyperLogLogSketch.serializedSize())
        || histLength < 0 || histLength != image.remaining() - registers) {
      throw new IllegalArgumentException("Invalid statistics image fields");
    }

    EquiDepthHistogram histogram = null;
    if (histLength != 0) {
      byte[] blob = new byte[histLength];
      image.get(blob);
      var keySerializer = nestedKeySerializer(serializer);
      validateHistogram(blob, keySerializer);
      histogram = EquiDepthHistogram.deserialize(blob, 0, keySerializer, factory);
      if (histogram == null || !Arrays.equals(blob, histogram.serialize(keySerializer, factory))) {
        throw new IllegalArgumentException("Invalid statistics histogram");
      }
      var boundaries = histogram.boundaries();
      for (int i = 1; i < boundaries.length; i++) {
        if (DefaultComparator.INSTANCE.compare(boundaries[i - 1], boundaries[i]) > 0) {
          throw new IllegalArgumentException(
              "Statistics histogram boundaries must be nondecreasing");
        }
      }
    }
    for (int i = image.position(); i < bytes.length; i++) {
      if (bytes[i] < 0 || bytes[i] > HyperLogLogSketch.MAX_REGISTER_VALUE) {
        throw new IllegalArgumentException("Invalid statistics sketch register");
      }
    }
    var sketch = registers == 0 ? null : HyperLogLogSketch.readFrom(bytes, image.position());
    return new HistogramSnapshot(new IndexStatistics(total, distinct, nulls), histogram, mutations,
        lastBuild, 0, false, sketch, false);
  }

  // Check the native histogram layout before the legacy decoder can allocate from key lengths.
  private static void validateHistogram(byte[] blob, BinarySerializer<?> serializer) {
    var fields = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder());
    if (fields.remaining() < HISTOGRAM_HEADER_SIZE) {
      throw new IllegalArgumentException("Truncated statistics histogram header");
    }
    int buckets = fields.getInt();
    long nonNullCount = fields.getLong();
    long mcvFrequency = fields.getLong();
    // Each bucket needs two counters and a boundary length, plus the final boundary and MCV
    // lengths. The image budget already bounds the bucket count below the legacy decoder limit.
    int maxBuckets = (fields.remaining() - 2 * Integer.BYTES) / BUCKET_OVERHEAD;
    if (buckets <= 0 || buckets > maxBuckets || (nonNullCount | mcvFrequency) < 0) {
      throw new IllegalArgumentException("Invalid statistics histogram header");
    }

    int end = fields.limit();
    fields.limit(end - buckets * 2 * Long.BYTES);
    var layout = histogramKeyLayout(serializer);
    validateHistogramKey(fields, serializer, layout, true);
    for (int i = 0; i <= buckets; i++) {
      validateHistogramKey(fields, serializer, layout, false);
    }
    if (fields.hasRemaining()) {
      throw new IllegalArgumentException("Unexpected data after statistics histogram keys");
    }
    fields.limit(end);
    // Estimates can drift independently. Only enforce the sign of each stored count.
    while (fields.hasRemaining()) {
      if (fields.getLong() < 0) {
        throw new IllegalArgumentException("Negative statistics bucket count");
      }
    }
  }

  private enum HistogramKeyLayout {
    UTF8, UTF16, DECIMAL, COMPACT_LINK, FIXED
  }

  // Keyless images only need serializer ID matching. Populated images need a readable layout.
  private static HistogramKeyLayout histogramKeyLayout(BinarySerializer<?> serializer) {
    return switch (serializer.getId()) {
      case UTF8Serializer.ID -> HistogramKeyLayout.UTF8;
      case StringSerializer.ID -> HistogramKeyLayout.UTF16;
      case DecimalSerializer.ID -> HistogramKeyLayout.DECIMAL;
      case CompactedLinkSerializer.ID -> HistogramKeyLayout.COMPACT_LINK;
      default -> {
        if (!serializer.isFixedLength() || serializer.getFixedLength() <= 0) {
          throw new IllegalArgumentException("Unsupported statistics histogram key layout");
        }
        yield HistogramKeyLayout.FIXED;
      }
    };
  }

  private static void validateHistogramKey(ByteBuffer fields, BinarySerializer<?> serializer,
      HistogramKeyLayout layout, boolean optional) {
    if (fields.remaining() < Integer.BYTES) {
      throw new IllegalArgumentException("Missing statistics histogram key length");
    }
    int length = fields.getInt();
    if (optional && length == 0) {
      return;
    }
    if (length <= 0 || length > fields.remaining()) {
      throw new IllegalArgumentException("Invalid statistics histogram key length");
    }

    int start = fields.position();
    // Check each variable-width key's inner length before invoking its native decoder.
    boolean valid = switch (layout) {
      case UTF8 -> length >= Short.BYTES
          && Short.toUnsignedInt(fields.getShort(start)) == length - Short.BYTES;
      case UTF16 -> length >= Integer.BYTES
          && (length - Integer.BYTES) % Character.BYTES == 0
          && fields.getInt(start) == (length - Integer.BYTES) / Character.BYTES;
      case DECIMAL -> length > 2 * Integer.BYTES
          && fields.getInt(start + Integer.BYTES) == length - 2 * Integer.BYTES;
      case COMPACT_LINK -> isValidCompactLinkExtent(fields, start, length);
      case FIXED -> serializer.getFixedLength() == length;
    };
    if (!valid) {
      throw new IllegalArgumentException("Invalid encoded statistics histogram key extent");
    }
    fields.position(start + length);
  }

  private static boolean isValidCompactLinkExtent(ByteBuffer fields, int start, int length) {
    // Native compact links store a collection short, then the number of position bytes.
    int headerSize = Short.BYTES + Byte.BYTES;
    if (length < headerSize) {
      return false;
    }
    int width = Byte.toUnsignedInt(fields.get(start + Short.BYTES));
    return width <= Long.BYTES && width == length - headerSize;
  }

  // ---- Page-1 HLL I/O ----

  /**
   * Writes HLL registers to page 1. Called when the HLL was spilled
   * from page 0 because boundaries exhaust the budget.
   *
   * <p>Page 1 layout: DurablePage header (28 bytes) + HLL registers
   * (1024 bytes starting at {@code NEXT_FREE_POSITION}).
   *
   * @param page1CacheEntry cache entry for page 1 (must be write-locked)
   * @param hll             the HLL sketch to persist
   */
  static void writeHllToPage1(CacheEntry page1CacheEntry,
      HyperLogLogSketch hll) {
    // Use HistogramStatsPage wrapper to access protected DurablePage methods
    var page1 = new HistogramStatsPage(page1CacheEntry);
    byte[] hllData = new byte[HyperLogLogSketch.serializedSize()];
    hll.writeTo(hllData, 0);
    page1.setBinaryValue(NEXT_FREE_POSITION, hllData);

    if (page1CacheEntry instanceof CacheEntryChanges cec) {
      cec.registerPageOperation(
          new HistogramStatsPageWriteHllToPage1Op(
              page1CacheEntry.getPageIndex(), page1CacheEntry.getFileId(),
              0, cec.getInitialLSN(), hllData));
    }
  }

  /**
   * Reads HLL registers from page 1.
   *
   * @param page1CacheEntry cache entry for page 1 (must be read-locked)
   * @return a new HLL sketch populated from the page-1 data
   */
  static HyperLogLogSketch readHllFromPage1(CacheEntry page1CacheEntry) {
    var page1 = new HistogramStatsPage(page1CacheEntry);
    byte[] hllData = page1.getBinaryValue(
        NEXT_FREE_POSITION, HyperLogLogSketch.serializedSize());
    return HyperLogLogSketch.readFrom(hllData, 0);
  }

  // ---- Accessors for individual fields (used by tests) ----

  long getTotalCount() {
    return getLongValue(TOTAL_COUNT_OFFSET);
  }

  long getDistinctCount() {
    return getLongValue(DISTINCT_COUNT_OFFSET);
  }

  long getNullCount() {
    return getLongValue(NULL_COUNT_OFFSET);
  }

  int getHistogramDataLength() {
    return getIntValue(HISTOGRAM_DATA_LENGTH_OFFSET);
  }

  /**
   * Returns the raw persisted HLL register count value. May have the
   * {@link #HLL_PAGE1_FLAG} high bit set when registers are on page 1.
   * Use {@code value & ~HLL_PAGE1_FLAG} to extract the actual count.
   */
  int getRawHllRegisterCount() {
    return getIntValue(HLL_REGISTER_COUNT_OFFSET);
  }
}
