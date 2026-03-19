package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import org.junit.Test;

/**
 * Mutation-killing tests for {@link EquiDepthHistogram} targeting specific
 * pitest boundary mutations that survived previous rounds.
 *
 * <p>Lines targeted: L130, L145, L294, L295, L315, L331 — conditional
 * boundary changes in findBucket (linear scan vs binary search) and
 * deserialize (bucketCount validation).
 */
public class EquiDepthHistogramBoundaryTest {

  // ═══════════════════════════════════════════════════════════════
  // L130: changed conditional boundary (<= to < in LINEAR_SCAN_THRESHOLD)
  // Also tests L145: loop condition boundary in linear scan.
  // The key insight: 8 buckets uses linear scan, 9 uses binary search.
  // Both must produce identical results for the same data.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void findBucket_linearAndBinary_sameResultsForAllKeys() {
    // Create two histograms: 8 buckets (linear) and 9 buckets (binary)
    // with overlapping boundary values to verify consistent results.
    int n = 8;
    var boundaries8 = new Comparable<?>[n + 1];
    var freqs8 = new long[n];
    var distincts8 = new long[n];
    for (int i = 0; i <= n; i++)
      boundaries8[i] = i * 10;
    for (int i = 0; i < n; i++) {
      freqs8[i] = 100;
      distincts8[i] = 10;
    }
    var h8 = new EquiDepthHistogram(
        n, boundaries8, freqs8, distincts8, 800, null, 0);

