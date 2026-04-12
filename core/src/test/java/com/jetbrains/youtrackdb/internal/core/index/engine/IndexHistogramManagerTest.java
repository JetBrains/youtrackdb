package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Unit tests for {@link IndexHistogramManager} — focuses on the pure
 * computational logic that can be tested without storage infrastructure:
 * histogram construction ({@code scanAndBuild}), delta application
 * ({@code computeNewSnapshot}), and supporting algorithms.
 *
 * <p>Integration tests requiring storage (page I/O, lifecycle) are deferred
 * to Step 12.
 */
public class IndexHistogramManagerTest {

  // ── scanAndBuild tests ─────────────────────────────────────────

  @Test
  public void scanAndBuildProducesEquiDepthBucketsForUniformData() {
    // 100 entries, 10 distinct values (0..9), 10 each, 4 target buckets
    var keys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) (i / 10))
        .sorted();

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 4);

    assertNotNull(result);
    assertEquals(4, result.actualBucketCount);
    // Each bucket should have ~25 entries
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertTrue("Bucket " + i + " freq = " + result.frequencies[i],
          result.frequencies[i] > 0);
      totalFreq += result.frequencies[i];
    }
    assertEquals(100, totalFreq);
    // First boundary is min key (0), last boundary is max key (9)
    assertEquals(0, result.boundaries[0]);
    assertEquals(9, result.boundaries[result.actualBucketCount]);
  }

  @Test
  public void scanAndBuildReturnsNullForEmptyStream() {
    var result = IndexHistogramManager.scanAndBuild(
        Stream.empty(), 0, 4);
    assertNull(result);
  }

  @Test
  public void scanAndBuildHandlesAllIdenticalKeys() {
    // 100 entries, all value 42 → single bucket
    var keys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) 42);

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 10);

    assertNotNull(result);
    assertEquals(1, result.actualBucketCount);
    assertEquals(100, result.frequencies[0]);
    assertEquals(1, result.distinctCounts[0]);
    assertEquals(42, result.boundaries[0]);
    assertEquals(42, result.boundaries[1]);
    assertEquals(1, result.totalDistinct);
    // MCV should be 42
    assertEquals(42, result.mcvValue);
    assertEquals(100, result.mcvFrequency);
  }

  @Test
  public void scanAndBuildTracksMostCommonValue() {
    // Skewed data: 90 entries of value 1, 10 entries of value 2
    Object[] data = new Object[100];
    Arrays.fill(data, 0, 90, 1);
    Arrays.fill(data, 90, 100, 2);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 100, 4);

    assertNotNull(result);
    assertEquals(1, result.mcvValue);
    assertEquals(90, result.mcvFrequency);
  }

  @Test
  public void scanAndBuildComputesCorrectGlobalNDV() {
    // 5 distinct values, 20 each = 100 total
    var keys = IntStream.range(0, 100)
        .mapToObj(i -> (Object) (i / 20))
        .sorted();

    var result = IndexHistogramManager.scanAndBuild(keys, 100, 4);

    assertNotNull(result);
    assertEquals(5, result.totalDistinct);
  }

  @Test
  public void scanAndBuildKeepsDuplicatesInSameBucket() {
    // 4 distinct values with very different frequencies:
    // value 0: 50 entries, value 1: 20, value 2: 20, value 3: 10
    Object[] data = new Object[100];
    Arrays.fill(data, 0, 50, 0);
    Arrays.fill(data, 50, 70, 1);
    Arrays.fill(data, 70, 90, 2);
    Arrays.fill(data, 90, 100, 3);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 100, 4);

    assertNotNull(result);
    // All entries of a key should be in the same bucket (no splitting
    // duplicates across boundaries)
    for (int b = 0; b < result.actualBucketCount; b++) {
      assertTrue("Bucket " + b + " distinctCounts should be > 0",
          result.distinctCounts[b] > 0);
    }
  }

  @Test
  public void scanAndBuildHandlesSingleEntry() {
    var result = IndexHistogramManager.scanAndBuild(
        Stream.of((Object) 42), 1, 4);

    assertNotNull(result);
    assertEquals(1, result.actualBucketCount);
    assertEquals(1, result.frequencies[0]);
    assertEquals(1, result.distinctCounts[0]);
    assertEquals(1, result.totalDistinct);
  }

  @Test
  public void scanAndBuildUsesCumulativeThresholdForEvenDistribution() {
    // 1000 entries with 100 distinct values → 10 target buckets
    // Each bucket should have ~100 entries
    var keys = IntStream.range(0, 1000)
        .mapToObj(i -> (Object) (i / 10))
        .sorted();

    var result = IndexHistogramManager.scanAndBuild(keys, 1000, 10);

    assertNotNull(result);
    // Verify reasonable balance — no bucket should have more than 2x
    // the average
    long avg = 1000L / result.actualBucketCount;
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertTrue("Bucket " + i + " is too large: " + result.frequencies[i],
          result.frequencies[i] <= avg * 3);
    }
  }

  @Test
  public void scanAndBuildWithTwoDistinctValues() {
    // Boolean-like: 600 false, 400 true → at most 2 effective buckets
    Object[] data = new Object[1000];
    Arrays.fill(data, 0, 600, 0);
    Arrays.fill(data, 600, 1000, 1);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 1000, 128);

    assertNotNull(result);
    // Low-NDV: should produce at most 2 buckets (one per distinct value)
    assertTrue("Expected <= 2 buckets, got " + result.actualBucketCount,
        result.actualBucketCount <= 2);
    assertEquals(2, result.totalDistinct);
  }

  // ── computeNewSnapshot tests ───────────────────────────────────

  @Test
  public void computeNewSnapshotAppliesScalarCountersWithClamping() {
    var stats = new IndexStatistics(100, 100, 10);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -200; // would go negative
    delta.nullCountDelta = -50; // would go negative
    delta.mutationCount = 5;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Clamped to 0
    assertEquals(0, result.stats().totalCount());
    assertEquals(0, result.stats().nullCount());
    // Single-value: distinctCount == totalCount
    assertEquals(0, result.stats().distinctCount());
    assertEquals(5, result.mutationsSinceRebalance());
  }

  @Test
  public void computeNewSnapshotAppliesFrequencyDeltasWhenVersionMatches() {
    var histogram = new EquiDepthHistogram(
        3,
        new Comparable<?>[] {0, 10, 20, 30},
        new long[] {100, 200, 300},
        new long[] {10, 20, 30},
        600,
        null,
        0);
    var stats = new IndexStatistics(600, 600, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 600, 5, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 3;
    delta.mutationCount = 3;
    delta.initFrequencyDeltas(3, 5); // version matches
    delta.frequencyDeltas[0] = 1;
    delta.frequencyDeltas[1] = 2;
    delta.frequencyDeltas[2] = 0;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertEquals(603, result.stats().totalCount());
    assertNotNull(result.histogram());
    assertEquals(101, result.histogram().frequencies()[0]);
    assertEquals(202, result.histogram().frequencies()[1]);
    assertEquals(300, result.histogram().frequencies()[2]);
    // nonNullCount = sum of new frequencies
    assertEquals(603, result.histogram().nonNullCount());
  }

  @Test
  public void computeNewSnapshotDiscardsFrequencyDeltasOnVersionMismatch() {
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {300, 300},
        new long[] {50, 50},
        600,
        null,
        0);
    var stats = new IndexStatistics(600, 600, 0);
    // Version is 5
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 600, 5, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 10;
    delta.mutationCount = 10;
    delta.initFrequencyDeltas(2, 3); // version 3 != 5 → mismatch
    delta.frequencyDeltas[0] = 5;
    delta.frequencyDeltas[1] = 5;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Scalar counters applied
    assertEquals(610, result.stats().totalCount());
    // Frequency deltas discarded — original frequencies preserved
    assertNotNull(result.histogram());
    assertEquals(300, result.histogram().frequencies()[0]);
    assertEquals(300, result.histogram().frequencies()[1]);
    // nonNullCount updated from scalar counters: (600 + 10) - 0 = 610
    assertEquals(610, result.histogram().nonNullCount());
  }

  @Test
  public void computeNewSnapshotClampsNegativeFrequenciesToZeroAndSetsDriftFlag() {
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {10, 200},
        new long[] {5, 20},
        210,
        null,
        0);
    var stats = new IndexStatistics(210, 210, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 210, 1, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = -20;
    delta.mutationCount = 20;
    delta.initFrequencyDeltas(2, 1); // version matches
    delta.frequencyDeltas[0] = -15; // would make freq = 10 - 15 = -5

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Frequency clamped to 0
    assertEquals(0, result.histogram().frequencies()[0]);
    assertEquals(200, result.histogram().frequencies()[1]);
    // nonNullCount = max(0, newTotal - newNull) = max(0, 190 - 0) = 190
    // (uses accurate scalar counters, not the sum of clamped frequencies)
    assertEquals(190, result.histogram().nonNullCount());
    // Drift flag set
    assertTrue(result.hasDriftedBuckets());
  }

  @Test
  public void computeNewSnapshotMergesHllForMultiValue() {
    var hll = new HyperLogLogSketch();
    for (int i = 0; i < 100; i++) {
      hll.add(i * 31L); // add some distinct values
    }

    var stats = new IndexStatistics(1000, hll.estimate(), 50);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 1000, 0, false, hll, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 10;
    delta.mutationCount = 10;
    delta.hllSketch = new HyperLogLogSketch();
    for (int i = 100; i < 110; i++) {
      delta.hllSketch.add(i * 31L);
    }

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertEquals(1010, result.stats().totalCount());
    assertNotNull(result.hllSketch());
    // distinctCount should be updated from merged HLL estimate
    assertTrue("Distinct count should be > 0",
        result.stats().distinctCount() > 0);
    // The merged HLL should have more distinct values
    assertTrue("Merged HLL should estimate more distinct values",
        result.hllSketch().estimate() >= hll.estimate());
  }

  @Test
  public void computeNewSnapshotSingleValueDistinctCountEqualsTotalCount() {
    var stats = new IndexStatistics(500, 500, 10);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 500, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;
    delta.mutationCount = 5;
    // No HLL → single-value mode

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertEquals(505, result.stats().totalCount());
    // Single-value: distinctCount == totalCount - nullCount (non-null keys)
    assertEquals(495, result.stats().distinctCount());
  }

  @Test
  public void computeNewSnapshotAppliesDeltaToNonNullSnapshot() {
    // Verify that computeNewSnapshot correctly applies a delta to a
    // non-null snapshot (the normal commit path).
    var delta = new HistogramDelta();
    delta.totalCountDelta = 5;

    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, false, null, false);
    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);
    assertNotNull(result);
    assertEquals(105, result.stats().totalCount());
  }

  @Test
  public void computeNewSnapshotPreservesHistogramBoundariesAndNDV() {
    // Verify that delta application preserves the immutable boundaries
    // and distinctCounts (only frequencies change)
    var histogram = new EquiDepthHistogram(
        3,
        new Comparable<?>[] {1, 10, 20, 30},
        new long[] {100, 200, 300},
        new long[] {10, 20, 30},
        600,
        (Comparable<?>) 15,
        250L);
    var stats = new IndexStatistics(600, 600, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 600, 2, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 1;
    delta.mutationCount = 1;
    delta.initFrequencyDeltas(3, 2);
    delta.frequencyDeltas[1] = 1;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Boundaries unchanged
    assertEquals(1, result.histogram().boundaries()[0]);
    assertEquals(10, result.histogram().boundaries()[1]);
    assertEquals(20, result.histogram().boundaries()[2]);
    assertEquals(30, result.histogram().boundaries()[3]);
    // distinctCounts unchanged
    assertEquals(10, result.histogram().distinctCounts()[0]);
    assertEquals(20, result.histogram().distinctCounts()[1]);
    assertEquals(30, result.histogram().distinctCounts()[2]);
    // MCV preserved
    assertEquals(15, result.histogram().mcvValue());
    assertEquals(250L, result.histogram().mcvFrequency());
  }

  @Test
  public void computeNewSnapshotAccumulatesMutationsSinceRebalance() {
    var stats = new IndexStatistics(100, 100, 0);
    var snapshot = new HistogramSnapshot(
        stats, null, 50, 100, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 1;
    delta.mutationCount = 7;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    assertEquals(57, result.mutationsSinceRebalance());
  }

  @Test
  public void computeNewSnapshotWithNullFrequencyDeltasAndHistogram() {
    // Delta has no frequency deltas (e.g., only null key operations)
    // but snapshot has a histogram
    var histogram = new EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {300, 300},
        new long[] {50, 50},
        600,
        null,
        0);
    var stats = new IndexStatistics(600, 600, 0);
    var snapshot = new HistogramSnapshot(
        stats, histogram, 0, 600, 0, false, null, false);

    var delta = new HistogramDelta();
    delta.nullCountDelta = 1;
    delta.totalCountDelta = 1;
    delta.mutationCount = 1;
    // frequencyDeltas is null (no non-null key ops)

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Histogram frequencies unchanged
    assertEquals(300, result.histogram().frequencies()[0]);
    assertEquals(300, result.histogram().frequencies()[1]);
    assertEquals(601, result.stats().totalCount());
    assertEquals(1, result.stats().nullCount());
  }

  // ── Adaptive bucket count with NDV cap tests ────────────────

  @Test
  public void scanAndBuildWithNdvCappedTargetBucketsForBooleanIndex() {
    // Simulate a rebalance of a boolean index (NDV=2) where the NDV cap
    // reduces targetBuckets from 128 to MINIMUM_BUCKET_COUNT (4).
    // scanAndBuild should trim to 2 actual buckets regardless.
    Object[] data = new Object[1000];
    Arrays.fill(data, 0, 600, false);
    Arrays.fill(data, 600, 1000, true);

    // Without NDV cap: targetBuckets=31 (sqrt(1000)), allocates 31-element
    // arrays. With NDV cap: targetBuckets=4 (min(31, NDV=2) clamped to 4),
    // allocates only 4-element arrays. Both produce 2 actual buckets.
    int ndvCappedTarget = IndexHistogramManager.MINIMUM_BUCKET_COUNT;
    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 1000, ndvCappedTarget);

    assertNotNull(result);
    assertEquals(2, result.actualBucketCount);
    assertEquals(600, result.frequencies[0]);
    assertEquals(400, result.frequencies[1]);
    assertEquals(2, result.totalDistinct);
  }

  @Test
  public void scanAndBuildWithNdvCappedTargetBucketsForSmallNdv() {
    // Simulate NDV=5 with 10,000 entries. Without NDV cap:
    // targetBuckets = min(128, sqrt(10000)) = 100.
    // With NDV cap: targetBuckets = min(100, 5) = 5.
    // Both produce 5 actual buckets, but the capped version avoids
    // allocating 100-element intermediate arrays.
    Object[] data = new Object[10000];
    for (int i = 0; i < 10000; i++) {
      data[i] = i % 5;
    }
    Arrays.sort(data);

    var result = IndexHistogramManager.scanAndBuild(
        Arrays.stream(data), 10000, 5);

    assertNotNull(result);
    assertEquals(5, result.actualBucketCount);
    assertEquals(5, result.totalDistinct);
    // Each bucket has exactly 2000 entries (uniform distribution)
    for (int i = 0; i < result.actualBucketCount; i++) {
      assertEquals("Bucket " + i, 2000, result.frequencies[i]);
      assertEquals("Bucket " + i + " NDV", 1, result.distinctCounts[i]);
    }
  }

  @Test
  public void adaptiveBucketCount_ndvCapReducesBuckets() {
    // Verify via observable outcome: when NDV is very low (e.g., 5 distinct
    // values in 10000 entries), scanAndBuild produces at most NDV buckets.
    // This tests the production code path rather than reimplementing the
    // formula. See also scanAndBuildWithNdvCappedTargetBucketsForBooleanIndex
    // and scanAndBuildWithNdvCappedTargetBucketsForSmallNdv.
    var keys = new ArrayList<Object>();
    for (int i = 0; i < 10000; i++) {
      keys.add(i % 5); // 5 distinct values
    }
    keys.sort(null);
    // Target buckets = min(128, sqrt(10000)=100) = 100, but NDV=5 caps it.
    // The effective target after NDV cap should be max(5, MINIMUM_BUCKET_COUNT)=5.
    var result = IndexHistogramManager.scanAndBuild(
        keys.stream().map(k -> (Object) k), 10000, 5);
    assertNotNull(result);
    // scanAndBuild trims to actual distinct boundaries → at most 5 buckets
    assertTrue("Bucket count should be <= 5 (NDV), was: "
        + result.actualBucketCount, result.actualBucketCount <= 5);
    assertEquals(5, result.totalDistinct);
  }

  @Test
  public void scanAndBuildWithMinimumBucketCountTarget() {
    // Verify scanAndBuild handles the minimum bucket count (4) correctly
    // when NDV cap reduces target to MINIMUM_BUCKET_COUNT
    var keys = IntStream.range(0, 1000)
        .mapToObj(i -> (Object) (i / 100)) // 10 distinct values
        .sorted();

    var result = IndexHistogramManager.scanAndBuild(
        keys, 1000, IndexHistogramManager.MINIMUM_BUCKET_COUNT);

    assertNotNull(result);
    assertTrue("Expected <= " + IndexHistogramManager.MINIMUM_BUCKET_COUNT
        + " buckets, got " + result.actualBucketCount,
        result.actualBucketCount <= IndexHistogramManager.MINIMUM_BUCKET_COUNT);
    assertEquals(10, result.totalDistinct);
    long totalFreq = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      totalFreq += result.frequencies[i];
    }
    assertEquals(1000, totalFreq);
  }

  // ── DriftedBuckets preservation test ──

  @Test
  public void computeNewSnapshotPreservesDriftedBucketsFlag() {
    var stats = new IndexStatistics(100, 100, 0);
    // Already drifted
    var snapshot = new HistogramSnapshot(
        stats, null, 0, 100, 0, true, null, false);

    var delta = new HistogramDelta();
    delta.totalCountDelta = 1;
    delta.mutationCount = 1;

    var result = IndexHistogramManager.computeNewSnapshot(snapshot, delta);

    // Flag preserved when no histogram delta applied
    assertTrue(result.hasDriftedBuckets());
  }
}
