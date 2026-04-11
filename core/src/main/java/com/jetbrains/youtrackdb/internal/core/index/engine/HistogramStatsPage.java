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

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Page accessor for the histogram statistics page (.ixs file, page 0).
 *
 * <p>Layout (offsets absolute from page start):
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
