package com.jetbrains.youtrackdb.internal.core.index.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link SelectivityEstimator}.
 *
 * <p>Covers all three tiers (empty, uniform, histogram) and all predicate
 * types: equality, greater-than, less-than, greater-or-equal, less-or-equal,
 * range, IS NULL, IS NOT NULL, IN. Also tests MCV short-circuit,
 * out-of-range equality short-circuit, single-value bucket optimization,
 * degenerate bucket handling, and clamping behavior.
 */
public class SelectivityEstimatorTest {

  private static final double DELTA = 1e-9;

  // ── Helper: create a uniform histogram with integer boundaries ────

  /**
   * Creates a histogram with evenly spaced integer boundaries.
   * Each bucket has the same frequency and distinct count.
   */
  private static EquiDepthHistogram uniform(
      int buckets, int step, long freqPerBucket, long distinctPerBucket) {
    Comparable<?>[] boundaries = new Comparable<?>[buckets + 1];
    long[] frequencies = new long[buckets];
    long[] distinctCounts = new long[buckets];
    long nonNull = 0;
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = i * step;
    }
    for (int i = 0; i < buckets; i++) {
      frequencies[i] = freqPerBucket;
      distinctCounts[i] = distinctPerBucket;
      nonNull += freqPerBucket;
    }
    return new EquiDepthHistogram(
        buckets, boundaries, frequencies, distinctCounts, nonNull,
        null, 0);
  }

  /**
   * Creates a histogram with string boundaries, single bucket.
   */
  private static EquiDepthHistogram stringHistogram(
      String lo, String hi, long freq, long ndv) {
    return new EquiDepthHistogram(
        1,
        new Comparable<?>[]{lo, hi},
        new long[]{freq},
        new long[]{ndv},
        freq,
        null, 0);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  TIER 1: Empty index — all estimates return 0
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void emptyIndexEqualityReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateEquality(stats, null, 42), DELTA);
  }

  @Test
  public void emptyIndexRangeReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateGreaterThan(stats, null, 42), DELTA);
  }

  @Test
  public void emptyIndexIsNullReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateIsNull(stats, null), DELTA);
  }

  @Test
  public void emptyIndexInReturnsZero() {
    var stats = new IndexStatistics(0, 0, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateIn(stats, null, List.of(1, 2)), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  TIER 2: Uniform mode — no histogram, use summary counters
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void uniformEqualityReturnsOneOverDistinctCount() {
    // 1000 entries, 100 distinct → selectivity = 1/100 = 0.01
    var stats = new IndexStatistics(1000, 100, 0);
    Assert.assertEquals(0.01,
        SelectivityEstimator.estimateEquality(stats, null, 42), DELTA);
  }

  @Test
  public void uniformEqualityWithZeroDistinctReturnsZero() {
    var stats = new IndexStatistics(1000, 0, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateEquality(stats, null, 42), DELTA);
  }

  @Test
  public void uniformRangeReturnsOneThird() {
    // Standard PostgreSQL default for unbounded range
    var stats = new IndexStatistics(1000, 100, 0);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateGreaterThan(stats, null, 42), DELTA);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateLessThan(stats, null, 42), DELTA);
  }

  @Test
  public void uniformIsNullReturnsNullFraction() {
    // 1000 total, 200 null → null fraction = 200/1000 = 0.2
    var stats = new IndexStatistics(1000, 800, 200);
    Assert.assertEquals(0.2,
        SelectivityEstimator.estimateIsNull(stats, null), DELTA);
  }

  @Test
  public void uniformIsNotNullReturnsNonNullFraction() {
    var stats = new IndexStatistics(1000, 800, 200);
    Assert.assertEquals(0.8,
        SelectivityEstimator.estimateIsNotNull(stats, null), DELTA);
  }

  @Test
  public void uniformInReturnsSumOfEqualities() {
    // 1000 entries, 100 distinct, IN(3 values) → 3/100 = 0.03
    var stats = new IndexStatistics(1000, 100, 0);
    Assert.assertEquals(0.03,
        SelectivityEstimator.estimateIn(stats, null, List.of(1, 2, 3)),
        DELTA);
  }

  @Test
  public void uniformInClampsToOne() {
    // 10 entries, 5 distinct, IN(10 values) → 10/5 = 2.0 clamped to 1.0
    var stats = new IndexStatistics(10, 5, 0);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateIn(stats, null,
            List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)),
        DELTA);
  }

  @Test
  public void uniformInEmptyValuesReturnsZero() {
    var stats = new IndexStatistics(1000, 100, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateIn(stats, null, Collections.emptyList()),
        DELTA);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  TIER 3: Histogram mode — bucket-level interpolation
  // ═══════════════════════════════════════════════════════════════════

  // ── Equality ──────────────────────────────────────────────────────

  @Test
  public void histogramEqualityInMiddleBucket() {
    // 4 buckets, boundaries [0, 100, 200, 300, 400]
    // Each bucket: 250 entries, 50 distinct
    // selectivity(f = 150) → bucket 1 → (1/50) * (250/1000) = 0.005
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.005,
        SelectivityEstimator.estimateEquality(stats, h, 150), DELTA);
  }

  @Test
  public void histogramEqualityOutOfRangeBelowReturnsMinimal() {
    // Key below boundaries[0] → 1/nonNullCount
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(1.0 / 1000.0,
        SelectivityEstimator.estimateEquality(stats, h, -10), DELTA);
  }

  @Test
  public void histogramEqualityOutOfRangeAboveReturnsMinimal() {
    // Key above boundaries[bucketCount] → 1/nonNullCount
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(1.0 / 1000.0,
        SelectivityEstimator.estimateEquality(stats, h, 500), DELTA);
  }

  @Test
  public void histogramEqualityAtMinBoundaryNotOutOfRange() {
    // Key == boundaries[0] → not out-of-range, normal bucket lookup
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 0);
    // Bucket 0: (1/50) * (250/1000) = 0.005
    Assert.assertEquals(0.005, sel, DELTA);
  }

  @Test
  public void histogramEqualityAtMaxBoundaryNotOutOfRange() {
    // Key == boundaries[bucketCount] → not out-of-range
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 400);
    // Last bucket (bucket 3): (1/50) * (250/1000) = 0.005
    Assert.assertEquals(0.005, sel, DELTA);
  }

  @Test
  public void histogramEqualityEmptyBucketReturnsZero() {
    // A bucket with 0 frequency
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{0, 100},
        new long[]{0, 10},
        100, null, 0);
    var stats = new IndexStatistics(100, 10, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateEquality(stats, h, 25), DELTA);
  }

  @Test
  public void histogramEqualityZeroDistinctBucketReturnsZero() {
    // A bucket with frequency > 0 but distinctCount == 0
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{100, 100},
        new long[]{0, 10},
        200, null, 0);
    var stats = new IndexStatistics(200, 10, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateEquality(stats, h, 25), DELTA);
  }

  @Test
  public void histogramEqualityWithZeroNonNullReturnsZero() {
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{0, 0},
        new long[]{0, 0},
        0, null, 0);
    var stats = new IndexStatistics(100, 0, 100);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateEquality(stats, h, 25), DELTA);
  }

  // ── MCV short-circuit ─────────────────────────────────────────────

  @Test
  public void histogramEqualityMcvMatchUsesExactFrequency() {
    // MCV value = 42 with frequency 500 out of 1000 nonNull
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 100, 200, 300, 400},
        new long[]{250, 250, 250, 250},
        new long[]{50, 50, 50, 50},
        1000,
        42, 500);
    var stats = new IndexStatistics(1000, 200, 0);
    // MCV match → 500/1000 = 0.5
    Assert.assertEquals(0.5,
        SelectivityEstimator.estimateEquality(stats, h, 42), DELTA);
  }

  @Test
  public void histogramEqualityMcvNonMatchUsesBucketFormula() {
    // MCV value = 42, but we query for 150 → normal bucket formula
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 100, 200, 300, 400},
        new long[]{250, 250, 250, 250},
        new long[]{50, 50, 50, 50},
        1000,
        42, 500);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.005,
        SelectivityEstimator.estimateEquality(stats, h, 150), DELTA);
  }

  // ── Greater-than ──────────────────────────────────────────────────

  @Test
  public void histogramGreaterThanAtBucketMidpoint() {
    // 4 buckets: [0,100), [100,200), [200,300), [300,400]
    // Each: 250 entries. Total = 1000.
    // f > 150: bucket 1, fraction of 150 in [100,200) ~ 0.5
    // remainingInB1 = (1 - 0.5) * 250 = 125
    // buckets 2+3 = 500
    // total = 625/1000 = 0.625
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.625,
        SelectivityEstimator.estimateGreaterThan(stats, h, 150), DELTA);
  }

  @Test
  public void histogramGreaterThanAtMinBoundary() {
    // f > 0 (min boundary): fraction of 0 in bucket 0 = 0.0
    // remainingInB0 = (1 - 0) * 250 = 250, plus buckets 1-3 = 750
    // total = 1000/1000 = 1.0
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateGreaterThan(stats, h, 0), DELTA);
  }

  @Test
  public void histogramGreaterThanAtMaxBoundary() {
    // f > 400 (max boundary): last bucket, fraction = 1.0
    // remainingInLastBucket = (1 - 1) * 250 = 0, no buckets above
    // total = 0/1000 = 0.0
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateGreaterThan(stats, h, 400), DELTA);
  }

  @Test
  public void histogramGreaterThanWithZeroNonNull() {
    // Histogram with nonNullCount=0 but stats.totalCount > 0 (all nulls)
    // — reaches the histogram path, then returns 0.0 from nonNull <= 0 guard
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{0, 0},
        new long[]{0, 0},
        0, null, 0);
    var stats = new IndexStatistics(100, 0, 100);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateGreaterThan(stats, h, 25), DELTA);
  }

  // ── Less-than ─────────────────────────────────────────────────────

  @Test
  public void histogramLessThanAtBucketMidpoint() {
    // f < 150: bucket 1, fraction of 150 in [100,200) ~ 0.5
    // partialB1 = 0.5 * 250 = 125
    // bucket 0 = 250
    // total = 375/1000 = 0.375
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.375,
        SelectivityEstimator.estimateLessThan(stats, h, 150), DELTA);
  }

  @Test
  public void histogramLessThanAtMinBoundary() {
    // f < 0 (min boundary): fraction = 0 in bucket 0, nothing below
    // total = 0/1000 = 0.0
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateLessThan(stats, h, 0), DELTA);
  }

  // ── Greater-or-equal ──────────────────────────────────────────────

  @Test
  public void histogramGreaterOrEqualIncludesEqualityContribution() {
    // f >= 150: same as f > 150 + equality contribution for bucket 1
    // f > 150: 0.625 (see test above), but computed as rows / nonNull
    // eq contrib for bucket 1: (1/50) * 250 = 5.0 (rows, not selectivity)
    // GT rows: 625, eq rows: 5, total rows = 630
    // 630/1000 = 0.63
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.63,
        SelectivityEstimator.estimateGreaterOrEqual(stats, h, 150), DELTA);
  }

  // ── Less-or-equal ─────────────────────────────────────────────────

  @Test
  public void histogramLessOrEqualIncludesEqualityContribution() {
    // f <= 150: f < 150 + equality contribution
    // LT rows: 375, eq rows: 5, total = 380
    // 380/1000 = 0.38
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.38,
        SelectivityEstimator.estimateLessOrEqual(stats, h, 150), DELTA);
  }

  // ── Range ─────────────────────────────────────────────────────────

  @Test
  public void histogramRangeSameBucket() {
    // Range [120, 180] within bucket 1 [100, 200)
    // fracX of 120 in [100,200) = (120-100)/(200-100) = 0.2
    // fracY of 180 in [100,200) = (180-100)/(200-100) = 0.8
    // rangeFraction = 0.8 - 0.2 = 0.6
    // matchingRows = 0.6 * 250 = 150
    // selectivity = 150/1000 = 0.15
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.15,
        SelectivityEstimator.estimateRange(stats, h, 120, 180, true, true),
        DELTA);
  }

  @Test
  public void histogramRangeSpanningMultipleBuckets() {
    // Range [50, 250]: from bucket 0 to bucket 2
    // Lower (bucket 0): fracX = 0.5, lowerPart = (1-0.5)*250 = 125
    // Middle (bucket 1): full 250
    // Upper (bucket 2): fracY = (250-200)/(300-200) = 0.5, upperPart = 0.5*250 = 125
    // total = 125 + 250 + 125 = 500
    // selectivity = 500/1000 = 0.5
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.5,
        SelectivityEstimator.estimateRange(stats, h, 50, 250, true, true),
        DELTA);
  }

  @Test
  public void histogramRangeFullSpanReturnsOne() {
    // Range covering entire histogram [0, 400]
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateRange(stats, h, 0, 400, true, true),
        DELTA);
  }

  @Test
  public void histogramRangeWithZeroNonNull() {
    // Histogram with nonNullCount=0 but stats.totalCount > 0
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{0, 0},
        new long[]{0, 0},
        0, null, 0);
    var stats = new IndexStatistics(100, 0, 100);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateRange(stats, h, 10, 90, true, true),
        DELTA);
  }

  @Test
  public void histogramRangeExclusiveBoundsProduceSameResultAsInclusive() {
    // The inclusive/exclusive distinction is intentionally a no-op in the
    // current implementation (continuous interpolation makes the probability
    // mass at an exact point negligible). This test documents that behavior.
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 100, 200, 300, 400},
        new long[]{250, 250, 250, 250},
        new long[]{100, 100, 100, 100},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 400, 0);

    double inclusiveBoth = SelectivityEstimator.estimateRange(
        stats, h, 120, 280, true, true);
    double exclusiveBoth = SelectivityEstimator.estimateRange(
        stats, h, 120, 280, false, false);
    double exclusiveFrom = SelectivityEstimator.estimateRange(
        stats, h, 120, 280, false, true);
    double exclusiveTo = SelectivityEstimator.estimateRange(
        stats, h, 120, 280, true, false);

    Assert.assertEquals("Exclusive bounds should match inclusive (continuous)",
        inclusiveBoth, exclusiveBoth, DELTA);
    Assert.assertEquals(inclusiveBoth, exclusiveFrom, DELTA);
    Assert.assertEquals(inclusiveBoth, exclusiveTo, DELTA);
  }

  // ── IS NULL / IS NOT NULL with histogram ──────────────────────────

  @Test
  public void histogramIsNullUsesNonNullCountFromHistogram() {
    // Histogram nonNullCount = 800, stats.nullCount = 200
    // total = 800 + 200 = 1000
    // IS NULL selectivity = 200/1000 = 0.2
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{400, 400},
        new long[]{10, 10},
        800, null, 0);
    var stats = new IndexStatistics(1000, 20, 200);
    Assert.assertEquals(0.2,
        SelectivityEstimator.estimateIsNull(stats, h), DELTA);
  }

  @Test
  public void histogramIsNotNullReturnsNonNullFraction() {
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{400, 400},
        new long[]{10, 10},
        800, null, 0);
    var stats = new IndexStatistics(1000, 20, 200);
    Assert.assertEquals(0.8,
        SelectivityEstimator.estimateIsNotNull(stats, h), DELTA);
  }

  @Test
  public void isNullAllNullsReturnsOne() {
    var stats = new IndexStatistics(100, 0, 100);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateIsNull(stats, null), DELTA);
  }

  @Test
  public void isNotNullAllNullsReturnsZero() {
    var stats = new IndexStatistics(100, 0, 100);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateIsNotNull(stats, null), DELTA);
  }

  // ── IN with histogram ─────────────────────────────────────────────

  @Test
  public void histogramInSumsIndividualEqualities() {
    // 4 buckets: [0,100), [100,200), [200,300), [300,400]
    // Each: 250 entries, 50 distinct. Total = 1000.
    // IN(50, 150) → 2 × (1/50 × 250/1000) = 2 × 0.005 = 0.01
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.01,
        SelectivityEstimator.estimateIn(stats, h, List.of(50, 150)), DELTA);
  }

  @Test
  public void histogramInEmptyValuesReturnsZero() {
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateIn(stats, h, Collections.emptyList()),
        DELTA);
  }

  // ── Single-value bucket optimization ──────────────────────────────

  @Test
  public void singleValueBucketGreaterThanAboveBucketVal() {
    // Bucket 0: [0, 50), NDV=1, single value at boundaries[0]=0
    // f > 25: key 25 falls in bucket 0, value(25) > bucketVal(0)
    // → STRICT_ABOVE fraction = 1.0 → nothing in bucket 0 is > 25
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{500, 500},
        new long[]{1, 50},  // bucket 0 has NDV=1
        1000, null, 0);
    var stats = new IndexStatistics(1000, 51, 0);
    // Bucket 0: fraction = 1.0, remainingInB0 = 0
    // Bucket 1: 500
    // selectivity = 500/1000 = 0.5
    Assert.assertEquals(0.5,
        SelectivityEstimator.estimateGreaterThan(stats, h, 25), DELTA);
  }

  @Test
  public void singleValueBucketGreaterThanBelowBucketVal() {
    // f > -10: value(-10) < bucketVal(0) → fraction = 0.0 → everything above
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{500, 500},
        new long[]{1, 50},  // bucket 0 has NDV=1
        1000, null, 0);
    var stats = new IndexStatistics(1000, 51, 0);
    // Bucket 0: fraction = 0.0, remainingInB0 = 500
    // Bucket 1: 500
    // selectivity = 1000/1000 = 1.0
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateGreaterThan(stats, h, -10), DELTA);
  }

  @Test
  public void singleValueBucketGreaterThanEqualsBucketVal() {
    // f > 0: value(0) == bucketVal(0) → STRICT_ABOVE fraction = 1.0
    // Nothing in bucket 0 is strictly > bucketVal → remainingInB0 = 0
    // Bucket 1: 500
    // selectivity = 500/1000 = 0.5
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{500, 500},
        new long[]{1, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 51, 0);
    Assert.assertEquals(0.5,
        SelectivityEstimator.estimateGreaterThan(stats, h, 0), DELTA);
  }

  @Test
  public void singleValueBucketRangeFractionOf() {
    // Range within a single-value bucket: from < bucketVal → frac = 0,
    // to > bucketVal → frac = 1.0. Interpolated range covers full bucket.
    var h = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{50, 50},
        new long[]{100},
        new long[]{1},
        100, null, 0);
    var stats = new IndexStatistics(100, 1, 0);
    // Range [0, 100] spanning the single-value bucket
    // fracX = 0 (0 < 50), fracY = 1.0 (100 > 50)
    // matchingRows = (1.0 - 0.0) * 100 = 100
    // selectivity = 100/100 = 1.0
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateRange(stats, h, 0, 100, true, true),
        DELTA);
  }

  // ── Degenerate bucket (scaledLo == scaledHi) ─────────────────────

  @Test
  public void degenerateBucketUsesHalfFraction() {
    // String histogram where both boundaries are the same string
    // (can happen after boundary truncation)
    var h = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{"abc", "abc"},
        new long[]{100},
        new long[]{5},
        100, null, 0);
    var stats = new IndexStatistics(100, 5, 0);
    // f > "abc": degenerate bucket → fraction = 0.5
    // remainingInB = (1 - 0.5) * 100 = 50
    // selectivity = 50/100 = 0.5
    Assert.assertEquals(0.5,
        SelectivityEstimator.estimateGreaterThan(stats, h, "abc"), DELTA);
  }

  // ── String-based histogram interpolation ──────────────────────────

  @Test
  public void stringHistogramEqualityInRange() {
    var h = stringHistogram("aaa", "zzz", 100, 26);
    var stats = new IndexStatistics(100, 26, 0);
    // Equality: (1/26) * (100/100) = 1/26 ~ 0.03846
    Assert.assertEquals(1.0 / 26.0,
        SelectivityEstimator.estimateEquality(stats, h, "m"), DELTA);
  }

  @Test
  public void stringHistogramGreaterThanInterpolates() {
    // f > "m" with boundaries ["aaa", "zzz"]
    // Scalar conversion with prefix stripping should produce a fraction
    // and the result should be between 0 and 1
    var h = stringHistogram("aaa", "zzz", 100, 26);
    var stats = new IndexStatistics(100, 26, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, "m");
    Assert.assertTrue("selectivity should be > 0", sel > 0.0);
    Assert.assertTrue("selectivity should be < 1", sel < 1.0);
  }

  // ── Negative frequency clamping ───────────────────────────────────

  @Test
  public void negativeBucketFrequencyClampedToZero() {
    // Bucket with negative frequency (drift from incremental maintenance)
    // Should be treated as 0 in formulas
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{-10, 200},
        new long[]{5, 20},
        190, null, 0);
    var stats = new IndexStatistics(190, 25, 0);
    // f > 25 in bucket 0 with freq=-10 → max(-10,0)=0
    // Bucket 1: 200. Total = 200.
    // selectivity = 200/190 → clamped to 1.0
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateGreaterThan(stats, h, 25), DELTA);
  }

  // ── Clamp helper ──────────────────────────────────────────────────

  @Test
  public void clampBelowZeroReturnsZero() {
    Assert.assertEquals(0.0, SelectivityEstimator.clamp(-0.5), DELTA);
  }

  @Test
  public void clampAboveOneReturnsOne() {
    Assert.assertEquals(1.0, SelectivityEstimator.clamp(1.5), DELTA);
  }

  @Test
  public void clampInRangeReturnsValue() {
    Assert.assertEquals(0.42, SelectivityEstimator.clamp(0.42), DELTA);
  }

  // ── Skewed data scenario ──────────────────────────────────────────

  @Test
  public void skewedHistogramCapturesHotBucket() {
    // Simulating a skewed distribution where bucket 0 has most entries
    // Bucket 0: 900 entries, Bucket 1: 100 entries
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 10, 100},
        new long[]{900, 100},
        new long[]{5, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 55, 0);

    // Equality in hot bucket: (1/5) * (900/1000) = 0.18
    Assert.assertEquals(0.18,
        SelectivityEstimator.estimateEquality(stats, h, 5), DELTA);

    // Equality in cold bucket: (1/50) * (100/1000) = 0.002
    Assert.assertEquals(0.002,
        SelectivityEstimator.estimateEquality(stats, h, 50), DELTA);
  }

  // ── Complementary property: GT + LT + EQ ~ 1.0 ───────────────────

  @Test
  public void greaterThanPlusLessThanPlusEqualityApproximatesOne() {
    // For a value in the middle of the histogram, the three selectivities
    // should roughly sum to 1.0 (may not be exact due to interpolation)
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);

    double gt = SelectivityEstimator.estimateGreaterThan(stats, h, 200);
    double lt = SelectivityEstimator.estimateLessThan(stats, h, 200);
    double eq = SelectivityEstimator.estimateEquality(stats, h, 200);

    // These should approximately sum to 1.0
    Assert.assertEquals(1.0, gt + lt + eq, 0.05);
  }

  // ── Uniform mode for range estimators with histogram ──────────────

  @Test
  public void uniformGreaterOrEqualReturnsOneThird() {
    var stats = new IndexStatistics(1000, 100, 0);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateGreaterOrEqual(stats, null, 42), DELTA);
  }

  @Test
  public void uniformLessOrEqualReturnsOneThird() {
    var stats = new IndexStatistics(1000, 100, 0);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateLessOrEqual(stats, null, 42), DELTA);
  }

  // ── Edge: single-bucket histogram ─────────────────────────────────

  @Test
  public void singleBucketHistogramEqualityCoversAll() {
    // One bucket: [0, 100], 100 entries, 10 distinct
    var h = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{0, 100},
        new long[]{100},
        new long[]{10},
        100, null, 0);
    var stats = new IndexStatistics(100, 10, 0);
    // Equality: (1/10) * (100/100) = 0.1
    Assert.assertEquals(0.1,
        SelectivityEstimator.estimateEquality(stats, h, 50), DELTA);
  }

  @Test
  public void singleBucketHistogramGreaterThanMidpoint() {
    var h = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{0, 100},
        new long[]{100},
        new long[]{10},
        100, null, 0);
    var stats = new IndexStatistics(100, 10, 0);
    // f > 50: fraction = 0.5, remaining = 50, sel = 50/100 = 0.5
    Assert.assertEquals(0.5,
        SelectivityEstimator.estimateGreaterThan(stats, h, 50), DELTA);
  }

  // ── Edge: empty histogram (all values null) ───────────────────────

  @Test
  public void allNullsHistogramEqualityReturnsZero() {
    var stats = new IndexStatistics(500, 0, 500);
    // No histogram, totalCount > 0, distinctCount = 0
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateEquality(stats, null, 42), DELTA);
  }

  // ── Additional boundary tests for LT ──────────────────────────────

  @Test
  public void histogramLessThanAtMaxBoundary() {
    // f < 400 (max boundary): last bucket, STRICT_BELOW fraction = 1.0
    // partialB3 = 1.0 * 250 = 250, below buckets 0-2 = 750
    // selectivity = 1000/1000 = 1.0
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateLessThan(stats, h, 400), DELTA);
  }

  // ── Additional boundary tests for GTE/LTE ─────────────────────────

  @Test
  public void histogramGreaterOrEqualAtMinBoundary() {
    // f >= 0 (min boundary): everything matches
    // STRICT_ABOVE fraction = 0.0 (0 < bucketVal? no, 0 == boundaries[0])
    // For non-single-value bucket: scalar interp fraction = 0.0
    // remainingInB0 = 250, buckets 1-3 = 750, eq = (1/50)*250 = 5
    // total = 1005/1000 → clamped to 1.0
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateGreaterOrEqual(stats, h, 0), DELTA);
  }

  @Test
  public void histogramGreaterOrEqualAtMaxBoundary() {
    // f >= 400 (max boundary): only equality contribution for last bucket
    // STRICT_ABOVE fraction = 1.0, remainingInB3 = 0
    // eq = (1/50) * 250 = 5
    // total = 5/1000 = 0.005
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.005,
        SelectivityEstimator.estimateGreaterOrEqual(stats, h, 400), DELTA);
  }

  @Test
  public void histogramLessOrEqualAtMinBoundary() {
    // f <= 0 (min boundary): only equality contribution for first bucket
    // STRICT_BELOW fraction = 0.0, partialB0 = 0
    // eq = (1/50) * 250 = 5
    // total = 5/1000 = 0.005
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.005,
        SelectivityEstimator.estimateLessOrEqual(stats, h, 0), DELTA);
  }

  @Test
  public void histogramLessOrEqualAtMaxBoundary() {
    // f <= 400 (max boundary): everything matches
    var h = uniform(4, 100, 250, 50);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateLessOrEqual(stats, h, 400), DELTA);
  }

  // ── Single-value bucket with GTE/LTE ──────────────────────────────

  @Test
  public void singleValueBucketGreaterOrEqualEqualsBucketVal() {
    // Single-value bucket 0 with all entries at value 0.
    // f >= 0: STRICT_ABOVE fraction = 1.0, remainingInB0 = 0
    // eq = (1/1) * 500 = 500. Bucket 1: 500.
    // total = (0 + 500 + 500)/1000 = 1.0
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{500, 500},
        new long[]{1, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 51, 0);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateGreaterOrEqual(stats, h, 0), DELTA);
  }

  @Test
  public void singleValueBucketLessOrEqualEqualsBucketVal() {
    // f <= 0: STRICT_BELOW fraction = 0.0, partialB0 = 0
    // eq = (1/1) * 500 = 500
    // total = 500/1000 = 0.5
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{500, 500},
        new long[]{1, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 51, 0);
    Assert.assertEquals(0.5,
        SelectivityEstimator.estimateLessOrEqual(stats, h, 0), DELTA);
  }

  @Test
  public void singleValueBucketLessThanEqualsBucketVal() {
    // f < 0: STRICT_BELOW fraction = 0.0, partialB0 = 0
    // Nothing below bucket 0 either.
    // selectivity = 0/1000 = 0.0
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{500, 500},
        new long[]{1, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 51, 0);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateLessThan(stats, h, 0), DELTA);
  }

  // ── Zero nonNullCount in histogram (all nulls) ─────────────────────

  @Test
  public void histogramLessThanWithZeroNonNullReturnsZero() {
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{0, 0},
        new long[]{0, 0},
        0, null, 0);
    var stats = new IndexStatistics(100, 0, 100);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateLessThan(stats, h, 25), DELTA);
  }

  @Test
  public void histogramGreaterOrEqualWithZeroNonNullReturnsZero() {
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{0, 0},
        new long[]{0, 0},
        0, null, 0);
    var stats = new IndexStatistics(100, 0, 100);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateGreaterOrEqual(stats, h, 25), DELTA);
  }

  @Test
  public void histogramLessOrEqualWithZeroNonNullReturnsZero() {
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{0, 0},
        new long[]{0, 0},
        0, null, 0);
    var stats = new IndexStatistics(100, 0, 100);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateLessOrEqual(stats, h, 25), DELTA);
  }

  // ── IN with MCV ───────────────────────────────────────────────────

  @Test
  public void histogramInWithMcvValueTriggersMcvShortCircuit() {
    // MCV = 42 with freq 500. IN(42, 150).
    // 42 → MCV match → 500/1000 = 0.5
    // 150 → bucket formula → (1/50)*(250/1000) = 0.005
    // total = 0.505
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 100, 200, 300, 400},
        new long[]{250, 250, 250, 250},
        new long[]{50, 50, 50, 50},
        1000,
        42, 500);
    var stats = new IndexStatistics(1000, 200, 0);
    Assert.assertEquals(0.505,
        SelectivityEstimator.estimateIn(stats, h, List.of(42, 150)),
        DELTA);
  }
}
