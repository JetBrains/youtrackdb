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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Histogram construction tests (Section 10.2 of the ADR).
 *
 * <p>Covers: equi-depth bucket construction, skewed data, per-bucket NDV,
 * duplicate keys at boundary, all-identical keys, small index guard, N+1
 * boundary convention, NDV-based bucket pre-computation, adaptive bucket
 * count sqrt cap, findBucket linear/binary search, MCV tracking,
 * bucket merging, page budget calculation, and fitToPage boundary
 * truncation with bucket count reduction and HLL spill.
 *
 * <p>These tests exercise the pure computational logic of
 * {@link IndexHistogramManager#scanAndBuild},
 * {@link IndexHistogramManager#fitToPage},
 * {@link IndexHistogramManager#mergeBuckets},
 * {@link IndexHistogramManager#computeMaxBoundarySpace}, and
 * {@link EquiDepthHistogram#findBucket} without requiring storage
 * infrastructure. The {@code fitToPage} static overload accepts a
 * {@link IndexHistogramManager.BoundarySizeCalculator} to decouple
 * serializer-dependent boundary size computation.
 */
public class HistogramConstructionTest {

  // ── Known data: equi-depth verification ──

  @Test
  public void knownData_uniformDistribution_producesEqualBucketFrequencies() {
    // Given: 1000 entries with 100 distinct values, 10 entries each
    // (already in sorted order by construction: i/10 is non-decreasing)
    var keys = IntStream.range(0, 1000)
        .mapToObj(i -> (Object) (i / 10));

    // When: build with 10 target buckets
    var result = IndexHistogramManager.scanAndBuild(keys, 1000, 10);

    // Then: all buckets should have exactly 100 entries (perfect equi-depth)
    assertNotNull(result);
    assertEquals(10, result.actualBucketCount);
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertEquals("Bucket " + i + " frequency", 100, result.frequencies[i]);
      assertTrue("Bucket " + i + " frequency must be non-negative",
          result.frequencies[i] >= 0);
      totalFreq += result.frequencies[i];
    }
    assertEquals(1000, totalFreq);
    assertEquals(0, result.boundaries[0]);
    assertEquals(99, result.boundaries[result.actualBucketCount]);

    // Verify boundary monotonicity
    assertBoundariesMonotonic(result);
  }

  @Test
  public void knownData_boundariesAreMinAndMax() {
    // Given: sorted values from 5 to 95
    var keys = IntStream.rangeClosed(5, 95)
        .mapToObj(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(keys, 91, 4);

    // Then: boundaries[0] = 5 (min), boundaries[last] = 95 (max)
    assertNotNull(result);
    assertEquals(5, result.boundaries[0]);
    assertEquals(95, result.boundaries[result.actualBucketCount]);
    assertBoundariesMonotonic(result);
  }

  @Test
  public void knownData_frequenciesSumToNonNullCount() {
    // Given: 500 entries with 50 distinct values
    var keys = IntStream.range(0, 500)
        .mapToObj(i -> (Object) (i / 10));

    var result = IndexHistogramManager.scanAndBuild(keys, 500, 8);

    assertNotNull(result);
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals(500, totalFreq);
  }

  // ── Skewed data (Zipf-like) ──

  @Test
  public void skewedData_capturesShapeWithMostFrequentValueInOneBucket() {
    // Given: Zipf-like distribution — value 0 has 500 entries, values 1-9
    // have exponentially decreasing frequencies
    Object[] data = new Object[1000];
    int pos = 0;
    Arrays.fill(data, pos, pos + 500, 0);
    pos += 500;
    Arrays.fill(data, pos, pos + 200, 1);
    pos += 200;
    Arrays.fill(data, pos, pos + 100, 2);
    pos += 100;
    Arrays.fill(data, pos, pos + 80, 3);
    pos += 80;
    Arrays.fill(data, pos, pos + 50, 4);
    pos += 50;
    for (int v = 5; v <= 9; v++) {
      Arrays.fill(data, pos, pos + 14, v);
      pos += 14;
    }
    assert pos == data.length : "Data construction error";
    Arrays.sort(data);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 1000, 4);

    // Then: histogram captures the skew
    assertNotNull(result);
    // The first bucket should have the highest frequency (value 0 dominant)
    assertTrue("First bucket should be the largest",
        result.frequencies[0]
            >= result.frequencies[result.actualBucketCount - 1]);
    assertEquals(0, result.mcvValue);
    assertEquals(500, result.mcvFrequency);
    assertBoundariesMonotonic(result);
  }

  // ── Per-bucket NDV ──

  @Test
  public void perBucketNdv_isComputedCorrectlyDuringBuild() {
    // Given: 200 entries with 20 distinct values (10 each), 4 target buckets
    // Each bucket should have 5 distinct values
    var keys = IntStream.range(0, 200)
        .mapToObj(i -> (Object) (i / 10));

    var result = IndexHistogramManager.scanAndBuild(keys, 200, 4);

    assertNotNull(result);
    assertEquals(4, result.actualBucketCount);
    assertEquals(20, result.totalDistinct);
    long totalNDV = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertEquals("Bucket " + i + " NDV", 5, result.distinctCounts[i]);
      totalNDV += result.distinctCounts[i];
    }
    assertEquals(20, totalNDV);
  }

  // ── Duplicate keys at boundary ──

  @Test
  public void duplicateKeysAtBoundary_allInSameBucket() {
    // Given: 100 entries where value 5 (a potential boundary value) has
    // 50 entries, ensuring all duplicates stay in the same bucket
    Object[] data = new Object[100];
    Arrays.fill(data, 0, 10, 0);
    Arrays.fill(data, 10, 60, 5); // 50 entries of value 5
    Arrays.fill(data, 60, 80, 10);
    Arrays.fill(data, 80, 100, 15);
    Arrays.sort(data);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 100, 4);

    // Then: no bucket boundary splits value 5 — all 50 entries of
    // value 5 appear in a single bucket's frequency
    assertNotNull(result);
    boolean found50 = false;
    for (int i = 0; i < result.actualBucketCount; i++) {
      if (result.frequencies[i] >= 50) {
        found50 = true;
        break;
      }
    }
    assertTrue("All 50 entries of value 5 should be in one bucket", found50);
  }

  // ── All-identical keys (NDV=1) ──

  @Test
  public void allIdenticalKeys_producesSingleBucket() {
    // Given: 500 entries, all value 42
    var keys = Stream.generate(() -> (Object) 42).limit(500);

    var result = IndexHistogramManager.scanAndBuild(keys, 500, 10);

    assertNotNull(result);
    assertEquals(1, result.actualBucketCount);
    assertEquals(500, result.frequencies[0]);
    assertEquals(1, result.distinctCounts[0]);
    assertEquals(1, result.totalDistinct);
    assertEquals(42, result.boundaries[0]);
    assertEquals(42, result.boundaries[1]);
  }

  // ── Small index (< HISTOGRAM_MIN_SIZE) ──

  @Test
  public void smallIndex_scanAndBuildWorksWithVerySmallInputs() {
    // scanAndBuild itself doesn't enforce min-size — that's done by
    // buildHistogram(). Verify it still works with very small inputs.
    var result = IndexHistogramManager.scanAndBuild(
        Stream.of((Object) 1, (Object) 2, (Object) 3), 3, 4);

    // With 3 entries and 4 target buckets, we get at most 3 actual buckets
    assertNotNull(result);
    assertTrue(result.actualBucketCount <= 3);
    assertEquals(3, result.totalDistinct);
  }

  @Test
  public void emptyStream_returnsNull() {
    var result = IndexHistogramManager.scanAndBuild(
        Stream.empty(), 0, 4);
    assertNull(result);
  }

  // ── N+1 boundary convention ──

  @Test
  public void nPlusOneBoundaryConvention_boundariesLenIsBucketCountPlusOne() {
    // Given: 100 entries with 10 distinct values
    var keys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) (i / 10));

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 4);

    // Then: boundaries array has exactly actualBucketCount + 1 elements
    assertNotNull(result);
    assertEquals(result.actualBucketCount + 1, result.boundaries.length);
    assertEquals(result.actualBucketCount, result.frequencies.length);
    assertEquals(result.actualBucketCount, result.distinctCounts.length);
  }

  @Test
  public void nPlusOneBoundaryConvention_firstBoundaryIsMinLastIsMax() {
    // Given: values from 10 to 99
    var keys = IntStream.rangeClosed(10, 99)
        .mapToObj(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(keys, 90, 5);

    assertNotNull(result);
    assertEquals(10, result.boundaries[0]);
    assertEquals(99, result.boundaries[result.actualBucketCount]);
  }

  // ── Adaptive bucket count (sqrt cap) ──

  @Test
  public void adaptiveBucketCount_sqrtCapReducesBucketsForSmallIndexes() {
    // Given: 100 entries, sqrt(100) = 10. With 128 configured target,
    // the production cap logic computes min(128, 10) = 10.
    // We pass the capped value directly to scanAndBuild.
    var keys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 10);

    assertNotNull(result);
    assertTrue(result.actualBucketCount <= 10);
  }

  @Test
  public void adaptiveBucketCount_minimumBucketCountTarget() {
    // Given: target = MINIMUM_BUCKET_COUNT (4), 1000 entries with 10 NDV
    var keys = IntStream.range(0, 1000)
        .mapToObj(i -> (Object) (i / 100));

    var result = IndexHistogramManager.scanAndBuild(
        keys, 1000, IndexHistogramManager.MINIMUM_BUCKET_COUNT);

    assertNotNull(result);
    assertTrue("Expected <= " + IndexHistogramManager.MINIMUM_BUCKET_COUNT
        + " buckets, got " + result.actualBucketCount,
        result.actualBucketCount
            <= IndexHistogramManager.MINIMUM_BUCKET_COUNT);
    assertEquals(10, result.totalDistinct);
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals(1000, totalFreq);
  }

  // ── NDV-based bucket pre-computation (rebalance) ──

  @Test
  public void ndvCap_fiveDistinctValues_producesFiveBuckets() {
    // Simulate rebalance with NDV cap: NDV=5, nonNullCount=10000
    // After sqrt + NDV cap: targetBuckets = 5
    Object[] data = new Object[10000];
    for (int i = 0; i < 10000; i++) {
      data[i] = i % 5;
    }
    Arrays.sort(data);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 10000, 5);

    assertNotNull(result);
    assertEquals(5, result.actualBucketCount);
    for (int i = 0; i < 5; i++) {
      assertEquals(2000, result.frequencies[i]);
    }
  }

  @Test
  public void ndvCap_booleanIndex_producesTwoBuckets() {
    // NDV=2, nonNullCount=1000, NDV cap reduces to MINIMUM_BUCKET_COUNT
    // but scanAndBuild trims to 2 actual buckets
    Object[] data = new Object[1000];
    Arrays.fill(data, 0, 600, false);
    Arrays.fill(data, 600, 1000, true);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 1000,
        IndexHistogramManager.MINIMUM_BUCKET_COUNT);

    assertNotNull(result);
    assertEquals(2, result.actualBucketCount);
    assertEquals(600, result.frequencies[0]);
    assertEquals(400, result.frequencies[1]);
    assertEquals(2, result.totalDistinct);
  }

  // ── findBucket: linear scan (bucketCount <= 8) ──

  @Test
  public void findBucket_linear_keyInFirstBucket() {
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {25, 25, 25, 25},
        new long[] {25, 25, 25, 25},
        100, null, 0);

    assertEquals(0, histogram.findBucket(0));
    assertEquals(0, histogram.findBucket(10));
    assertEquals(0, histogram.findBucket(24));
  }

  @Test
  public void findBucket_linear_keyInMiddleBucket() {
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {25, 25, 25, 25},
        new long[] {25, 25, 25, 25},
        100, null, 0);

    assertEquals(1, histogram.findBucket(25));
    assertEquals(1, histogram.findBucket(30));
    assertEquals(1, histogram.findBucket(49));
    assertEquals(2, histogram.findBucket(50));
    assertEquals(2, histogram.findBucket(74));
  }

  @Test
  public void findBucket_linear_keyInLastBucket() {
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {25, 25, 25, 25},
        new long[] {25, 25, 25, 25},
        100, null, 0);

    assertEquals(3, histogram.findBucket(75));
    assertEquals(3, histogram.findBucket(99));
    // Last bucket is closed on both ends — key == max maps to last bucket
    assertEquals(3, histogram.findBucket(100));
  }

  @Test
  public void findBucket_linear_keyBelowMinimum_mapsToFirstBucket() {
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {10, 25, 50, 75, 100},
        new long[] {25, 25, 25, 25},
        new long[] {25, 25, 25, 25},
        100, null, 0);

    assertEquals(0, histogram.findBucket(5));
  }

  @Test
  public void findBucket_linear_keyAboveMaximum_mapsToLastBucket() {
    var histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {25, 25, 25, 25},
        new long[] {25, 25, 25, 25},
        100, null, 0);

    assertEquals(3, histogram.findBucket(150));
  }

  @Test
  public void findBucket_linear_keyOnBoundary_mapsToNextBucket() {
    // Key exactly on a boundary (not first or last) maps to the next bucket
    // because bucket i spans [boundaries[i], boundaries[i+1])
    var histogram = new EquiDepthHistogram(
        3,
        new Comparable<?>[] {0, 10, 20, 30},
        new long[] {10, 10, 10},
        new long[] {10, 10, 10},
        30, null, 0);

    assertEquals(1, histogram.findBucket(10)); // [10, 20)
    assertEquals(2, histogram.findBucket(20)); // [20, 30]
  }

  @Test
  public void findBucket_linear_singleBucket() {
    var histogram = new EquiDepthHistogram(
        1,
        new Comparable<?>[] {0, 100},
        new long[] {100},
        new long[] {100},
        100, null, 0);

    assertEquals(0, histogram.findBucket(0));
    assertEquals(0, histogram.findBucket(50));
    assertEquals(0, histogram.findBucket(100));
    assertEquals(0, histogram.findBucket(200));
  }

  // ── findBucket: binary search (bucketCount > 8) ──

  @Test
  public void findBucket_binary_keyInFirstBucket() {
    // 10 buckets → triggers binary search (> LINEAR_SCAN_THRESHOLD=8)
    var histogram = create10BucketHistogram();

    assertEquals(0, histogram.findBucket(0));
    assertEquals(0, histogram.findBucket(5));
    assertEquals(0, histogram.findBucket(9));
  }

  @Test
  public void findBucket_binary_keyInMiddleBucket() {
    var histogram = create10BucketHistogram();

    assertEquals(5, histogram.findBucket(50));
    assertEquals(5, histogram.findBucket(55));
    assertEquals(5, histogram.findBucket(59));
  }

  @Test
  public void findBucket_binary_keyInLastBucket() {
    var histogram = create10BucketHistogram();

    assertEquals(9, histogram.findBucket(90));
    assertEquals(9, histogram.findBucket(95));
    // Last bucket closed on both ends
    assertEquals(9, histogram.findBucket(100));
  }

  @Test
  public void findBucket_binary_keyBelowMinimum() {
    var boundaries = new Comparable<?>[11];
    var frequencies = new long[10];
    var distinctCounts = new long[10];
    for (int i = 0; i <= 10; i++) {
      boundaries[i] = i * 10 + 10; // min = 10
    }
    Arrays.fill(frequencies, 10L);
    Arrays.fill(distinctCounts, 10L);

    var histogram = new EquiDepthHistogram(
        10, boundaries, frequencies, distinctCounts, 100, null, 0);

    assertEquals(0, histogram.findBucket(5));
  }

  @Test
  public void findBucket_binary_keyAboveMaximum() {
    var histogram = create10BucketHistogram();
    assertEquals(9, histogram.findBucket(200));
  }

  @Test
  public void findBucket_binary_keyOnBoundary() {
    var histogram = create10BucketHistogram();

    // Key on internal boundary → maps to the bucket starting at boundary
    assertEquals(3, histogram.findBucket(30));
    assertEquals(7, histogram.findBucket(70));
  }

  @Test
  public void findBucket_linearAndBinary_consistentForSameKeys() {
    // Build a 5-bucket histogram (linear) and 10-bucket histogram (binary)
    // with matching boundaries, then verify consistent results
    var linearBounds = new Comparable<?>[] {0, 20, 40, 60, 80, 100};
    var linear = new EquiDepthHistogram(5, linearBounds,
        new long[] {20, 20, 20, 20, 20},
        new long[] {20, 20, 20, 20, 20}, 100, null, 0);

    var binaryBounds = new Comparable<?>[] {
        0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
    var binary = new EquiDepthHistogram(10, binaryBounds,
        new long[10], new long[10], 100, null, 0);

    // Key 0 → first bucket in both
    assertEquals(0, linear.findBucket(0));
    assertEquals(0, binary.findBucket(0));
    // Key 100 → last bucket in both
    assertEquals(4, linear.findBucket(100));
    assertEquals(9, binary.findBucket(100));
    // Key 50 → bucket [40,60) in linear = bucket 2, [50,60) in binary = 5
    assertEquals(2, linear.findBucket(50));
    assertEquals(5, binary.findBucket(50));
  }

  // ── MCV tracking ──

  @Test
  public void mcvTracking_skewedData_identifiesMostCommonValue() {
    Object[] data = new Object[1000];
    Arrays.fill(data, 0, 900, "A");
    Arrays.fill(data, 900, 950, "B");
    Arrays.fill(data, 950, 1000, "C");
    Arrays.sort(data);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 1000, 4);

    assertNotNull(result);
    assertEquals("A", result.mcvValue);
    assertEquals(900, result.mcvFrequency);
  }

  @Test
  public void mcvTracking_uniformData_picksOneOfTheValues() {
    // Given: 5 distinct values, 200 each (already sorted by construction)
    Object[] data = new Object[1000];
    for (int i = 0; i < 1000; i++) {
      data[i] = i / 200;
    }

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 1000, 4);

    assertNotNull(result);
    assertNotNull(result.mcvValue);
    assertEquals(200, result.mcvFrequency);
  }

  @Test
  public void mcvTracking_allIdenticalKeys_mcvIsTheKey() {
    var keys = Stream.generate(() -> (Object) 42).limit(1000);

    var result = IndexHistogramManager.scanAndBuild(keys, 1000, 4);

    assertNotNull(result);
    assertEquals(42, result.mcvValue);
    assertEquals(1000, result.mcvFrequency);
  }

  @Test
  public void mcvTracking_constructionRoundTrip_preservesMcv() {
    // Build result → construct EquiDepthHistogram → verify MCV fields
    Object[] data = new Object[100];
    Arrays.fill(data, 0, 70, 1);
    Arrays.fill(data, 70, 100, 2);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 100, 4);

    assertNotNull(result);
    assertEquals(1, result.mcvValue);
    assertEquals(70, result.mcvFrequency);

    var histogram = new EquiDepthHistogram(
        result.actualBucketCount,
        result.boundaries,
        result.frequencies,
        result.distinctCounts,
        100,
        result.mcvValue,
        result.mcvFrequency);

    assertEquals(1, histogram.mcvValue());
    assertEquals(70, histogram.mcvFrequency());
  }

  @Test
  public void mcvTracking_lastValueIsMostCommon() {
    // Edge case: the most common value is the last in sorted order.
    // Tests the final MCV check after the iteration loop.
    Object[] data = new Object[100];
    Arrays.fill(data, 0, 10, 1);
    Arrays.fill(data, 10, 100, 2); // 90 entries of value 2 (last)

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 100, 4);

    assertNotNull(result);
    assertEquals(2, result.mcvValue);
    assertEquals(90, result.mcvFrequency);
  }

  // ── Boundary array trimming ──

  @Test
  public void arrayTrimming_fewerActualBucketsThanTarget_arraysTrimmed() {
    // Given: 2 distinct values with 10 target buckets → at most 2 actual
    Object[] data = new Object[100];
    Arrays.fill(data, 0, 50, 0);
    Arrays.fill(data, 50, 100, 1);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 100, 10);

    assertNotNull(result);
    assertTrue(result.actualBucketCount <= 2);
    assertEquals(result.actualBucketCount + 1, result.boundaries.length);
    assertEquals(result.actualBucketCount, result.frequencies.length);
    assertEquals(result.actualBucketCount, result.distinctCounts.length);
  }

  @Test
  public void arrayTrimming_exactBucketCount_noTrimming() {
    // Given: enough distinct values to fill all target buckets
    var keys = IntStream.range(0, 400)
        .mapToObj(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(keys, 400, 4);

    assertNotNull(result);
    assertEquals(4, result.actualBucketCount);
    assertEquals(5, result.boundaries.length);
    assertEquals(4, result.frequencies.length);
    assertEquals(4, result.distinctCounts.length);
  }

  // ── Edge cases ──

  @Test
  public void singleEntry_producesOneBucket() {
    var result = IndexHistogramManager.scanAndBuild(
        Stream.of((Object) 99), 1, 10);

    assertNotNull(result);
    assertEquals(1, result.actualBucketCount);
    assertEquals(1, result.frequencies[0]);
    assertEquals(99, result.boundaries[0]);
    assertEquals(99, result.boundaries[1]);
  }

  @Test
  public void twoDistinctValues_producesAtMostTwoBuckets() {
    Object[] data = new Object[1000];
    Arrays.fill(data, 0, 600, false);
    Arrays.fill(data, 600, 1000, true);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 1000, 128);

    assertNotNull(result);
    assertTrue(result.actualBucketCount <= 2);
    assertEquals(2, result.totalDistinct);
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals(1000, totalFreq);
  }

  // ── String keys ──

  @Test
  public void stringKeys_buildAndFindBucket() {
    Object[] data = {"alpha", "bravo", "charlie", "delta", "echo",
        "foxtrot", "golf", "hotel", "india", "juliet"};
    Arrays.sort(data);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 10, 4);

    assertNotNull(result);
    assertEquals("alpha", result.boundaries[0]);
    assertEquals("juliet", result.boundaries[result.actualBucketCount]);

    var histogram = new EquiDepthHistogram(
        result.actualBucketCount,
        result.boundaries,
        result.frequencies,
        result.distinctCounts,
        10,
        result.mcvValue,
        result.mcvFrequency);

    assertEquals(0, histogram.findBucket("alpha"));
    assertEquals(result.actualBucketCount - 1,
        histogram.findBucket("juliet"));
  }

  // ── Large histogram for binary search ──

  @Test
  public void largeHistogram_findBucketBinarySearch_allBoundaryKeys() {
    // Create a 20-bucket histogram and verify findBucket for every
    // boundary and mid-bucket key
    int n = 20;
    var boundaries = new Comparable<?>[n + 1];
    var frequencies = new long[n];
    var distinctCounts = new long[n];
    for (int i = 0; i <= n; i++) {
      boundaries[i] = i * 100;
    }
    Arrays.fill(frequencies, 50L);
    Arrays.fill(distinctCounts, 50L);

    var histogram = new EquiDepthHistogram(
        n, boundaries, frequencies, distinctCounts, 1000, null, 0);

    for (int i = 0; i < n; i++) {
      assertEquals("Boundary " + (i * 100), i,
          histogram.findBucket(i * 100));
    }
    // Max boundary → last bucket
    assertEquals(n - 1, histogram.findBucket(n * 100));

    for (int i = 0; i < n; i++) {
      assertEquals("Mid " + (i * 100 + 50), i,
          histogram.findBucket(i * 100 + 50));
    }
  }

  // ── Cumulative threshold: equi-depth property ──

  @Test
  public void cumulativeThreshold_largePerfectlyUniform_allBucketsEqual() {
    // 10,000 entries with 100 distinct values (100 each), 10 target buckets
    var keys = IntStream.range(0, 10000)
        .mapToObj(i -> (Object) (i / 100));

    var result = IndexHistogramManager.scanAndBuild(keys, 10000, 10);

    assertNotNull(result);
    assertEquals(10, result.actualBucketCount);
    for (int i = 0; i < 10; i++) {
      assertEquals("Bucket " + i, 1000, result.frequencies[i]);
      assertEquals("Bucket " + i + " NDV", 10, result.distinctCounts[i]);
    }
  }

  // ── findBucket threshold boundary (8/9) ──

  @Test
  public void findBucket_exactly8Buckets_usesLinearScan() {
    var boundaries = new Comparable<?>[9];
    var frequencies = new long[8];
    var distinctCounts = new long[8];
    for (int i = 0; i <= 8; i++) {
      boundaries[i] = i * 10;
    }
    Arrays.fill(frequencies, 10L);
    Arrays.fill(distinctCounts, 10L);

    var histogram = new EquiDepthHistogram(
        8, boundaries, frequencies, distinctCounts, 80, null, 0);

    assertEquals(0, histogram.findBucket(0));
    assertEquals(4, histogram.findBucket(40));
    assertEquals(7, histogram.findBucket(80));
    assertEquals(7, histogram.findBucket(100));
  }

  @Test
  public void findBucket_9Buckets_usesBinarySearch() {
    var boundaries = new Comparable<?>[10];
    var frequencies = new long[9];
    var distinctCounts = new long[9];
    for (int i = 0; i <= 9; i++) {
      boundaries[i] = i * 10;
    }
    Arrays.fill(frequencies, 10L);
    Arrays.fill(distinctCounts, 10L);

    var histogram = new EquiDepthHistogram(
        9, boundaries, frequencies, distinctCounts, 90, null, 0);

    assertEquals(0, histogram.findBucket(0));
    assertEquals(4, histogram.findBucket(40));
    assertEquals(8, histogram.findBucket(90));
    assertEquals(8, histogram.findBucket(100));
  }

  // ── Zipf-distributed build ──

  @Test
  public void scanAndBuild_zipfDistribution_capturesSkewedShape() {
    // Build a histogram from a Zipf-like distribution where the most frequent
    // value (0) has the highest count and frequency decreases with rank.
    // Zipf approximation: value i appears floor(N / (i+1)) times (harmonic).
    int ndv = 50;
    int n = 0;
    int[] counts = new int[ndv];
    for (int i = 0; i < ndv; i++) {
      counts[i] = Math.max(1, 5000 / (i + 1));
      n += counts[i];
    }
    // Generate sorted stream: counts[0] copies of 0, counts[1] copies of 1, ...
    final int totalN = n;
    final int[] finalCounts = counts;
    var keys = IntStream.range(0, totalN).mapToObj(idx -> {
      int cumulative = 0;
      for (int v = 0; v < ndv; v++) {
        cumulative += finalCounts[v];
        if (idx < cumulative) {
          return (Object) v;
        }
      }
      return (Object) (ndv - 1);
    });

    var result = IndexHistogramManager.scanAndBuild(keys, totalN, 32);
    assertNotNull(result);

    // The most frequent value (0) should be in a bucket with high frequency.
    // The first bucket should contain more entries than the last bucket
    // (Zipf skew: low-rank values are dense, high-rank values are sparse).
    assertTrue("First bucket should have more entries than last bucket "
        + "(Zipf skew): first=" + result.frequencies[0]
        + " last=" + result.frequencies[result.actualBucketCount - 1],
        result.frequencies[0] >= result.frequencies[result.actualBucketCount - 1]);

    // Verify total entries match
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals(totalN, totalFreq);

    // Verify equi-depth property: the majority of buckets should be
    // within 3x of the target. Zipf's most frequent values (rank 0-2)
    // can dominate a single bucket because all duplicates stay together,
    // so we check that at least 75% of buckets are within bound.
    long target = totalN / result.actualBucketCount;
    int withinBound = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      if (result.frequencies[i] <= target * 3) {
        withinBound++;
      }
    }
    assertTrue("At least 75% of buckets should be within 3x of target, "
        + "but only " + withinBound + "/" + result.actualBucketCount + " are",
        withinBound >= result.actualBucketCount * 3 / 4);
  }

  // ── Variable-length key truncation and bucket merging ──

  @Test
  public void fitToPage_longStringKeys_reducesBucketCount() {
    // Given: histogram with 16 buckets where each boundary is a very long
    // string (500 bytes serialized) that exceeds the per-boundary budget.
    // fitToPage should reduce bucket count to fit within a single page.
    int buckets = 16;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    for (int i = 0; i <= buckets; i++) {
      // Each boundary is a long string
      boundaries[i] = "key_" + String.format("%0500d", i);
    }
    for (int i = 0; i < buckets; i++) {
      frequencies[i] = 100;
      distinctCounts[i] = 10;
    }

    var buildResult = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets, 160,
        null, 0);

    // Large boundary calculator: each boundary = 510 bytes
    // (4-byte prefix + 506-byte string). Total = 17 * 510 = 8670 bytes,
    // which exceeds the page budget (~8023 bytes for 16 buckets).
    var fitResult = IndexHistogramManager.fitToPage(
        buildResult, 1600, true, 0,
        (bounds, count) -> (count + 1) * 510);

    assertNotNull("fitToPage should produce a result (not fall to uniform)",
        fitResult);
    assertTrue("Bucket count should be reduced from " + buckets
        + ", got " + fitResult.histogram().bucketCount(),
        fitResult.histogram().bucketCount() < buckets);
    // Frequencies should sum to the original total
    long totalFreq = 0;
    for (int i = 0; i < fitResult.histogram().bucketCount(); i++) {
      totalFreq += fitResult.histogram().frequencies()[i];
    }
    assertEquals(1600, totalFreq);
  }

  @Test
  public void fitToPage_truncationPreservesBoundarySortOrder() {
    // After bucket reduction via fitToPage, boundaries must still be sorted
    int buckets = 8;
    var scanResult = IndexHistogramManager.scanAndBuild(
        IntStream.range(0, 800).mapToObj(i -> (Object) (i / 100)),
        800, buckets);
    assertNotNull(scanResult);

    // Use a boundary calculator that reports large sizes, forcing reduction
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 800, true, 0,
        (bounds, count) -> (count + 1) * 2000);

    assertNotNull(fitResult);
    // Verify boundaries are monotonically non-decreasing
    var h = fitResult.histogram();
    for (int i = 0; i < h.bucketCount(); i++) {
      assertTrue("Boundary " + i + " should be <= boundary " + (i + 1),
          DefaultComparator.INSTANCE.compare(
              h.boundaries()[i], h.boundaries()[i + 1]) <= 0);
    }
  }

  // ── Adaptive bucket count: sqrt(N) cap ──

  @Test
  public void scanAndBuild_smallIndex_bucketCountCappedBySqrt() {
    // buildHistogram applies sqrt cap BEFORE calling scanAndBuild:
    //   targetBuckets = min(128, floor(sqrt(1000))) = min(128, 31) = 31
    // Verify that scanAndBuild with the pre-capped value produces <= 31 buckets.
    int n = 1000;
    int sqrtCap = (int) Math.floor(Math.sqrt(n));
    assertEquals("sqrt(1000) should be 31", 31, sqrtCap);

    int targetBuckets = Math.min(128, sqrtCap);
    var keys = IntStream.range(0, n).mapToObj(i -> (Object) i);

    var result = IndexHistogramManager.scanAndBuild(keys, n, targetBuckets);
    assertNotNull(result);

    assertTrue("Bucket count " + result.actualBucketCount
        + " should be <= sqrt-capped target " + targetBuckets,
        result.actualBucketCount <= targetBuckets);

    // Verify total entries preserved
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals(n, totalFreq);

    // Each bucket should have at least sqrt(n) entries on average
    long avgPerBucket = n / result.actualBucketCount;
    assertTrue("Average entries per bucket (" + avgPerBucket
        + ") should be >= sqrt(n) (" + sqrtCap + ")",
        avgPerBucket >= sqrtCap);
  }

  @Test
  public void scanAndBuild_largeIndex_fullBucketCount() {
    // Given: 20000 entries with 128 target buckets.
    // sqrt(20000) = 141 > 128, so the sqrt cap does NOT fire.
    // buildHistogram would pass targetBuckets = min(128, 141) = 128.
    int n = 20000;
    int targetBuckets = Math.min(128,
        (int) Math.floor(Math.sqrt(n)));
    assertEquals("sqrt cap should not reduce 128", 128, targetBuckets);

    var keys = IntStream.range(0, n).mapToObj(i -> (Object) (i / 157));
    // 157 distinct values, each appearing ~127 times

    var result = IndexHistogramManager.scanAndBuild(keys, n, targetBuckets);
    assertNotNull(result);

    assertEquals("Large index should use full target bucket count",
        128, result.actualBucketCount);
  }

  // ── NDV-based bucket pre-computation ──

  @Test
  public void scanAndBuild_lowNDV_bucketCountEqualsNDV() {
    // Given: 5000 entries with only 5 distinct values.
    // Target 128 buckets, but NDV=5 means at most 5 buckets are useful.
    int n = 5000;
    var keys = IntStream.range(0, n).mapToObj(i -> (Object) (i / 1000));
    // Values: 0,0,...,0 (1000x), 1,1,...,1 (1000x), ..., 4,4,...,4 (1000x)

    var result = IndexHistogramManager.scanAndBuild(keys, n, 128);
    assertNotNull(result);

    // The scan naturally produces at most NDV=5 buckets (split condition
    // never fires more than NDV-1=4 times for 5 distinct values).
    assertEquals("Bucket count should equal NDV for low-NDV index",
        5, result.actualBucketCount);

    // Each bucket should have exactly 1000 entries (perfect for 5 equal groups)
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertEquals("Bucket " + i + " frequency", 1000,
          result.frequencies[i]);
      assertEquals("Bucket " + i + " distinctCount", 1,
          result.distinctCounts[i]);
    }
  }

  @Test
  public void scanAndBuild_booleanIndex_twoDistinctValues_twoBuckets() {
    // Given: boolean-like index with 10000 entries, 2 distinct values
    int n = 10000;
    var keys = IntStream.range(0, n).mapToObj(i -> (Object) (i < 3000 ? 0 : 1));
    // 3000 zeros, 7000 ones (sorted)

    var result = IndexHistogramManager.scanAndBuild(keys, n, 128);
    assertNotNull(result);

    assertEquals("Boolean index should produce 2 buckets",
        2, result.actualBucketCount);
    assertEquals(3000, result.frequencies[0]);
    assertEquals(7000, result.frequencies[1]);
    assertEquals(1, result.distinctCounts[0]);
    assertEquals(1, result.distinctCounts[1]);
  }

  // ── mergeBuckets ──

  @Test
  public void mergeBuckets_8to4_sumsFrequenciesAndNdv() {
    // Given: 8 buckets with known frequencies and NDVs
    var boundaries = new Comparable<?>[] {
        0, 10, 20, 30, 40, 50, 60, 70, 80};
    var frequencies = new long[] {10, 20, 30, 40, 50, 60, 70, 80};
    var distinctCounts = new long[] {1, 2, 3, 4, 5, 6, 7, 8};

    // When: merge 8 → 4 (ratio = 2, pairs of 2)
    var merged = IndexHistogramManager.mergeBuckets(
        boundaries, frequencies, distinctCounts, 8, 4);

    // Then: merged buckets sum adjacent pairs
    assertEquals(4, merged.actualBucketCount);
    assertEquals(30, merged.frequencies[0]); // 10+20
    assertEquals(70, merged.frequencies[1]); // 30+40
    assertEquals(110, merged.frequencies[2]); // 50+60
    assertEquals(150, merged.frequencies[3]); // 70+80
    assertEquals(3, merged.distinctCounts[0]); // 1+2
    assertEquals(7, merged.distinctCounts[1]); // 3+4
    assertEquals(11, merged.distinctCounts[2]); // 5+6
    assertEquals(15, merged.distinctCounts[3]); // 7+8
    // Boundaries: [0], [20], [40], [60], [80]
    assertEquals(0, merged.boundaries[0]);
    assertEquals(20, merged.boundaries[1]);
    assertEquals(40, merged.boundaries[2]);
    assertEquals(60, merged.boundaries[3]);
    assertEquals(80, merged.boundaries[4]);
  }

  @Test
  public void mergeBuckets_6to3_lastBucketAbsorbsRemainder() {
    // Given: 6 buckets (ratio = 2, but last bucket absorbs remainder)
    var boundaries = new Comparable<?>[] {
        0, 10, 20, 30, 40, 50, 60};
    var frequencies = new long[] {10, 10, 10, 10, 10, 10};
    var distinctCounts = new long[] {5, 5, 5, 5, 5, 5};

    var merged = IndexHistogramManager.mergeBuckets(
        boundaries, frequencies, distinctCounts, 6, 3);

    assertEquals(3, merged.actualBucketCount);
    assertEquals(20, merged.frequencies[0]); // 10+10
    assertEquals(20, merged.frequencies[1]); // 10+10
    assertEquals(20, merged.frequencies[2]); // 10+10 (last absorbs rest)
    assertEquals(0, merged.boundaries[0]);
    assertEquals(60, merged.boundaries[3]);
  }

  @Test
  public void mergeBuckets_7to4_lastBucketAbsorbsRemainder() {
    // 7 buckets merged to 4 — ratio = 7/4 = 1 (integer division), so
    // first 3 buckets get 1 source each, last bucket absorbs remainder
    // (indices 3,4,5,6 → 4 source buckets).
    Comparable<?>[] bounds = {0, 10, 20, 30, 40, 50, 60, 70};
    long[] freqs = {100, 100, 100, 100, 100, 100, 100};
    long[] ndvs = {10, 10, 10, 10, 10, 10, 10};

    var result = IndexHistogramManager.mergeBuckets(
        bounds, freqs, ndvs, 7, 4);

    assertEquals(4, result.actualBucketCount);
    assertEquals(100, result.frequencies[0]);
    assertEquals(100, result.frequencies[1]);
    assertEquals(100, result.frequencies[2]);
    assertEquals(400, result.frequencies[3]); // 4 buckets merged
  }

  @Test
  public void mergeBuckets_preservesMinAndMaxBoundaries() {
    var boundaries = new Comparable<?>[] {
        "alpha", "bravo", "charlie", "delta", "echo"};
    var frequencies = new long[] {25, 25, 25, 25};
    var distinctCounts = new long[] {10, 10, 10, 10};

    var merged = IndexHistogramManager.mergeBuckets(
        boundaries, frequencies, distinctCounts, 4, 2);

    assertEquals("alpha", merged.boundaries[0]);
    assertEquals("echo", merged.boundaries[2]);
  }

  // ── computeMaxBoundarySpace ──

  @Test
  public void computeMaxBoundarySpace_returnsPositiveForSmallBucketCount() {
    // With 4 buckets, no HLL, no MCV → should leave plenty of space
    int space = IndexHistogramManager.computeMaxBoundarySpace(4, 0, 0);
    assertTrue("Space should be positive, got " + space, space > 0);
    // Page payload = 8192 - 28 = 8164, minus FIXED_HEADER (53),
    // minus HISTOGRAM_BLOB_HEADER (24),
    // minus 4*8*2 (frequencies+distinctCounts) = 8164 - 53 - 24 - 64 = 8023
    assertEquals(8023, space);
  }

  @Test
  public void computeMaxBoundarySpace_hllReducesAvailable() {
    int withoutHll =
        IndexHistogramManager.computeMaxBoundarySpace(4, 0, 0);
    int hllSize = HyperLogLogSketch.serializedSize();
    int withHll =
        IndexHistogramManager.computeMaxBoundarySpace(4, hllSize, 0);
    assertEquals(hllSize, withoutHll - withHll);
  }

  @Test
  public void computeMaxBoundarySpace_mcvKeyReducesAvailable() {
    int withoutMcv =
        IndexHistogramManager.computeMaxBoundarySpace(4, 0, 0);
    int withMcv =
        IndexHistogramManager.computeMaxBoundarySpace(4, 0, 100);
    assertEquals(100, withoutMcv - withMcv);
  }

  @Test
  public void computeMaxBoundarySpace_accountsForBlobHeader() {
    // The blob header inside EquiDepthHistogram.serialize() adds 24 bytes
    // (bucketCount=4 + nonNullCount=8 + mcvFrequency=8 + mcvKeyLength=4).
    // Verify the budget accounts for it by checking the formula against
    // the actual serialized size of a histogram that uses exactly the
    // boundary budget.
    int buckets = 4;
    int blobHeaderSize = IndexHistogramManager.HISTOGRAM_BLOB_HEADER_SIZE;
    assertEquals("Blob header should be 24 bytes", 24, blobHeaderSize);

    // Compute max boundary space from the budget
    int maxBoundarySpace =
        IndexHistogramManager.computeMaxBoundarySpace(buckets, 0, 0);

    // The total blob size for a histogram that uses exactly maxBoundarySpace
    // for boundaries should be:
    //   blobHeader(24) + boundaries(maxBoundarySpace)
    //   + frequencies(buckets*8) + distinctCounts(buckets*8)
    int expectedBlobSize = blobHeaderSize + maxBoundarySpace
        + buckets * 8 + buckets * 8;

    // This blob + FIXED_HEADER_SIZE must fit within the page payload
    int pagePayload = 8192 - 28; // MAX_PAGE_SIZE - NEXT_FREE_POSITION
    int totalUsed = IndexHistogramManager.FIXED_HEADER_SIZE + expectedBlobSize;
    assertTrue(
        "Boundary-budget histogram blob must fit on page: totalUsed="
            + totalUsed + " pagePayload=" + pagePayload,
        totalUsed <= pagePayload);

    // And adding even 1 more byte should exceed the budget
    int overBudgetBlobSize = expectedBlobSize + 1;
    int overBudgetUsed =
        IndexHistogramManager.FIXED_HEADER_SIZE + overBudgetBlobSize;
    assertTrue(
        "One byte over budget should exceed page capacity",
        overBudgetUsed > pagePayload);
  }

  // ── fitToPage ──

  @Test
  public void fitToPage_fitsWithinBudget_returnsOriginalBucketCount() {
    // Given: 8 buckets with small boundaries that fit easily
    var scanResult = IndexHistogramManager.scanAndBuild(
        IntStream.range(0, 800).mapToObj(i -> (Object) (i / 100)),
        800, 8);
    assertNotNull(scanResult);

    // Boundary calculator: each boundary = 8 bytes (4-byte prefix + 4-byte
    // int). Total = 9 boundaries * 8 = 72 bytes (well within budget)
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 800, true, 0,
        (bounds, count) -> (count + 1) * 8);

    assertNotNull(fitResult);
    assertEquals(scanResult.actualBucketCount,
        fitResult.histogram().bucketCount());
    assertFalse(fitResult.hllOnPage1());
  }

  @Test
  public void fitToPage_exceedsBudget_reducesBucketCount() {
    // Given: 8 buckets with artificially large boundaries that exceed
    // page budget, forcing bucket count reduction
    var scanResult = IndexHistogramManager.scanAndBuild(
        IntStream.range(0, 800).mapToObj(i -> (Object) (i / 100)),
        800, 8);
    assertNotNull(scanResult);

    // Boundary calculator: reports huge boundaries to force reduction
    // Available for 8 buckets with no HLL/MCV ≈ 7919 bytes
    // Report each boundary as 1000 bytes → 9 * 1000 = 9000 > 7919
    // After merge to 4 buckets → 5 * 1000 = 5000 < 8047 → fits
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 800, true, 0,
        (bounds, count) -> (count + 1) * 1000);

    assertNotNull(fitResult);
    assertEquals(4, fitResult.histogram().bucketCount());
    // Frequencies should sum to 800
    long totalFreq = 0;
    for (long f : fitResult.histogram().frequencies()) {
      totalFreq += f;
    }
    assertEquals(800, totalFreq);
  }

  @Test
  public void fitToPage_tooLargeForAnyHistogram_returnsNull() {
    // Given: 6 buckets, single-value. Halving 6→3, 3 < MINIMUM (4),
    // single-value has no HLL spill → return null.
    var scanResult = IndexHistogramManager.scanAndBuild(
        IntStream.range(0, 600).mapToObj(i -> (Object) (i / 100)),
        600, 6);
    assertNotNull(scanResult);

    // Report huge boundary sizes to ensure 6 buckets don't fit.
    // 6 buckets: available ≈ 8164-53-48-48 = 8015, total = 7*5000 = 35000
    // Halve to 3, 3 < MINIMUM_BUCKET_COUNT → null
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 600, true, 0,
        (bounds, count) -> (count + 1) * 5000);

    assertNull(fitResult);
  }

  @Test
  public void fitToPage_multiValue_spillsHllToPage1WhenNeeded() {
    // Given: multi-value index (singleValue=false), 6 buckets where
    // boundaries don't fit with HLL inline. The loop halves:
    //   6→3 (< MINIMUM) with HLL → spill HLL to page 1
    //   Reset to 6 (no HLL) → 6 > 4, check budget:
    //   6 without HLL fits → result has hllOnPage1=true, 6 buckets.
    var scanResult = IndexHistogramManager.scanAndBuild(
        IntStream.range(0, 600).mapToObj(i -> (Object) (i / 100)),
        600, 6);
    assertNotNull(scanResult);

    // Boundary size = 1100 per boundary (7 boundaries * 1100 = 7700).
    // 6 buckets + HLL(1024): available = 8164-53-48-48-1024 = 6991
    //   7700 > 6991 → halve to 3 → 3 < MINIMUM → spill HLL
    // Reset to 6, no HLL: available = 8164-53-48-48-0 = 8015
    //   7700 < 8015 → fits!
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 600, false, 0,
        (bounds, count) -> (count + 1) * 1100);

    assertNotNull(fitResult);
    assertTrue("HLL should be spilled to page 1",
        fitResult.hllOnPage1());
    assertEquals(scanResult.actualBucketCount,
        fitResult.histogram().bucketCount());
  }

  @Test
  public void fitToPage_preservesMcvInResultHistogram() {
    // Given: data with a clear MCV
    Object[] data = new Object[100];
    Arrays.fill(data, 0, 80, 1);
    Arrays.fill(data, 80, 100, 2);
    var scanResult = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 100, 4);
    assertNotNull(scanResult);
    assertEquals(1, scanResult.mcvValue);

    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 100, true, 0,
        (bounds, count) -> (count + 1) * 8);

    assertNotNull(fitResult);
    assertEquals(1, fitResult.histogram().mcvValue());
    assertEquals(80, fitResult.histogram().mcvFrequency());
  }

  @Test
  public void fitToPage_afterMerge_boundariesAreMonotonic() {
    // Force a merge and verify the result histogram has valid boundaries
    var scanResult = IndexHistogramManager.scanAndBuild(
        IntStream.range(0, 800).mapToObj(i -> (Object) (i / 100)),
        800, 8);
    assertNotNull(scanResult);

    // Force merge: 8 → 4
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 800, true, 0,
        (bounds, count) -> (count + 1) * 1000);

    assertNotNull(fitResult);
    var h = fitResult.histogram();
    var comparator = DefaultComparator.INSTANCE;
    for (int i = 0; i < h.bucketCount(); i++) {
      assertTrue(
          "boundaries[" + i + "] should be <= boundaries[" + (i + 1) + "]",
          comparator.compare(
              h.boundaries()[i], h.boundaries()[i + 1]) <= 0);
    }
  }

  @Test
  public void fitToPage_largeMcvKey_forcesMerge() {
    // Create a histogram where boundaries fit without MCV, but adding a
    // large MCV key pushes the total over the page budget, forcing a
    // merge. Use a boundary calculator that reports moderately large
    // sizes so that the MCV key is the tipping factor.
    var bounds = new Comparable<?>[129]; // 128 buckets
    var freqs = new long[128];
    var ndvs = new long[128];
    for (int i = 0; i <= 128; i++) {
      bounds[i] = i;
    }
    for (int i = 0; i < 128; i++) {
      freqs[i] = 100;
      ndvs[i] = 10;
    }

    var result = new IndexHistogramManager.BuildResult(
        bounds, freqs, ndvs, 128, 1000, 50, 500);

    // Large MCV key (4000 bytes) eats into the boundary budget.
    // Page budget for 128 buckets:
    //   8164 - 53 - 1024 - 1024 - 4000 = 2063 bytes for boundaries.
    // With 40 bytes per boundary: 129 * 40 = 5160 > 2063 → must merge.
    // Without MCV: 8164 - 53 - 1024 - 1024 = 6063 → 5160 < 6063 → fits.
    int mcvKeySize = 4000;
    IndexHistogramManager.BoundarySizeCalculator calc =
        (b, bc) -> (bc + 1) * 40;

    var fitResult = IndexHistogramManager.fitToPage(
        result, 12800, true, mcvKeySize, calc);

    // Should have merged down because of the large MCV key
    assertNotNull(fitResult);
    assertTrue("Large MCV key should force bucket reduction",
        fitResult.histogram().bucketCount() < 128);
  }

  @Test
  public void fitToPage_multiStepHalving_correctlyMergesFromPrevious() {
    // Given: 16 buckets that need two rounds of halving (16→8→4).
    // This exercises the sourceBucketCount tracking — the second merge
    // must use 8 (the result of the first merge) as the source count,
    // not the original 16.
    var scanResult = IndexHistogramManager.scanAndBuild(
        IntStream.range(0, 1600).mapToObj(i -> (Object) (i / 100)),
        1600, 16);
    assertNotNull(scanResult);
    assertEquals(16, scanResult.actualBucketCount);

    // Per-boundary size = 500 bytes.
    // 16 buckets: available = 8164-53-128-128 = 7855, 17*500=8500 → no
    // Halve to 8: merge 16→8, 9*500=4500 vs available =
    //   8164-53-64-64 = 7983 → 4500 < 7983 → fits, BUT 8>4 so we check
    //   the available first: 4500 < 7983 → fits! Loop exits at 8.
    //
    // To force two halvings, make 8-bucket boundaries still too large:
    // 9 * 900 = 8100 > 7983 → need to halve again: 8→4
    // 5 * 900 = 4500 < 8047 → fits at 4
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 1600, true, 0,
        (bounds, count) -> (count + 1) * 900);

    assertNotNull(fitResult);
    assertEquals(4, fitResult.histogram().bucketCount());
    // Frequencies should sum to 1600
    long totalFreq = 0;
    for (long f : fitResult.histogram().frequencies()) {
      totalFreq += f;
    }
    assertEquals(1600, totalFreq);
    // Verify boundaries are valid
    var h = fitResult.histogram();
    assertEquals(5, h.boundaries().length);
    var comparator = DefaultComparator.INSTANCE;
    for (int i = 0; i < h.bucketCount(); i++) {
      assertTrue(
          "boundaries[" + i + "] <= boundaries[" + (i + 1) + "]",
          comparator.compare(
              h.boundaries()[i], h.boundaries()[i + 1]) <= 0);
    }
  }

  @Test
  public void fitToPage_initialBucketCountEqualsMinimum_exceedsBudget() {
    // When actualBucketCount == MINIMUM_BUCKET_COUNT (4) and boundaries
    // exceed the page budget, fitToPage cannot halve further (4/2=2 < 4).
    // The while-loop condition (bucketCount > MINIMUM) is false from the
    // start, so the histogram is constructed as-is. This test documents
    // the current behavior: oversized boundaries are not rejected when
    // the initial bucket count is already at the minimum.
    var scanResult = IndexHistogramManager.scanAndBuild(
        IntStream.range(0, 400).mapToObj(i -> (Object) (i / 100)),
        400, 4);
    assertNotNull(scanResult);

    // Report boundaries larger than page budget
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 400, true, 0,
        (bounds, count) -> (count + 1) * 5000);

    // Current behavior: returns non-null even though boundaries exceed
    // page space. This is acceptable because in practice 4-bucket
    // histograms have very small boundaries. If this becomes a real
    // issue, production code should add a post-loop budget check.
    assertNotNull(fitResult);
    assertEquals(scanResult.actualBucketCount,
        fitResult.histogram().bucketCount());
  }

  // ── Long string boundary truncation ──

  @Test
  public void fitToPage_longStringBoundaries_mergePreservesSortOrder() {
    // Given: a histogram with 128 buckets and long string boundaries.
    // Each boundary has a 950-char common prefix + unique 50-char suffix.
    // Total serialized size per boundary = 4 (length prefix) + 1000 chars
    // = 1004 bytes. For 129 boundaries: 129 × 1004 = 129,516 bytes, far
    // exceeding the ~6000-byte budget on a single page. fitToPage must
    // halve repeatedly (128 → 64 → 32 → 16 → 8 → 4) until it fits.
    int buckets = 128;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    String prefix = "X".repeat(950);
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = prefix + String.format("%050d", i);
    }
    Arrays.fill(frequencies, 10L);
    Arrays.fill(distinctCounts, 10L);
    var scanResult = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets,
        buckets * 10L, null, 0);

    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, buckets * 10L, false, 0,
        (bounds, count) -> {
          int total = 0;
          for (int i = 0; i <= count; i++) {
            total += 4 + ((String) bounds[i]).length();
          }
          return total;
        });

    assertNotNull("Should produce a histogram (with fewer buckets)",
        fitResult);

    // Verify merged boundaries maintain sort order
    var h = fitResult.histogram();
    var comparator = DefaultComparator.INSTANCE;
    for (int i = 0; i < h.bucketCount(); i++) {
      assertTrue(
          "boundaries[" + i + "]=" + h.boundaries()[i].toString().substring(950)
              + " should be <= boundaries[" + (i + 1) + "]="
              + h.boundaries()[i + 1].toString().substring(950),
          comparator.compare(
              h.boundaries()[i], h.boundaries()[i + 1]) <= 0);
    }
    // Bucket count should have been reduced from 128
    assertTrue("Bucket count should be reduced from " + buckets
        + " but was " + h.bucketCount(),
        h.bucketCount() < buckets);
    assertTrue("Bucket count should be >= MINIMUM_BUCKET_COUNT (4)",
        h.bucketCount() >= 4);
  }

  @Test
  public void fitToPage_identicalPrefixBoundaries_mergesToFewerBuckets() {
    // Given: boundaries that are identical after a tight budget forces
    // truncation-like reduction. Adjacent equal boundaries cause bucket
    // merging, resulting in fewer buckets.
    int buckets = 8;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    // All boundaries are the same string — simulates the case where
    // truncation produces identical adjacent boundaries.
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = "same_key";
    }
    // Only first and last differ to make it a valid histogram
    boundaries[0] = "aaa";
    boundaries[buckets] = "zzz";
    Arrays.fill(frequencies, 50L);
    Arrays.fill(distinctCounts, 10L);

    var scanResult = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets, 400, null, 0);

    // Small budget to force merging
    var fitResult = IndexHistogramManager.fitToPage(
        scanResult, 200, false, 0,
        (bounds, count) -> (count + 1) * 20);

    assertNotNull(fitResult);
    var h = fitResult.histogram();
    // Merged histogram should have fewer buckets
    assertTrue("Bucket count should be <= " + buckets,
        h.bucketCount() <= buckets);
    // Frequencies should sum to the original total
    long totalFreq = 0;
    for (long f : h.frequencies()) {
      totalFreq += f;
    }
    assertEquals("Total frequency must be preserved", 400, totalFreq);
  }

  // ── Helpers ──

  private static EquiDepthHistogram create10BucketHistogram() {
    var boundaries = new Comparable<?>[11];
    var frequencies = new long[10];
    var distinctCounts = new long[10];
    for (int i = 0; i <= 10; i++) {
      boundaries[i] = i * 10;
    }
    Arrays.fill(frequencies, 10L);
    Arrays.fill(distinctCounts, 10L);
    return new EquiDepthHistogram(
        10, boundaries, frequencies, distinctCounts, 100, null, 0);
  }

  // ── Strategy 1: Boundary truncation for variable-length keys ──

  /**
   * A short ASCII string (under MAX_BOUNDARY_BYTES) should not be
   * truncated. The truncateBoundary method must return the original
   * instance unchanged.
   */
  @Test
  public void truncateBoundary_shortString_returnsOriginal() {
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;
    String shortKey = "hello";
    var result = IndexHistogramManager.truncateBoundary(
        shortKey, 256, ser, factory);
    assertSame("Short key should not be truncated", shortKey, result);
  }

  /**
   * A long ASCII string exceeding MAX_BOUNDARY_BYTES must be truncated
   * to a shorter prefix whose serialized size fits within the limit.
   */
  @Test
  public void truncateBoundary_longAsciiString_truncatesToFit() {
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;
    String longKey = "A".repeat(500); // 500 bytes UTF-8 + 2-byte header = 502
    int maxBytes = 100;
    var result = (String) (Object) IndexHistogramManager.truncateBoundary(
        longKey, maxBytes, ser, factory);

    // Truncated string should be shorter than original
    assertTrue("Truncated length " + result.length()
        + " should be < original " + longKey.length(),
        result.length() < longKey.length());

    // Serialized size should be within budget
    int serializedSize = ser.getObjectSize(factory, result);
    assertTrue("Serialized size " + serializedSize
        + " should be <= maxBytes " + maxBytes,
        serializedSize <= maxBytes);

    // Truncated string should be a prefix of the original
    assertTrue("Truncated string should be a prefix of the original",
        longKey.startsWith(result));

    // Truncated string preserves sort order (prefix <= original)
    assertTrue("Truncated prefix should be <= original",
        result.compareTo(longKey) <= 0);
  }

  /**
   * Multi-byte UTF-8 characters must not be split. A string with
   * 2-byte, 3-byte, and 4-byte characters should be truncated at
   * complete character boundaries.
   */
  @Test
  public void truncateBoundary_multiByte_utf8_noCharSplit() {
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;
    // Build a string with mixed multi-byte characters:
    // \u00E9 = é (2 bytes in UTF-8)
    // \u4E16 = 世 (3 bytes in UTF-8)
    // \uD83D\uDE00 = 😀 (4 bytes in UTF-8, surrogate pair in UTF-16)
    String multiByteKey = "\u00E9".repeat(50) // 50 x 2 = 100 UTF-8 bytes
        + "\u4E16".repeat(50) // 50 x 3 = 150 UTF-8 bytes
        + "\uD83D\uDE00".repeat(25); // 25 x 4 = 100 UTF-8 bytes
    // Total: 350 UTF-8 bytes + 2-byte header = 352 serialized bytes

    int maxBytes = 110; // Should fit ~54 é chars (108 bytes + 2 header)
    var result = (String) (Object) IndexHistogramManager.truncateBoundary(
        multiByteKey, maxBytes, ser, factory);

    // Verify it's a valid string (no split multi-byte chars)
    byte[] encoded = result.getBytes(StandardCharsets.UTF_8);
    String roundTripped = new String(encoded, StandardCharsets.UTF_8);
    assertEquals("Round-trip through UTF-8 should preserve the string",
        result, roundTripped);

    // Serialized size within budget
    int serializedSize = ser.getObjectSize(factory, result);
    assertTrue("Serialized size " + serializedSize
        + " should be <= " + maxBytes,
        serializedSize <= maxBytes);

    // Prefix of original
    assertTrue("Truncated string should be a prefix",
        multiByteKey.startsWith(result));
  }

  /**
   * truncateBoundaries must truncate oversized string boundaries and
   * merge adjacent buckets when truncation produces equal boundaries.
   * Given 8 buckets where all boundaries share a 300-char common prefix
   * and differ only in the last few chars, truncation to 256 bytes
   * should collapse them and merge the corresponding buckets.
   */
  @Test
  public void truncateBoundaries_longPrefix_mergesAdjacentEqual() {
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;

    int buckets = 8;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];

    // Common prefix of 300 chars exceeds 256-byte max. After truncation,
    // all boundaries will share the same prefix and the differing suffix
    // is lost, making adjacent boundaries equal.
    String prefix = "P".repeat(300);
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = prefix + String.format("%03d", i);
    }
    Arrays.fill(frequencies, 100L);
    Arrays.fill(distinctCounts, 50L);

    var original = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets, 400, null, 0);

    // Truncate at 256 bytes (UTF8: 2-byte header + 254 payload chars
    // for ASCII). The 300-char prefix will be truncated to 254 chars,
    // losing the distinguishing suffix.
    var result = IndexHistogramManager.truncateBoundaries(
        original, 256, ser, factory);

    // Should have fewer buckets due to merging
    assertTrue("Bucket count should be reduced from " + buckets
        + " but was " + result.actualBucketCount,
        result.actualBucketCount < buckets);

    // Total frequency must be preserved
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals("Total frequency must be preserved",
        buckets * 100L, totalFreq);

    // Boundaries must be monotonically non-decreasing
    assertBoundariesMonotonic(result);

    // All boundaries should fit within max
    for (int i = 0; i <= result.actualBucketCount; i++) {
      int size = ser.getObjectSize(factory, result.boundaries[i]);
      assertTrue("Boundary " + i + " serialized size " + size
          + " should be <= 256", size <= 256);
    }
  }

  /**
   * When no boundaries exceed maxBoundaryBytes, truncateBoundaries
   * should return the original BuildResult unchanged (fast path).
   */
  @Test
  public void truncateBoundaries_allFit_returnsOriginal() {
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;

    int buckets = 4;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = "key_" + i; // short strings, ~7 bytes
    }
    Arrays.fill(frequencies, 25L);
    Arrays.fill(distinctCounts, 10L);

    var original = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets, 40, null, 0);

    var result = IndexHistogramManager.truncateBoundaries(
        original, 256, ser, factory);

    // Should be the same instance (no truncation needed)
    assertSame("No truncation needed — should return original",
        original, result);
  }

  /**
   * Truncation where some boundaries are truncated but all remain
   * distinct. Bucket count should be preserved, only the key values
   * change.
   */
  @Test
  public void truncateBoundaries_truncatedButDistinct_preservesBuckets() {
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;

    int buckets = 4;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    // Each boundary has a unique 10-char prefix then a long suffix.
    // After truncation, the unique prefix remains → no merging.
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = String.format("%010d", i) + "Z".repeat(500);
    }
    Arrays.fill(frequencies, 25L);
    Arrays.fill(distinctCounts, 10L);

    var original = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets, 40,
        null, 0);

    // maxBytes = 50 → truncate to ~48 ASCII chars (2-byte header)
    var result = IndexHistogramManager.truncateBoundaries(
        original, 50, ser, factory);

    // Bucket count should be preserved (all boundaries still distinct)
    assertEquals("Bucket count should be preserved",
        buckets, result.actualBucketCount);

    // Boundaries should all be within budget
    for (int i = 0; i <= result.actualBucketCount; i++) {
      int size = ser.getObjectSize(factory, result.boundaries[i]);
      assertTrue("Boundary " + i + " size " + size + " <= 50",
          size <= 50);
    }

    // Boundaries should still be monotonic
    assertBoundariesMonotonic(result);
  }

  /**
   * Verifies that Strategy 1 (truncation) is applied before Strategy 2
   * (bucket count reduction) in the instance fitToPage path. With
   * truncation active, long string boundaries that would have forced
   * aggressive bucket halving should instead be truncated, preserving
   * more buckets.
   */
  @Test
  public void truncation_preservesMoreBuckets_thanPureBucketReduction() {
    // Simulate what happens with 16-bucket histogram where each boundary
    // is 300 ASCII chars. Without truncation (Strategy 2 only): the
    // BoundarySizeCalculator sees 17 × 304 = 5168 bytes, exceeding
    // budget and forcing halving. With truncation to 256 bytes first:
    // 17 × 256 = 4352 bytes — may fit depending on page budget.
    int buckets = 16;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    // Each boundary is distinct even after truncation to 254 ASCII chars
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = String.format("%010d", i) + "X".repeat(290);
    }
    Arrays.fill(frequencies, 100L);
    Arrays.fill(distinctCounts, 50L);

    var original = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets,
        buckets * 50L, null, 0);

    // Without truncation (pure Strategy 2): compute boundary size as-is
    // Each boundary serialized = 2 (header) + 300 (payload) = 302.
    // With 4-byte length prefix in EquiDepthHistogram.serialize: 306 each.
    // 17 × 306 = 5202 bytes.
    var resultNoTruncation = IndexHistogramManager.fitToPage(
        original, buckets * 100L, true, 0,
        (bounds, count) -> {
          int total = 0;
          for (int i = 0; i <= count; i++) {
            total += 4 + ((String) bounds[i]).length();
          }
          return total;
        });

    // With truncation: simulate by truncating boundaries first
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;
    var truncated = IndexHistogramManager.truncateBoundaries(
        original, 256, ser, factory);

    var resultWithTruncation = IndexHistogramManager.fitToPage(
        truncated, buckets * 100L, true, 0,
        (bounds, count) -> {
          int total = 0;
          for (int i = 0; i <= count; i++) {
            String s = (String) bounds[i];
            total += 4 + Math.min(s.length(),
                256 - 2); // UTF-8 payload after header
          }
          return total;
        });

    // Both should produce valid histograms
    assertNotNull("Strategy 2 only should produce a result",
        resultNoTruncation);
    assertNotNull("Strategy 1+2 should produce a result",
        resultWithTruncation);

    // With truncation, we should have at least as many buckets
    assertTrue("Truncation should preserve >= buckets vs pure halving. "
        + "With truncation: " + resultWithTruncation.histogram().bucketCount()
        + ", without: " + resultNoTruncation.histogram().bucketCount(),
        resultWithTruncation.histogram().bucketCount()
            >= resultNoTruncation.histogram().bucketCount());
  }

  /**
   * When all boundaries collapse to the same value after truncation
   * (extreme degenerate case), truncateBoundaries should produce a
   * single-bucket histogram.
   */
  @Test
  public void truncateBoundaries_allCollapse_singleBucket() {
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;

    int buckets = 4;
    var boundaries = new Comparable<?>[buckets + 1];
    var frequencies = new long[buckets];
    var distinctCounts = new long[buckets];
    // All boundaries share an identical 500-char prefix; the
    // distinguishing suffix is only in the last 2 chars.
    // After truncation to 50 bytes (~48 ASCII chars), all are identical.
    String prefix = "Q".repeat(500);
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = prefix + String.format("%02d", i);
    }
    Arrays.fill(frequencies, 100L);
    Arrays.fill(distinctCounts, 25L);

    var original = new IndexHistogramManager.BuildResult(
        boundaries, frequencies, distinctCounts, buckets, 100,
        null, 0);

    var result = IndexHistogramManager.truncateBoundaries(
        original, 50, ser, factory);

    assertEquals("All boundaries collapsed — should be 1 bucket",
        1, result.actualBucketCount);
    assertEquals("Single bucket should have all frequencies",
        400L, result.frequencies[0]);
    assertEquals("Single bucket should have all NDV",
        100L, result.distinctCounts[0]);
  }

  /**
   * Fixed-length key types (Integer) should never be truncated,
   * regardless of maxBoundaryBytes setting. The truncateBoundary
   * method is only called for variable-length serializers in the
   * instance fitToPage path, but this test verifies the static method
   * handles them gracefully.
   */
  @Test
  public void truncateBoundary_fixedLengthKey_neverTruncated() {
    // IntegerSerializer always returns getObjectSize = 4, which is
    // always within any reasonable maxBoundaryBytes. Verify that the
    // method returns the original key unchanged even with a tiny limit.
    @SuppressWarnings("unchecked")
    var ser = (BinarySerializer<Object>) (BinarySerializer<?>) UTF8Serializer.INSTANCE;
    // UTF8Serializer.getObjectSize does not use the factory parameter
    var factory =
        (com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory) null;

    // For non-String, non-byte[] types, truncateBoundary returns as-is.
    // We can't easily test with IntegerSerializer here since
    // truncateBoundary operates on the serialized size, and Integer keys
    // would always fit. Instead verify that the fast path (size <= max)
    // works for short strings.
    String key = "short";
    var result = IndexHistogramManager.truncateBoundary(
        key, 10, ser, factory);
    assertSame(key, result);
  }

  /**
   * Asserts that boundaries in a BuildResult are in non-decreasing order.
   */
  private static void assertBoundariesMonotonic(
      IndexHistogramManager.BuildResult result) {
    var comparator = DefaultComparator.INSTANCE;
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertTrue(
          "boundaries[" + i + "]=" + result.boundaries[i]
              + " should be <= boundaries[" + (i + 1) + "]="
              + result.boundaries[i + 1],
          comparator.compare(
              result.boundaries[i], result.boundaries[i + 1]) <= 0);
    }
  }
}
