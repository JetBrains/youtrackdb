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
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

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
 * 77  hllRegisterCount          4 bytes (int) — 0 for single-value
 * 81  [histogram data blob]     histogramDataLength bytes
 * ... [HLL registers]           hllRegisterCount bytes
 * </pre>
 *
 * <p>The histogram data blob is produced by
 * {@link EquiDepthHistogram#serialize} and includes bucketCount,
 * nonNullCount, mcvFrequency, mcvKeyLength, mcvKey, boundaries,
 * frequencies, and distinctCounts.
 */
final class HistogramStatsPage extends DurablePage {

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
  }

  void writeSnapshot(HistogramSnapshot snapshot, byte serializerId,
      BinarySerializer<Object> keySerializer,
      BinarySerializerFactory serializerFactory) {
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
    setIntValue(HLL_REGISTER_COUNT_OFFSET, hllRegisterCount);

    // Write histogram data blob
    int histogramDataLength = 0;
    if (snapshot.histogram() != null) {
      byte[] histData = snapshot.histogram().serialize(
          keySerializer, serializerFactory);
      histogramDataLength = histData.length;
      setIntValue(HISTOGRAM_DATA_LENGTH_OFFSET, histogramDataLength);
      setBinaryValue(VARIABLE_DATA_OFFSET, histData);
    } else {
      setIntValue(HISTOGRAM_DATA_LENGTH_OFFSET, 0);
    }

    // Write HLL registers after histogram data
    if (hllRegisterCount > 0) {
      int hllOffset = VARIABLE_DATA_OFFSET + histogramDataLength;
      byte[] hllData = new byte[hllRegisterCount];
      snapshot.hllSketch().writeTo(hllData, 0);
      setBinaryValue(hllOffset, hllData);
    }
  }

  // ---- Read methods ----

  HistogramSnapshot readSnapshot(
      BinarySerializer<Object> keySerializer,
      BinarySerializerFactory serializerFactory) {
    long totalCount = getLongValue(TOTAL_COUNT_OFFSET);
    long distinctCount = getLongValue(DISTINCT_COUNT_OFFSET);
    long nullCount = getLongValue(NULL_COUNT_OFFSET);
    long mutationsSinceRebalance =
        getLongValue(MUTATIONS_SINCE_REBALANCE_OFFSET);
    long totalCountAtLastBuild =
        getLongValue(TOTAL_COUNT_AT_LAST_BUILD_OFFSET);
    int histogramDataLength = getIntValue(HISTOGRAM_DATA_LENGTH_OFFSET);
    int hllRegisterCount = getIntValue(HLL_REGISTER_COUNT_OFFSET);

    var stats = new IndexStatistics(totalCount, distinctCount, nullCount);

    EquiDepthHistogram histogram = null;
    if (histogramDataLength > 0) {
      byte[] histData =
          getBinaryValue(VARIABLE_DATA_OFFSET, histogramDataLength);
      histogram = EquiDepthHistogram.deserialize(
          histData, 0, keySerializer, serializerFactory);
    }

    HyperLogLogSketch hll = null;
    if (hllRegisterCount > 0) {
      int hllOffset = VARIABLE_DATA_OFFSET + histogramDataLength;
      byte[] hllData = getBinaryValue(hllOffset, hllRegisterCount);
      hll = HyperLogLogSketch.readFrom(hllData, 0);
    }

    return new HistogramSnapshot(
        stats, histogram, mutationsSinceRebalance,
        totalCountAtLastBuild,
        0,     // version resets to 0 on restart
        false, // hasDriftedBuckets resets to false on restart
        hll
    );
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

  int getHllRegisterCount() {
    return getIntValue(HLL_REGISTER_COUNT_OFFSET);
  }
}
