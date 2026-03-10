package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for {@link EquiDepthHistogram}.
 *
 * <p>Covers: findBucket() (linear scan and binary search, boundary cases),
 * serialization round-trip (integer, long, string keys, with and without MCV),
 * N+1 boundary convention, single-bucket histograms, and deserialization
 * corruption guards.
 */
public class EquiDepthHistogramTest {

  private static BinarySerializerFactory serializerFactory;

  @BeforeClass
  public static void beforeClass() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
  }

  // ── Helper: cast a typed serializer to BinarySerializer<Object> ──

  @SuppressWarnings("unchecked")
  private static <T> BinarySerializer<Object> objectSerializer(
      BinarySerializer<T> serializer) {
    return (BinarySerializer<Object>) (BinarySerializer<?>) serializer;
  }

  // ── Helper: create a histogram with integer boundaries ───────

  /**
   * Creates a simple histogram with evenly spaced integer boundaries.
   * Bucket i spans [i * step, (i+1) * step) for i < bucketCount - 1,
   * last bucket spans [(bucketCount-1) * step, bucketCount * step].
   */
  private static EquiDepthHistogram createIntHistogram(
      int bucketCount, int step, long freqPerBucket,
      long distinctPerBucket) {
    Comparable<?>[] boundaries = new Comparable<?>[bucketCount + 1];
    long[] frequencies = new long[bucketCount];
    long[] distinctCounts = new long[bucketCount];
    long nonNullCount = 0;

    for (int i = 0; i <= bucketCount; i++) {
      boundaries[i] = i * step;
    }
    for (int i = 0; i < bucketCount; i++) {
      frequencies[i] = freqPerBucket;
      distinctCounts[i] = distinctPerBucket;
      nonNullCount += freqPerBucket;
    }

    return new EquiDepthHistogram(
        bucketCount, boundaries, frequencies, distinctCounts,
        nonNullCount, null, 0);
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket() — linear scan path (bucketCount <= 8)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testFindBucketLinearMiddle() {
    // 4 buckets: [0,10), [10,20), [20,30), [30,40]
    var hist = createIntHistogram(4, 10, 100, 10);

    Assert.assertEquals(0, hist.findBucket(5));    // in first bucket
    Assert.assertEquals(1, hist.findBucket(15));   // in second bucket
    Assert.assertEquals(2, hist.findBucket(25));   // in third bucket
    Assert.assertEquals(3, hist.findBucket(35));   // in last bucket
  }

  @Test
  public void testFindBucketLinearOnBoundary() {
    // 4 buckets: [0,10), [10,20), [20,30), [30,40]
    var hist = createIntHistogram(4, 10, 100, 10);

    // Key exactly on a boundary → belongs to the bucket starting at that
    // boundary (lower bound is inclusive)
    Assert.assertEquals(0, hist.findBucket(0));   // boundary[0] → bucket 0
    Assert.assertEquals(1, hist.findBucket(10));  // boundary[1] → bucket 1
    Assert.assertEquals(2, hist.findBucket(20));  // boundary[2] → bucket 2
    Assert.assertEquals(3, hist.findBucket(30));  // boundary[3] → bucket 3
  }

  @Test
  public void testFindBucketLinearMaxKey() {
    // Key == boundaries[bucketCount] (max key) → last bucket
    var hist = createIntHistogram(4, 10, 100, 10);
    Assert.assertEquals(3, hist.findBucket(40));
  }

  @Test
  public void testFindBucketLinearAboveMax() {
    // Key > boundaries[bucketCount] → last bucket
    var hist = createIntHistogram(4, 10, 100, 10);
    Assert.assertEquals(3, hist.findBucket(999));
  }

  @Test
  public void testFindBucketLinearBelowMin() {
    // Key < boundaries[0] → bucket 0
    var hist = createIntHistogram(4, 10, 100, 10);
    Assert.assertEquals(0, hist.findBucket(-5));
  }

  @Test
  public void testFindBucketLinearSingleBucket() {
    // Single bucket: [0, 100]
    var hist = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{0, 100},
        new long[]{500},
        new long[]{100},
        500, null, 0);

    Assert.assertEquals(0, hist.findBucket(-1));   // below min
    Assert.assertEquals(0, hist.findBucket(0));    // at min
    Assert.assertEquals(0, hist.findBucket(50));   // in middle
    Assert.assertEquals(0, hist.findBucket(100));  // at max
    Assert.assertEquals(0, hist.findBucket(200));  // above max
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket() — binary search path (bucketCount > 8)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testFindBucketBinaryMiddle() {
    // 16 buckets with step 10: [0,10), [10,20), ... [150,160]
    var hist = createIntHistogram(16, 10, 100, 10);

    Assert.assertEquals(0, hist.findBucket(5));
    Assert.assertEquals(7, hist.findBucket(75));
    Assert.assertEquals(15, hist.findBucket(155));
  }

  @Test
  public void testFindBucketBinaryOnBoundary() {
    var hist = createIntHistogram(16, 10, 100, 10);

    Assert.assertEquals(0, hist.findBucket(0));
    Assert.assertEquals(5, hist.findBucket(50));
    Assert.assertEquals(10, hist.findBucket(100));
    Assert.assertEquals(15, hist.findBucket(150));
  }

  @Test
  public void testFindBucketBinaryMaxKey() {
    var hist = createIntHistogram(16, 10, 100, 10);
    Assert.assertEquals(15, hist.findBucket(160)); // max key
  }

  @Test
  public void testFindBucketBinaryAboveMax() {
    var hist = createIntHistogram(16, 10, 100, 10);
    Assert.assertEquals(15, hist.findBucket(9999));
  }

  @Test
  public void testFindBucketBinaryBelowMin() {
    var hist = createIntHistogram(16, 10, 100, 10);
    Assert.assertEquals(0, hist.findBucket(-100));
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket() — threshold boundary (exactly 8 buckets)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testFindBucketAt8BucketsUsesLinearScan() {
    // Exactly 8 buckets → should use linear scan path
    var hist = createIntHistogram(8, 10, 100, 10);

    Assert.assertEquals(0, hist.findBucket(0));
    Assert.assertEquals(3, hist.findBucket(35));
    Assert.assertEquals(7, hist.findBucket(75));
    Assert.assertEquals(7, hist.findBucket(80)); // max key
    Assert.assertEquals(7, hist.findBucket(999)); // above max
  }

  @Test
  public void testFindBucketAt9BucketsUsesBinarySearch() {
    // 9 buckets → should use binary search path
    var hist = createIntHistogram(9, 10, 100, 10);

    Assert.assertEquals(0, hist.findBucket(0));
    Assert.assertEquals(4, hist.findBucket(45));
    Assert.assertEquals(8, hist.findBucket(85));
    Assert.assertEquals(8, hist.findBucket(90)); // max key
    Assert.assertEquals(8, hist.findBucket(999)); // above max
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket() — consistency between linear and binary paths
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testFindBucketConsistencyAcrossPaths() {
    // Verify that the linear scan path (bucketCount <= 8) and the binary
    // search path (bucketCount > 8) produce identical results for the
    // same boundaries. We construct two histograms with identical
    // boundary arrays but different bucket counts by duplicating
    // boundaries: 5 boundaries (4 buckets, linear) and a wider histogram
    // where the first 4 buckets share the same boundary positions.
    Comparable<?>[] boundaries4 = {0, 10, 20, 30, 40};
    long[] freq4 = {100, 100, 100, 100};
    long[] dc4 = {10, 10, 10, 10};
    // Linear scan (4 buckets <= 8 threshold)
    var linear = new EquiDepthHistogram(
        4, boundaries4, freq4, dc4, 400, null, 0);

    // To exercise binary search with the same boundaries, create a
    // 10-bucket histogram (> 8 threshold) where the first 5 boundaries
    // match and additional boundaries extend the range.
    Comparable<?>[] boundaries10 = {
        0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
    long[] freq10 = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
    long[] dc10 = {10, 10, 10, 10, 10, 10, 10, 10, 10, 10};
    // Binary search (10 buckets > 8 threshold)
    var binary = new EquiDepthHistogram(
        10, boundaries10, freq10, dc10, 1000, null, 0);

    // For keys in the shared range [0, 39], both histograms return the
    // same bucket index. Key 40 is excluded: it is the max boundary in
    // the 4-bucket histogram (maps to last bucket = 3) but an interior
    // boundary in the 10-bucket histogram (maps to bucket 4).
    for (int key = -10; key < 40; key++) {
      int linBucket = linear.findBucket(key);
      int binBucket = binary.findBucket(key);
      Assert.assertEquals(
          "Mismatch for key=" + key
              + ": linear=" + linBucket + ", binary=" + binBucket,
          linBucket, binBucket);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket() — string boundaries
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testFindBucketWithStringBoundaries() {
    // String boundaries use DefaultComparator → String.compareTo()
    var hist = new EquiDepthHistogram(
        3,
        new Comparable<?>[]{"apple", "cherry", "mango", "zebra"},
        new long[]{100, 200, 150},
        new long[]{10, 20, 15},
        450, null, 0);

    Assert.assertEquals(0, hist.findBucket("banana"));  // "apple" <= "banana" < "cherry"
    Assert.assertEquals(1, hist.findBucket("dog"));      // "cherry" <= "dog" < "mango"
    Assert.assertEquals(2, hist.findBucket("peach"));    // "mango" <= "peach" <= "zebra"
    Assert.assertEquals(2, hist.findBucket("zebra"));    // max key → last bucket
    Assert.assertEquals(2, hist.findBucket("zzz"));      // above max → last bucket
    Assert.assertEquals(0, hist.findBucket("aardvark")); // below min → bucket 0
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket() — duplicate boundaries (single-value histogram)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testFindBucketSingleValueHistogram() {
    // All-identical keys: one bucket spanning [42, 42]
    var hist = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{42, 42},
        new long[]{1000},
        new long[]{1},
        1000, 42, 1000);

    Assert.assertEquals(0, hist.findBucket(42));   // exact match
    Assert.assertEquals(0, hist.findBucket(0));    // below
    Assert.assertEquals(0, hist.findBucket(100));  // above
  }

  // ═══════════════════════════════════════════════════════════════
  // Serialization — integer boundaries
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testSerializeDeserializeIntegerHistogram() {
    // Create a histogram with integer boundaries and verify round-trip
    var original = createIntHistogram(4, 10, 250, 25);
    var serializer = objectSerializer(IntegerSerializer.INSTANCE);

    byte[] data = original.serialize(serializer, serializerFactory);
    var restored = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);

    Assert.assertNotNull(restored);
    Assert.assertEquals(original.bucketCount(), restored.bucketCount());
    Assert.assertEquals(original.nonNullCount(), restored.nonNullCount());
    Assert.assertNull(restored.mcvValue());
    Assert.assertEquals(0, restored.mcvFrequency());

    for (int i = 0; i <= original.bucketCount(); i++) {
      Assert.assertEquals(
          "boundary[" + i + "]",
          original.boundaries()[i], restored.boundaries()[i]);
    }
    for (int i = 0; i < original.bucketCount(); i++) {
      Assert.assertEquals(
          "frequency[" + i + "]",
          original.frequencies()[i], restored.frequencies()[i]);
      Assert.assertEquals(
          "distinctCount[" + i + "]",
          original.distinctCounts()[i], restored.distinctCounts()[i]);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Serialization — long boundaries
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testSerializeDeserializeLongHistogram() {
    Comparable<?>[] boundaries = new Comparable<?>[]{
        0L, 1000L, 2000L, 3000L, Long.MAX_VALUE};
    long[] frequencies = {100, 200, 300, 400};
    long[] distinctCounts = {10, 20, 30, 40};

    var original = new EquiDepthHistogram(
        4, boundaries, frequencies, distinctCounts, 1000, null, 0);

    var serializer = objectSerializer(LongSerializer.INSTANCE);

    byte[] data = original.serialize(serializer, serializerFactory);
    var restored = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);

    Assert.assertNotNull(restored);
    Assert.assertEquals(4, restored.bucketCount());
    Assert.assertEquals(1000, restored.nonNullCount());

    for (int i = 0; i <= 4; i++) {
      Assert.assertEquals(boundaries[i], restored.boundaries()[i]);
    }
    for (int i = 0; i < 4; i++) {
      Assert.assertEquals(frequencies[i], restored.frequencies()[i]);
      Assert.assertEquals(distinctCounts[i], restored.distinctCounts()[i]);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Serialization — string boundaries
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testSerializeDeserializeStringHistogram() {
    // Boundaries must be sorted lexicographically (String.compareTo order)
    Comparable<?>[] boundaries = new Comparable<?>[]{
        "alpha", "beta", "delta", "gamma"};
    long[] frequencies = {100, 200, 300};
    long[] distinctCounts = {10, 20, 30};

    var original = new EquiDepthHistogram(
        3, boundaries, frequencies, distinctCounts, 600, null, 0);

    var serializer = objectSerializer(UTF8Serializer.INSTANCE);

    byte[] data = original.serialize(serializer, serializerFactory);
    var restored = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);

    Assert.assertNotNull(restored);
    Assert.assertEquals(3, restored.bucketCount());
    Assert.assertEquals(600, restored.nonNullCount());

    for (int i = 0; i <= 3; i++) {
      Assert.assertEquals(boundaries[i], restored.boundaries()[i]);
    }
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(frequencies[i], restored.frequencies()[i]);
      Assert.assertEquals(distinctCounts[i], restored.distinctCounts()[i]);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Serialization — with MCV
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testSerializeDeserializeWithMcv() {
    // Histogram with an MCV (most common value)
    var original = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 10, 20, 30, 40},
        new long[]{100, 250, 100, 50},
        new long[]{10, 25, 10, 5},
        500,
        20,   // mcvValue
        250); // mcvFrequency

    var serializer = objectSerializer(IntegerSerializer.INSTANCE);

    byte[] data = original.serialize(serializer, serializerFactory);
    var restored = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);

    Assert.assertNotNull(restored);
    Assert.assertEquals(4, restored.bucketCount());
    Assert.assertEquals(500, restored.nonNullCount());
    Assert.assertEquals(20, restored.mcvValue());
    Assert.assertEquals(250, restored.mcvFrequency());
  }

  @Test
  public void testSerializeDeserializeWithStringMcv() {
    // Histogram with string MCV
    var original = new EquiDepthHistogram(
        3,
        new Comparable<?>[]{"a", "m", "t", "z"},
        new long[]{100, 500, 100},
        new long[]{10, 50, 10},
        700,
        "popular",
        500);

    var serializer = objectSerializer(UTF8Serializer.INSTANCE);

    byte[] data = original.serialize(serializer, serializerFactory);
    var restored = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);

    Assert.assertNotNull(restored);
    Assert.assertEquals("popular", restored.mcvValue());
    Assert.assertEquals(500, restored.mcvFrequency());
  }

  // ═══════════════════════════════════════════════════════════════
  // Serialization — with non-zero offset
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testSerializeDeserializeWithOffset() {
    // Verify deserialization works at a non-zero offset
    var original = createIntHistogram(4, 10, 100, 10);
    var serializer = objectSerializer(IntegerSerializer.INSTANCE);

    byte[] serialized = original.serialize(serializer, serializerFactory);

    // Embed in a larger buffer with a prefix
    int offset = 64;
    byte[] buffer = new byte[offset + serialized.length];
    System.arraycopy(serialized, 0, buffer, offset, serialized.length);

    var restored = EquiDepthHistogram.deserialize(
        buffer, offset, serializer, serializerFactory);

    Assert.assertNotNull(restored);
    Assert.assertEquals(original.bucketCount(), restored.bucketCount());
    Assert.assertEquals(original.nonNullCount(), restored.nonNullCount());
  }

  // ═══════════════════════════════════════════════════════════════
  // Deserialization — corruption guards
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testDeserializeZeroBucketCountReturnsNull() {
    // A byte array representing bucketCount = 0 should return null
    byte[] data = new byte[4];
    IntegerSerializer.serializeNative(0, data, 0);

    var serializer = objectSerializer(IntegerSerializer.INSTANCE);

    var result = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);
    Assert.assertNull("bucketCount=0 should return null", result);
  }

  @Test
  public void testDeserializeNegativeBucketCountReturnsNull() {
    byte[] data = new byte[4];
    IntegerSerializer.serializeNative(-1, data, 0);

    var serializer = objectSerializer(IntegerSerializer.INSTANCE);

    var result = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);
    Assert.assertNull("Negative bucketCount should return null", result);
  }

  @Test
  public void testDeserializeAbsurdBucketCountReturnsNull() {
    // bucketCount > 10_000 is treated as corruption
    byte[] data = new byte[4];
    IntegerSerializer.serializeNative(10_001, data, 0);

    var serializer = objectSerializer(IntegerSerializer.INSTANCE);

    var result = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);
    Assert.assertNull("Absurd bucketCount should return null", result);
  }

  // ═══════════════════════════════════════════════════════════════
  // N+1 boundary convention verification
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testBoundaryConventionNPlusOne() {
    // Verify the N+1 boundary convention: boundaries[0] = min,
    // boundaries[bucketCount] = max
    var hist = createIntHistogram(4, 10, 100, 10);

    Assert.assertEquals(5, hist.boundaries().length); // 4 + 1
    Assert.assertEquals(0, hist.boundaries()[0]);     // min
    Assert.assertEquals(40, hist.boundaries()[4]);    // max
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket() correctness — every boundary transition
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testFindBucketExhaustiveBoundaryTransitions() {
    // For a histogram with boundaries [0, 10, 20, 30, 40], verify
    // that keys at transition points map to the correct buckets.
    var hist = createIntHistogram(4, 10, 100, 10);

    // Just below each boundary → previous bucket
    Assert.assertEquals(0, hist.findBucket(9));
    Assert.assertEquals(1, hist.findBucket(19));
    Assert.assertEquals(2, hist.findBucket(29));
    Assert.assertEquals(3, hist.findBucket(39));

    // Exactly at each boundary → that boundary's bucket
    Assert.assertEquals(0, hist.findBucket(0));
    Assert.assertEquals(1, hist.findBucket(10));
    Assert.assertEquals(2, hist.findBucket(20));
    Assert.assertEquals(3, hist.findBucket(30));
    Assert.assertEquals(3, hist.findBucket(40)); // max → last bucket
  }

  // ═══════════════════════════════════════════════════════════════
  // Serialization — large histogram (128 buckets)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testSerializeDeserializeLargeHistogram() {
    // Create a 128-bucket histogram (the target from the ADR)
    int bucketCount = 128;
    var hist = createIntHistogram(bucketCount, 100, 78, 10);

    var serializer = objectSerializer(IntegerSerializer.INSTANCE);

    byte[] data = hist.serialize(serializer, serializerFactory);
    var restored = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);

    Assert.assertNotNull(restored);
    Assert.assertEquals(bucketCount, restored.bucketCount());
    Assert.assertEquals(
        hist.nonNullCount(), restored.nonNullCount());

    // Verify all boundaries round-trip correctly
    for (int i = 0; i <= bucketCount; i++) {
      Assert.assertEquals(
          "boundary[" + i + "]",
          hist.boundaries()[i], restored.boundaries()[i]);
    }

    // Verify findBucket works on deserialized histogram
    Assert.assertEquals(0, restored.findBucket(50));
    Assert.assertEquals(64, restored.findBucket(6450));
    Assert.assertEquals(127, restored.findBucket(99999));
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket() — Long boundaries
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testFindBucketWithLongBoundaries() {
    var hist = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0L, 1000L, 2000L, 3000L, Long.MAX_VALUE},
        new long[]{100, 200, 300, 400},
        new long[]{10, 20, 30, 40},
        1000, null, 0);

    Assert.assertEquals(0, hist.findBucket(500L));
    Assert.assertEquals(1, hist.findBucket(1500L));
    Assert.assertEquals(2, hist.findBucket(2500L));
    Assert.assertEquals(3, hist.findBucket(5000L));
    Assert.assertEquals(3, hist.findBucket(Long.MAX_VALUE));
    Assert.assertEquals(0, hist.findBucket(-1L));
  }

  // ═══════════════════════════════════════════════════════════════
  // Serialization — single-bucket histogram
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testSerializeDeserializeSingleBucketHistogram() {
    var original = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{0, 100},
        new long[]{500},
        new long[]{100},
        500, null, 0);
    var serializer = objectSerializer(IntegerSerializer.INSTANCE);

    byte[] data = original.serialize(serializer, serializerFactory);
    var restored = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);

    Assert.assertNotNull(restored);
    Assert.assertEquals(1, restored.bucketCount());
    Assert.assertEquals(500, restored.nonNullCount());
    Assert.assertEquals(0, restored.boundaries()[0]);
    Assert.assertEquals(100, restored.boundaries()[1]);
  }

  // ═══════════════════════════════════════════════════════════════
  // Deserialization — truncated data guards
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void testDeserializeTruncatedFrequenciesReturnsNull() {
    // Serialize a valid histogram, then truncate within the frequency
    // region. Wire format after boundaries: frequencies need
    // bucketCount × 8 bytes. Cutting mid-way triggers the bounds check.
    var original = createIntHistogram(4, 10, 100, 10);
    var serializer = objectSerializer(IntegerSerializer.INSTANCE);
    byte[] data = original.serialize(serializer, serializerFactory);

    // Header: bucketCount(4) + nonNullCount(8) + mcvFrequency(8) +
    //   mcvKeyLen(4) = 24
    // Boundaries: 5 × (4-byte len prefix + 4-byte int) = 40
    // Frequencies start at offset 64, need 4 × 8 = 32 bytes.
    // Truncate after only 2 frequencies (16 bytes).
    int truncateAt = 24 + 40 + 16;
    byte[] truncated = new byte[truncateAt];
    System.arraycopy(data, 0, truncated, 0, truncateAt);

    var result = EquiDepthHistogram.deserialize(
        truncated, 0, serializer, serializerFactory);
    Assert.assertNull("Truncated frequencies should return null", result);
  }

  @Test
  public void testDeserializeTruncatedDistinctCountsReturnsNull() {
    // Serialize a valid histogram, then truncate within the
    // distinctCounts region.
    var original = createIntHistogram(4, 10, 100, 10);
    var serializer = objectSerializer(IntegerSerializer.INSTANCE);
    byte[] data = original.serialize(serializer, serializerFactory);

    // Header(24) + boundaries(40) + frequencies(32) = 96.
    // DistinctCounts need 4 × 8 = 32 bytes. Truncate after only 2.
    int truncateAt = 24 + 40 + 32 + 16;
    byte[] truncated = new byte[truncateAt];
    System.arraycopy(data, 0, truncated, 0, truncateAt);

    var result = EquiDepthHistogram.deserialize(
        truncated, 0, serializer, serializerFactory);
    Assert.assertNull(
        "Truncated distinctCounts should return null", result);
  }

  @Test
  public void testDeserializeTruncatedBoundaryKeyReturnsNull() {
    // Serialize a valid histogram, then truncate within the boundary
    // section. After the header (24 bytes), the first boundary needs
    // a 4-byte length prefix + 4-byte int. Truncate mid-length-prefix.
    var original = createIntHistogram(4, 10, 100, 10);
    var serializer = objectSerializer(IntegerSerializer.INSTANCE);
    byte[] data = original.serialize(serializer, serializerFactory);

    // Cut at header(24) + 2 bytes — within the first boundary's
    // length prefix (needs 4 bytes).
    int truncateAt = 24 + 2;
    byte[] truncated = new byte[truncateAt];
    System.arraycopy(data, 0, truncated, 0, truncateAt);

    var result = EquiDepthHistogram.deserialize(
        truncated, 0, serializer, serializerFactory);
    Assert.assertNull(
        "Truncated boundary should return null", result);
  }

  @Test
  public void testDeserializeNegativeMcvKeyLenReturnsNull() {
    // Craft a byte array with valid bucketCount but negative mcvKeyLen.
    // Format: bucketCount(4) + nonNullCount(8) + mcvFrequency(8) +
    //   mcvKeyLen(4) — set mcvKeyLen to -1
    byte[] data = new byte[24];
    int pos = 0;
    IntegerSerializer.serializeNative(4, data, pos); // bucketCount
    pos += 4;
    LongSerializer.serializeNative(1000L, data, pos); // nonNullCount
    pos += 8;
    LongSerializer.serializeNative(100L, data, pos); // mcvFrequency
    pos += 8;
    IntegerSerializer.serializeNative(-1, data, pos); // negative mcvKeyLen

    var serializer = objectSerializer(IntegerSerializer.INSTANCE);
    var result = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);
    Assert.assertNull("Negative mcvKeyLen should return null", result);
  }

  @Test
  public void testDeserializeOverflowMcvKeyLenReturnsNull() {
    // Craft a byte array where mcvKeyLen is so large that
    // pos + mcvKeyLen would overflow int (wrapping to negative).
    // The overflow-safe guard (mcvKeyLen > data.length - pos) must
    // catch this without relying on pos + mcvKeyLen being positive.
    byte[] data = new byte[28];
    int pos = 0;
    IntegerSerializer.serializeNative(1, data, pos); // bucketCount = 1
    pos += 4;
    LongSerializer.serializeNative(100L, data, pos); // nonNullCount
    pos += 8;
    LongSerializer.serializeNative(50L, data, pos); // mcvFrequency
    pos += 8;
    // mcvKeyLen = Integer.MAX_VALUE - 5: pos(24) + mcvKeyLen overflows
    // to a negative int, which would pass a naive "pos + len > data.length"
    // check because negative < data.length.
    IntegerSerializer.serializeNative(Integer.MAX_VALUE - 5, data, pos);

    var serializer = objectSerializer(IntegerSerializer.INSTANCE);
    var result = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);
    Assert.assertNull(
        "Overflowing mcvKeyLen should return null", result);
  }

  @Test
  public void testDeserializeOverflowBoundaryKeyLenReturnsNull() {
    // Craft a byte array where a boundary key length is so large that
    // pos + keyLen would overflow int.  The MCV section is valid (len=0)
    // so deserialization reaches the boundary loop.
    byte[] data = new byte[32];
    int pos = 0;
    IntegerSerializer.serializeNative(1, data, pos); // bucketCount = 1
    pos += 4;
    LongSerializer.serializeNative(100L, data, pos); // nonNullCount
    pos += 8;
    LongSerializer.serializeNative(50L, data, pos); // mcvFrequency
    pos += 8;
    IntegerSerializer.serializeNative(0, data, pos); // mcvKeyLen = 0
    pos += 4;
    // First boundary key length: Integer.MAX_VALUE - 5
    IntegerSerializer.serializeNative(Integer.MAX_VALUE - 5, data, pos);

    var serializer = objectSerializer(IntegerSerializer.INSTANCE);
    var result = EquiDepthHistogram.deserialize(
        data, 0, serializer, serializerFactory);
    Assert.assertNull(
        "Overflowing boundary keyLen should return null", result);
  }
}
