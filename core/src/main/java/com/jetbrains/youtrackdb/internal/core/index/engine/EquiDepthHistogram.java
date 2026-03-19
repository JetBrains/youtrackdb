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
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Equi-depth histogram for selectivity estimation.
 *
 * <p>Present only when {@code totalCount >= HISTOGRAM_MIN_SIZE}.
 *
 * <p><b>Immutability contract:</b> This record is treated as deeply immutable.
 * The {@code boundaries}, {@code frequencies}, and {@code distinctCounts} arrays
 * must NOT be mutated after construction. Callers receiving a histogram from
 * {@code HistogramSnapshot} must never modify its arrays. The constructor does
 * not make defensive copies (to avoid allocation on the hot read path); correct
 * usage is enforced by convention, not by the type system.
 *
 * <p>Null entries are tracked exclusively in {@link IndexStatistics#nullCount()},
 * not duplicated here. IS NULL / IS NOT NULL selectivity is computed from
 * {@code stats.nullCount} and {@code histogram.nonNullCount}.
 *
 * <p><b>Boundary format (PostgreSQL convention):</b> The boundaries array stores
 * {@code bucketCount + 1} values — explicit lower and upper bounds for every
 * bucket. Bucket {@code i} spans {@code [boundaries[i], boundaries[i+1])} for
 * {@code i < bucketCount - 1}. The last bucket spans
 * {@code [boundaries[bucketCount-1], boundaries[bucketCount]]} — closed on both
 * ends — because {@code boundaries[bucketCount]} is the maximum key and must be
 * included.
 *
 * @param bucketCount    number of buckets (target: HISTOGRAM_BUCKETS, may be
 *                       reduced for variable-length keys to fit within a single
 *                       page)
 * @param boundaries     sorted array of (bucketCount + 1) boundary values;
 *                       bucket i spans [boundaries[i], boundaries[i+1])
 *                       boundaries[0] = min key, boundaries[bucketCount] = max key
 * @param frequencies    frequencies[i] = number of entries in bucket i;
 *                       clamped to >= 0 (may drift below actual due to deletions
 *                       of pre-histogram entries; corrected on rebalance)
 * @param distinctCounts distinctCounts[i] = NDV within bucket i
 *                       (computed during build/rebalance only, not updated
 *                       incrementally)
 * @param nonNullCount   sum of all frequencies (excludes null entries);
 *                       clamped to >= 0
 * @param mcvValue       most common value (MCV); null if NDV == 0
 * @param mcvFrequency   exact frequency of mcvValue at build/rebalance time;
 *                       not maintained incrementally — stales between rebalances
 *                       but always >= bucket-averaged estimate, so still valuable
 */
public record EquiDepthHistogram(
    int bucketCount,
    Comparable<?>[] boundaries,
    long[] frequencies,
    long[] distinctCounts,
    long nonNullCount,
    @Nullable Comparable<?> mcvValue,
    long mcvFrequency) {

  private static final Logger logger =
      LoggerFactory.getLogger(EquiDepthHistogram.class);

  /**
   * Maximum bucket count accepted during deserialization. The production
   * target is 128 (configurable); this ceiling is a corruption-detection
   * guard — any persisted value above it indicates page damage.
   */
  static final int MAX_DESERIALIZE_BUCKET_COUNT = 10_000;

  // Compact constructor: validate structural invariants via assertions.
  // These are disabled in production but catch programming errors during
  // development and testing.
  public EquiDepthHistogram {
    assert bucketCount > 0 : "bucketCount must be positive";
    assert boundaries.length == bucketCount + 1
        : "boundaries must have bucketCount + 1 elements";
    assert frequencies.length == bucketCount
        : "frequencies must have bucketCount elements";
    assert distinctCounts.length == bucketCount
        : "distinctCounts must have bucketCount elements";
    assert nonNullCount >= 0 : "nonNullCount must be non-negative";
  }

  // Threshold for switching between linear scan and binary search in findBucket.
  private static final int LINEAR_SCAN_THRESHOLD = 8;

  /**
   * Finds the bucket index for the given key using the histogram boundaries.
   *
   * <p>Returns index {@code B} such that
   * {@code boundaries[B] <= key < boundaries[B+1]}, except for the last bucket
   * which is closed on both ends: keys {@code >= boundaries[bucketCount]} map to
   * the last bucket.
   *
   * <p>Keys below {@code boundaries[0]} map to bucket 0.
   *
   * <p>Uses linear scan for small histograms ({@code bucketCount <= 8}) and
   * binary search for larger ones. Both are pure in-memory operations with no I/O,
   * operating on the cached boundaries array.
   *
   * @param key the key to locate (must be non-null)
   * @return the bucket index in {@code [0, bucketCount - 1]}
   */
  public int findBucket(Object key) {
    assert key != null : "findBucket() requires a non-null key";
    if (bucketCount <= LINEAR_SCAN_THRESHOLD) {
      return findBucketLinear(key);
    }
    return findBucketBinary(key);
  }

  /**
   * Linear scan for small histograms. Scans boundaries from index 1 upward;
   * returns the first bucket whose upper boundary is strictly greater than
   * the key. Early exit gives better branch prediction for few buckets.
   */
  private int findBucketLinear(Object key) {
    var comparator = DefaultComparator.INSTANCE;
    // Scan upper boundaries: boundaries[1] through boundaries[bucketCount].
    // Bucket i spans [boundaries[i], boundaries[i+1]).
    for (int i = 1; i <= bucketCount; i++) {
      if (comparator.compare(key, boundaries[i]) < 0) {
        return i - 1;
      }
    }
    // Key >= boundaries[bucketCount] (max key) → last bucket
    return bucketCount - 1;
  }

  /**
   * Binary search for larger histograms. Searches boundaries[0..bucketCount]
   * for the rightmost boundary <= key, then maps to the corresponding bucket.
   */
  private int findBucketBinary(Object key) {
    var comparator = DefaultComparator.INSTANCE;
    int lo = 0;
    int hi = bucketCount; // search range: boundaries[0..bucketCount]

    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1; // round up to find rightmost match
      int cmp = comparator.compare(boundaries[mid], key);
      if (cmp <= 0) {
        lo = mid; // boundaries[mid] <= key, search right half
      } else {
        hi = mid - 1; // boundaries[mid] > key, search left half
      }
    }

    // lo is now the index of the rightmost boundary <= key.
    // Clamp to valid bucket range [0, bucketCount - 1].
    return Math.min(lo, bucketCount - 1);
  }

  /**
   * Serializes this histogram to a byte array.
   *
   * <p>Wire format:
   * <pre>
   *   bucketCount           4 bytes (int)
   *   nonNullCount          8 bytes (long)
   *   mcvFrequency          8 bytes (long)
   *   mcvKeyLength          4 bytes (int) — 0 if no MCV
   *   mcvKey                mcvKeyLength bytes
   *   boundaries[]          variable ((bucketCount + 1) serialized keys,
   *                         each prefixed with 4-byte length)
   *   frequencies[]         bucketCount × 8 bytes (long[])
   *   distinctCounts[]      bucketCount × 8 bytes (long[])
   * </pre>
   *
   * @param serializer        the key serializer for boundary values
   * @param serializerFactory the serializer factory for key serialization
   * @return the serialized byte array
   */
  @SuppressWarnings("unchecked")
  public byte[] serialize(
      BinarySerializer<Object> serializer,
      BinarySerializerFactory serializerFactory) {
    // Pre-serialize boundaries and MCV to compute total size
    byte[] mcvBytes = null;
    if (mcvValue != null) {
      mcvBytes = serializer.serializeNativeAsWhole(
          serializerFactory, mcvValue);
    }
    int mcvKeyLen = mcvBytes != null ? mcvBytes.length : 0;

    byte[][] boundaryBytes = new byte[bucketCount + 1][];
    int boundaryTotalLen = 0;
    for (int i = 0; i <= bucketCount; i++) {
      boundaryBytes[i] = serializer.serializeNativeAsWhole(
          serializerFactory, boundaries[i]);
      // 4-byte length prefix + serialized key
      boundaryTotalLen += IntegerSerializer.INT_SIZE + boundaryBytes[i].length;
    }

    // Total size: bucketCount(4) + nonNullCount(8) + mcvFrequency(8) +
    //   mcvKeyLength(4) + mcvKey(mcvKeyLen) + boundaries(variable) +
    //   frequencies(bucketCount * 8) + distinctCounts(bucketCount * 8)
    int totalSize = IntegerSerializer.INT_SIZE + LongSerializer.LONG_SIZE
        + LongSerializer.LONG_SIZE
        + IntegerSerializer.INT_SIZE + mcvKeyLen
        + boundaryTotalLen
        + bucketCount * LongSerializer.LONG_SIZE
        + bucketCount * LongSerializer.LONG_SIZE;

    byte[] data = new byte[totalSize];
    int pos = 0;

    IntegerSerializer.serializeNative(bucketCount, data, pos);
    pos += IntegerSerializer.INT_SIZE;

    LongSerializer.serializeNative(nonNullCount, data, pos);
    pos += LongSerializer.LONG_SIZE;

    LongSerializer.serializeNative(mcvFrequency, data, pos);
    pos += LongSerializer.LONG_SIZE;

    // MCV key
    IntegerSerializer.serializeNative(mcvKeyLen, data, pos);
    pos += IntegerSerializer.INT_SIZE;
    if (mcvBytes != null) {
      System.arraycopy(mcvBytes, 0, data, pos, mcvBytes.length);
      pos += mcvBytes.length;
    }

    // Boundaries: each prefixed with 4-byte length
    for (int i = 0; i <= bucketCount; i++) {
      IntegerSerializer.serializeNative(
          boundaryBytes[i].length, data, pos);
      pos += IntegerSerializer.INT_SIZE;
      System.arraycopy(boundaryBytes[i], 0, data, pos,
          boundaryBytes[i].length);
      pos += boundaryBytes[i].length;
    }

    // Frequencies
    for (int i = 0; i < bucketCount; i++) {
      LongSerializer.serializeNative(frequencies[i], data, pos);
      pos += LongSerializer.LONG_SIZE;
    }

    // Distinct counts
    for (int i = 0; i < bucketCount; i++) {
      LongSerializer.serializeNative(distinctCounts[i], data, pos);
      pos += LongSerializer.LONG_SIZE;
    }

    return data;
  }

  /**
   * Deserializes a histogram from a byte array.
   *
   * @param data              the serialized byte array
   * @param offset            the starting byte offset
   * @param serializer        the key serializer for boundary values
   * @param serializerFactory the serializer factory for key deserialization
   * @return the deserialized histogram, or null if bucketCount is 0
   */
  @Nullable public static EquiDepthHistogram deserialize(
      byte[] data, int offset,
      BinarySerializer<Object> serializer,
      BinarySerializerFactory serializerFactory) {
    int pos = offset;

    int readBucketCount = IntegerSerializer.deserializeNative(data, pos);
    pos += IntegerSerializer.INT_SIZE;

    // Guard against corrupted data: zero means no histogram, negative or
    // absurdly large values indicate page corruption.
    if (readBucketCount <= 0 || readBucketCount > MAX_DESERIALIZE_BUCKET_COUNT) {
      if (readBucketCount != 0) {
        logger.warn("Histogram deserialization failed: invalid bucketCount {}"
            + " at offset {} (data length {})", readBucketCount, offset, data.length);
      }
      return null;
    }

    long readNonNullCount = Math.max(0,
        LongSerializer.deserializeNative(data, pos));
    pos += LongSerializer.LONG_SIZE;

    long readMcvFrequency = LongSerializer.deserializeNative(data, pos);
    pos += LongSerializer.LONG_SIZE;

    // Read MCV key
    int mcvKeyLen = IntegerSerializer.deserializeNative(data, pos);
    pos += IntegerSerializer.INT_SIZE;

    // Use overflow-safe form: mcvKeyLen > data.length - pos
    // (pos is always <= data.length here, so the subtraction is non-negative)
    if (mcvKeyLen < 0 || mcvKeyLen > data.length - pos) {
      logger.warn("Histogram deserialization failed: invalid mcvKeyLen {}"
          + " at pos {} (data length {})", mcvKeyLen, pos, data.length);
      return null;
    }

    Comparable<?> readMcvValue = null;
    if (mcvKeyLen > 0) {
      readMcvValue = (Comparable<?>) serializer.deserializeNativeObject(
          serializerFactory, data, pos);
      pos += mcvKeyLen;
    }

    // Read boundaries
    Comparable<?>[] readBoundaries = new Comparable<?>[readBucketCount + 1];
    for (int i = 0; i <= readBucketCount; i++) {
      if (IntegerSerializer.INT_SIZE > data.length - pos) {
        logger.warn("Histogram deserialization failed: truncated boundary"
            + " length at index {} pos {} (data length {})", i, pos, data.length);
        return null;
      }
      int keyLen = IntegerSerializer.deserializeNative(data, pos);
      pos += IntegerSerializer.INT_SIZE;
      if (keyLen < 0 || keyLen > data.length - pos) {
        logger.warn("Histogram deserialization failed: invalid boundary"
            + " keyLen {} at index {} pos {} (data length {})",
            keyLen, i, pos, data.length);
        return null;
      }
      readBoundaries[i] = (Comparable<?>) serializer.deserializeNativeObject(
          serializerFactory, data, pos);
      pos += keyLen;
    }

    // Read frequencies
    long[] readFrequencies = new long[readBucketCount];
    for (int i = 0; i < readBucketCount; i++) {
      if (LongSerializer.LONG_SIZE > data.length - pos) {
        logger.warn("Histogram deserialization failed: truncated frequency"
            + " at index {} pos {} (data length {})", i, pos, data.length);
        return null;
      }
      readFrequencies[i] = LongSerializer.deserializeNative(data, pos);
      pos += LongSerializer.LONG_SIZE;
    }

    // Read distinct counts
    long[] readDistinctCounts = new long[readBucketCount];
    for (int i = 0; i < readBucketCount; i++) {
      if (LongSerializer.LONG_SIZE > data.length - pos) {
        logger.warn("Histogram deserialization failed: truncated distinctCount"
            + " at index {} pos {} (data length {})", i, pos, data.length);
        return null;
      }
      readDistinctCounts[i] = LongSerializer.deserializeNative(data, pos);
      pos += LongSerializer.LONG_SIZE;
    }

    return new EquiDepthHistogram(
        readBucketCount,
        readBoundaries,
        readFrequencies,
        readDistinctCounts,
        readNonNullCount,
        readMcvValue,
        readMcvFrequency);
  }
}