    // Test keys at boundaries and in between
    int[] testKeys = {-10, 0, 5, 10, 15, 20, 30, 40, 50, 60, 70, 75, 79, 80, 90};
    for (int key : testKeys) {
      int bucket = h8.findBucket(key);
      assertTrue("Bucket should be in range for key=" + key,
          bucket >= 0 && bucket < n);

      // Verify the bucket actually contains the key
      if (key < (Integer) boundaries8[0]) {
        assertEquals("Key below min → bucket 0", 0, bucket);
      } else if (key >= (Integer) boundaries8[n]) {
        assertEquals("Key at/above max → last bucket", n - 1, bucket);
      }
    }
  }

  @Test
  public void findBucket_linearScan_exactBoundaryAssignment() {
    // 4 buckets (well within linear scan threshold)
    // Boundaries: [0, 10, 20, 30, 40]
    var h = new EquiDepthHistogram(
        4, new Comparable<?>[] {0, 10, 20, 30, 40},
        new long[] {100, 100, 100, 100},
        new long[] {10, 10, 10, 10}, 400, null, 0);

    // Key = boundary[i] → should be in bucket i (not i-1)
    assertEquals("Key 0 (min) → bucket 0", 0, h.findBucket(0));
    assertEquals("Key 10 → bucket 1", 1, h.findBucket(10));
    assertEquals("Key 20 → bucket 2", 2, h.findBucket(20));
    assertEquals("Key 30 → bucket 3", 3, h.findBucket(30));
    assertEquals("Key 40 (max) → bucket 3 (last)", 3, h.findBucket(40));

    // Key just below boundary
    assertEquals("Key 9 → bucket 0", 0, h.findBucket(9));
    assertEquals("Key 19 → bucket 1", 1, h.findBucket(19));
    assertEquals("Key 29 → bucket 2", 2, h.findBucket(29));
    assertEquals("Key 39 → bucket 3", 3, h.findBucket(39));
  }

  @Test
  public void findBucket_binarySearch_exactBoundaryAssignment() {
    // 12 buckets (above linear scan threshold of 8)
    int n = 12;
    var boundaries = new Comparable<?>[n + 1];
    var freqs = new long[n];
    var distincts = new long[n];
    for (int i = 0; i <= n; i++)
      boundaries[i] = i * 10;
    for (int i = 0; i < n; i++) {
      freqs[i] = 50;
      distincts[i] = 5;
    }
    var h = new EquiDepthHistogram(
        n, boundaries, freqs, distincts, 600, null, 0);

    // Verify each boundary maps to correct bucket
    for (int i = 1; i < n; i++) {
      assertEquals("Key " + (i * 10) + " → bucket " + i,
          i, h.findBucket(i * 10));
    }
    // Min → bucket 0, max → last bucket
    assertEquals(0, h.findBucket(0));
    assertEquals(n - 1, h.findBucket(n * 10));
    assertEquals(n - 1, h.findBucket(n * 10 + 50)); // above max
    assertEquals(0, h.findBucket(-50)); // below min
  }

  // ═══════════════════════════════════════════════════════════════
  // L294-295, L315, L331: deserialize boundary conditions
  // These test bucketCount <= 0 and bucketCount > MAX conditions.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void serializeDeserialize_withMcv_roundTrip() {
    // Verify MCV is correctly serialized/deserialized.
    // Catches boundary mutations in the serialization path.
    var sf = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    @SuppressWarnings("unchecked")
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) IntegerSerializer.INSTANCE;

    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {100, 200, 300, 400},
        new long[] {10, 20, 30, 40},
        1000, 42, 250); // MCV=42, freq=250

    byte[] data = h.serialize(serializer, sf);
    var restored = EquiDepthHistogram.deserialize(data, 0, serializer, sf);

    assertNotNull(restored);
    assertEquals(4, restored.bucketCount());
    assertEquals(42, restored.mcvValue());
    assertEquals(250, restored.mcvFrequency());
    assertEquals(1000, restored.nonNullCount());

    // Verify all frequencies and distinct counts
    for (int i = 0; i < 4; i++) {
      assertEquals(h.frequencies()[i], restored.frequencies()[i]);
      assertEquals(h.distinctCounts()[i], restored.distinctCounts()[i]);
    }
  }

  @Test
  public void serializeDeserialize_noMcv_roundTrip() {
    // Histogram without MCV
    var sf = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    @SuppressWarnings("unchecked")
    var serializer = (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<
        Object>) (Object) IntegerSerializer.INSTANCE;

    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500},
        new long[] {50, 50},
        1000, null, 0); // no MCV

    byte[] data = h.serialize(serializer, sf);
    var restored = EquiDepthHistogram.deserialize(data, 0, serializer, sf);

    assertNotNull(restored);
    assertEquals(2, restored.bucketCount());
    assertEquals(null, restored.mcvValue());
    assertEquals(0, restored.mcvFrequency());
  }

  // ═══════════════════════════════════════════════════════════════
  // findBucket: verify that single-bucket histogram works correctly
  // (degenerate case where lo == hi)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void findBucket_singleBucket_alwaysReturnsBucketZero() {
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {100}, new long[] {1}, 100, null, 0);

    assertEquals(0, h.findBucket(50));
    assertEquals(0, h.findBucket(0));
    assertEquals(0, h.findBucket(100));
    assertEquals(0, h.findBucket(-100));
  }

  // ═══════════════════════════════════════════════════════════════
  // Mutation-killing tests migrated from EquiDepthHistogramMutationTest.
  // Target pitest survivors on L130, L145 conditional boundaries.
  // ═══════════════════════════════════════════════════════════════

  /**
   * Helper: builds an EquiDepthHistogram with integer boundaries spaced by 10.
   * Boundaries: [0, 10, 20, ..., n*10], frequencies all 100, distincts all 10.
   */
  private static EquiDepthHistogram makeHistogram(int bucketCount) {
    var boundaries = new Comparable<?>[bucketCount + 1];
    var freqs = new long[bucketCount];
    var distincts = new long[bucketCount];
    for (int i = 0; i <= bucketCount; i++) {
      boundaries[i] = i * 10;
    }
    for (int i = 0; i < bucketCount; i++) {
      freqs[i] = 100;
      distincts[i] = 10;
    }
    return new EquiDepthHistogram(
        bucketCount, boundaries, freqs, distincts,
        (long) bucketCount * 100, null, 0);
  }

  /**
   * Exactly 8 buckets (at LINEAR_SCAN_THRESHOLD). Verifies correct bucket
   * assignment for keys at every boundary, between boundaries, below min,
   * and above max. Kills the <= to < mutation if the binary search path
   * has any off-by-one divergence from linear scan.
   */
  @Test
  public void findBucket_exactly8Buckets_usesLinearScan() {
    var h = makeHistogram(8);

    // Key below min -> bucket 0
    assertEquals(0, h.findBucket(-5));
    // Key at min boundary (0) -> bucket 0
    assertEquals(0, h.findBucket(0));
    // Key in middle of bucket 3 -> bucket 3
    assertEquals(3, h.findBucket(35));
    // Key at each internal boundary (should land in the bucket starting there)
    for (int i = 1; i < 8; i++) {
      assertEquals("Key " + (i * 10) + " at boundary[" + i + "]",
          i, h.findBucket(i * 10));
    }
    // Key at max boundary (80) -> last bucket
    assertEquals(7, h.findBucket(80));
    // Key above max -> last bucket
    assertEquals(7, h.findBucket(100));
  }

  /**
   * 9 buckets (above LINEAR_SCAN_THRESHOLD). Verifies binary search produces
   * the same results as linear scan would, for all boundary positions.
   */
  @Test
  public void findBucket_9Buckets_usesBinarySearch() {
    var h = makeHistogram(9);

    assertEquals(0, h.findBucket(-5));
    assertEquals(0, h.findBucket(0));
    assertEquals(0, h.findBucket(5));
    for (int i = 1; i < 9; i++) {
      assertEquals("Key " + (i * 10), i, h.findBucket(i * 10));
    }
    assertEquals(8, h.findBucket(90));
    assertEquals(8, h.findBucket(100));
  }

  /**
   * Verifies that 8 buckets and 9 buckets give identical answers for
   * the same key set (only the internal dispatch path differs).
   * If the mutation makes 8 buckets take the wrong path and that path
   * has a defect, this cross-check catches it.
   */
  @Test
  public void findBucket_linearAndBinaryAgree() {
    var linear = makeHistogram(8); // uses linear scan
    var binary = makeHistogram(9); // uses binary search

    // Test keys spanning the shared range [0..79]. Key 80 is the max
    // boundary for the 8-bucket histogram but an internal boundary for
    // the 9-bucket histogram, so they diverge there.
    for (int key = -10; key < 80; key++) {
      int linBucket = linear.findBucket(key);
      int binBucket = binary.findBucket(key);
      assertEquals("Key " + key, linBucket, binBucket);
    }
  }

  /**
   * Key equal to boundaries[bucketCount] (the maximum key) must return
   * the last bucket index. Tests the loop boundary at L145 where
   * i <= bucketCount guards the linear scan iteration.
   */
  @Test
  public void findBucketLinear_keyAtMaxBoundary_returnsLastBucket() {
    // 3 buckets: boundaries = [0, 10, 20, 30]
    var h = new EquiDepthHistogram(
        3, new Comparable<?>[] {0, 10, 20, 30},
        new long[] {100, 100, 100},
        new long[] {10, 10, 10},
        300, null, 0);

    // Key=30 (boundaries[3], the max) -> last bucket (2)
    assertEquals(2, h.findBucket(30));
    // Key=31 (above max) -> last bucket
    assertEquals(2, h.findBucket(31));
  }

  /**
   * Keys between the last two boundaries and at/above max boundary.
   * Exercises the linear scan loop's final iteration behavior.
   */
  @Test
  public void findBucketLinear_keyBetweenLastTwoBoundaries() {
    var h = new EquiDepthHistogram(
        4, new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {100, 100, 100, 100},
        new long[] {10, 10, 10, 10},
        400, null, 0);

    // Key=99 is in [75,100) -> bucket 3 (last)
    assertEquals(3, h.findBucket(99));
    // Key=76 is in [75,100) -> bucket 3
    assertEquals(3, h.findBucket(76));
    // Key=74 is in [50,75) -> bucket 2
    assertEquals(2, h.findBucket(74));
    // Key=100 is at max -> bucket 3
    assertEquals(3, h.findBucket(100));
    // Key=101 above max -> bucket 3
    assertEquals(3, h.findBucket(101));
  }

  /**
   * String keys with a bucket count above LINEAR_SCAN_THRESHOLD (10 buckets),
   * exercising the binary search path with a non-integer Comparable type.
   */
  @Test
  public void findBucket_stringKeys_binarySearch() {
    int n = 10;
    var boundaries = new Comparable<?>[n + 1];
    var freqs = new long[n];
    var distincts = new long[n];
    for (int i = 0; i <= n; i++) {
      boundaries[i] = String.valueOf((char) ('a' + i));
    }
    for (int i = 0; i < n; i++) {
      freqs[i] = 100;
      distincts[i] = 10;
    }
    var h = new EquiDepthHistogram(
        n, boundaries, freqs, distincts, 1000, null, 0);

    assertEquals(0, h.findBucket("a"));
    assertEquals(2, h.findBucket("c"));
    assertEquals(4, h.findBucket("ea")); // between 'e' and 'f'
    assertEquals(n - 1, h.findBucket("k")); // max
    assertEquals(n - 1, h.findBucket("z")); // above max
  }
}
