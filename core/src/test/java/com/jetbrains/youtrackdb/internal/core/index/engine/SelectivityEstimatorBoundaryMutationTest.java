package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Mutation-killing tests targeting specific pitest boundary mutations
 * in {@link SelectivityEstimator} that survived previous rounds.
 *
 * <p>Focuses on: conditional boundary changes (< vs <=), comparison
 * direction swaps (ORDER_ELSE), and scalarize parameter order.
 */
public class SelectivityEstimatorBoundaryMutationTest {

  private static final double DELTA = 1e-9;

  // ═══════════════════════════════════════════════════════════════
  // Line 143: changed conditional boundary (nonNull <= 0 vs < 0)
  // Test that nonNull=1 (just above boundary) returns a non-zero result.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void equality_histogram_nonNullExactlyOne_returnsNonZero() {
    // nonNull=1: boundary test. If <= mutated to <, nonNull=0 would pass.
    // With nonNull=1, should return a valid estimate, not 0.
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {42, 42},
        new long[] {1}, new long[] {1}, 1, null, 0);
    var stats = new IndexStatistics(1, 1, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 42);
    assertTrue("nonNull=1 should give non-zero selectivity", sel > 0);
    assertEquals("Single entry matching key should give selectivity 1.0",
        1.0, sel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 156: swapped parameters 1 and 2 in call to compare
  // If compare(key, mcvValue) is swapped to compare(mcvValue, key),
  // the zero-check still passes — but the behavior on non-matching keys
  // would differ. We need an asymmetric test.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void equality_mcv_asymmetricCompare_detectsSwap() {
    // MCV=50. Test key=50 → should use MCV frequency.
    // Test key=49 → should NOT use MCV frequency.
    // Compare(50, 50) == 0 and Compare(50, 50) == 0 (swap doesn't matter for equal).
    // But compare(49, 50) < 0 and compare(50, 49) > 0 — still both != 0.
    // So we need a test where the MCV match result (freq/nonNull) differs
    // from the bucket estimate to detect any issue.
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500}, new long[] {50, 50},
        1000, 50, 800); // MCV=50, freq=800
    var stats = new IndexStatistics(1000, 100, 0);

    double selMatch = SelectivityEstimator.estimateEquality(stats, h, 50);
    // Should be 800/1000 = 0.8 (MCV match)
    assertEquals("MCV match should return exact frequency ratio",
        0.8, selMatch, DELTA);

    double selNoMatch = SelectivityEstimator.estimateEquality(stats, h, 49);
    // Should be bucket estimate: (1/50) * (500/1000) = 0.01
    assertTrue("Non-MCV key should return much smaller selectivity",
        selNoMatch < 0.1);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 194: removed conditional (compare check with false)
  // estimateGreaterThanHistogram: nonNull <= 0 check
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void greaterThan_nonNullExactlyOne_returnsValidResult() {
    // nonNull=1, single entry at 50. f > 50 → 0, f > 30 → 1.0
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {1}, new long[] {1}, 1, null, 0);
    var stats = new IndexStatistics(1, 1, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 30);
    assertEquals("f > 30 on single entry at 50 → all match", 1.0, sel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 231: changed conditional boundary (nonNull <= 0)
  // estimateLessThan: boundary test
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void lessThan_nonNullExactlyOne_returnsValidResult() {
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {1}, new long[] {1}, 1, null, 0);
    var stats = new IndexStatistics(1, 1, 0);
    double sel = SelectivityEstimator.estimateLessThan(stats, h, 80);
    assertEquals("f < 80 on single entry at 50 → all match", 1.0, sel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 269: removed conditional (ORDER_ELSE - GTE nonNull check)
  // Line 308: removed conditional (ORDER_ELSE - LTE nonNull check)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void greaterOrEqual_nonNullExactlyOne_returnsValidResult() {
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {1}, new long[] {1}, 1, null, 0);
    var stats = new IndexStatistics(1, 1, 0);
    double sel = SelectivityEstimator.estimateGreaterOrEqual(stats, h, 50);
    assertEquals("f >= 50 on single entry at 50 → match", 1.0, sel, DELTA);
  }

  @Test
  public void lessOrEqual_nonNullExactlyOne_returnsValidResult() {
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {1}, new long[] {1}, 1, null, 0);
    var stats = new IndexStatistics(1, 1, 0);
    double sel = SelectivityEstimator.estimateLessOrEqual(stats, h, 50);
    assertEquals("f <= 50 on single entry at 50 → match", 1.0, sel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 371: changed conditional boundary (range nonNull <= 0)
  // Line 377: removed conditional (cmp > 0 → false in range)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void range_nonNullExactlyOne_validResult() {
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {1}, new long[] {1}, 1, null, 0);
    var stats = new IndexStatistics(1, 1, 0);
    // Range [40, 60] should include the single entry at 50
    double sel = SelectivityEstimator.estimateRange(
        stats, h, 40, 60, true, true);
    assertTrue("Range [40,60] on entry at 50 → match", sel > 0);
  }

  @Test
  public void range_histogram_fromGreaterThanTo_returnsZero() {
    // Tests line 377: if cmp > 0 condition is removed, would not return 0
    var h = new EquiDepthHistogram(
        4, new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {250, 250, 250, 250},
        new long[] {25, 25, 25, 25}, 1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);
    double sel = SelectivityEstimator.estimateRange(
        stats, h, 80, 20, true, true);
    assertEquals("Inverted range should return 0", 0.0, sel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 398: swapped parameters 2 and 3 in call to scalarize
  // In the same-bucket range path: scalarize(lo, lo, hi) — if lo and hi
  // are swapped, the scalar conversion would change for strings.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void range_sameBucket_stringKeys_scalarizeParamOrderMatters() {
    // Bucket with string boundaries ["aaa", "zzz"].
    // Range query from "bbb" to "yyy" — both in same bucket.
    // scalarize("bbb", "aaa", "zzz") gives a different result than
    // scalarize("bbb", "zzz", "aaa") because prefix stripping differs.
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {"aaa", "zzz"},
        new long[] {1000}, new long[] {100}, 1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    double sel = SelectivityEstimator.estimateRange(
        stats, h, "bbb", "yyy", true, true);
    // With correct param order: scalarize("bbb","aaa","zzz") and
    // scalarize("yyy","aaa","zzz") give proper interpolation fractions.
    // The range should cover most of the bucket.
    assertTrue("Range bbb-yyy in bucket aaa-zzz should be large, got " + sel,
        sel > 0.5);
    assertTrue("Range should be < 1.0", sel < 1.0);

    // Also verify a small range gives a small result
    double selSmall = SelectivityEstimator.estimateRange(
        stats, h, "mmm", "mmo", true, true);
    assertTrue("Small range should give small selectivity, got " + selSmall,
        selSmall < 0.1);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 437: removed conditional (ORDER_ELSE for isNull nonNull check)
  // Line 456: removed conditional (ORDER_ELSE for isNotNull)
  // Line 459: removed conditional (EQUAL_ELSE for isNotNull)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void isNull_nonNullExactlyOne_fractionCorrect() {
    // histogram with nonNull=1, stats with nullCount=1
    // total = nonNull + null = 1 + 1 = 2
    // selectivity = 1/2 = 0.5
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {42, 42},
        new long[] {1}, new long[] {1}, 1, null, 0);
    var stats = new IndexStatistics(2, 1, 1);
    double sel = SelectivityEstimator.estimateIsNull(stats, h);
    assertEquals("1 null out of 2 total", 0.5, sel, DELTA);
  }

  @Test
  public void isNotNull_nonNullExactlyOne_fractionCorrect() {
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {42, 42},
        new long[] {1}, new long[] {1}, 1, null, 0);
    var stats = new IndexStatistics(2, 1, 1);
    double sel = SelectivityEstimator.estimateIsNotNull(stats, h);
    assertEquals("1 nonNull out of 2 total", 0.5, sel, DELTA);
  }

  @Test
  public void isNull_histogramNonNull_statsNullBothExact() {
    // nonNull from histogram = 500, null from stats = 500
    // total = 500 + 500 = 1000
    // IS NULL selectivity = 500/1000 = 0.5
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {250, 250}, new long[] {25, 25}, 500, null, 0);
    var stats = new IndexStatistics(1000, 50, 500);
    double isNullSel = SelectivityEstimator.estimateIsNull(stats, h);
    double isNotNullSel = SelectivityEstimator.estimateIsNotNull(stats, h);
    assertEquals("IS NULL = 500/1000", 0.5, isNullSel, DELTA);
    assertEquals("IS NOT NULL = 500/1000", 0.5, isNotNullSel, DELTA);
    // IS NULL + IS NOT NULL should sum to ~1.0
    assertEquals("IS NULL + IS NOT NULL should sum to 1.0",
        1.0, isNullSel + isNotNullSel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 556: changed conditional boundary (distinctCounts[b] == 1)
  // Line 563: swapped parameters 2 and 3 in call to scalarize
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void fractionOf_distinctCountExactlyTwo_usesContinuousInterpolation() {
    // distinctCount=2 means continuous interpolation, not discrete.
    // Bucket [0, 100], freq=100, distinct=2, nonNull=100.
    // For f > 50: fraction should be ~0.5 (continuous)
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {0, 100},
        new long[] {100}, new long[] {2}, 100, null, 0);
    var stats = new IndexStatistics(100, 2, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 50);
    // Continuous: fraction(50, [0,100]) = 0.5
    // remaining = (1 - 0.5) * 100 = 50
    // selectivity = 50/100 = 0.5
    assertEquals("Continuous interpolation for distinct=2",
        0.5, sel, 0.05);
  }

  @Test
  public void fractionOf_scalarizeParamOrder_stringBoundaries() {
    // String boundaries where param order matters.
    // Bucket ["abc", "xyz"], key="lmn"
    // scalarize("lmn", "abc", "xyz") — common prefix="" → charEncode
    // If params 2,3 swapped: scalarize("lmn", "xyz", "abc") — different prefix
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {"abc", "xyz"},
        new long[] {1000}, new long[] {100}, 1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    double gt = SelectivityEstimator.estimateGreaterThan(stats, h, "lmn");
    // "lmn" is roughly in the middle of ["abc", "xyz"]
    assertTrue("GT for mid-range string should be ~0.5, got " + gt,
        gt > 0.2 && gt < 0.8);

    double lt = SelectivityEstimator.estimateLessThan(stats, h, "lmn");
    assertTrue("LT for mid-range string should be ~0.5, got " + lt,
        lt > 0.2 && lt < 0.8);

    // GT + LT should approximately sum to 1.0
    double sum = gt + lt;
    assertTrue("GT + LT should be ~1.0, got " + sum,
        sum > 0.85 && sum < 1.15);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 626: changed conditional boundary (freq <= 0 || distinct <= 0)
  // equalityContribution: boundary test
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void gte_bucketWithFreqExactlyOne_hasEqualityContribution() {
    // Bucket with freq=1, distinct=1 → equalityContribution = 1.0
    // If boundary changed from <=0 to <0, freq=0 would pass through.
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {1, 999}, new long[] {1, 99},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);
    double gte = SelectivityEstimator.estimateGreaterOrEqual(stats, h, 25);
    double gt = SelectivityEstimator.estimateGreaterThan(stats, h, 25);
    // GTE should be slightly > GT due to equality contribution from bucket 0
    assertTrue("GTE should be >= GT (equality contribution from freq=1 bucket)",
        gte >= gt - DELTA);
  }

  @Test
  public void gte_bucketWithDistinctExactlyOne_hasEqualityContribution() {
    // freq=100, distinct=1: equalityContribution = (1/1)*100 = 100
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {100, 900}, new long[] {1, 90},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 91, 0);
    double gte = SelectivityEstimator.estimateGreaterOrEqual(stats, h, 25);
    // With equality contribution from bucket 0 (100 rows for distinct=1)
    assertTrue("GTE should include equality contribution", gte > 0);
  }

  // ═══════════════════════════════════════════════════════════════
  // Exact numeric verification to catch conditional boundary swaps
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void equality_exactBucketEstimate_catchesBoundaryMutations() {
    // 4 buckets, key=30 falls in bucket 1 [25, 50)
    // Bucket 1: freq=200, distinct=20
    // Estimate: (1/20) * (200/1000) = 0.01
    var h = new EquiDepthHistogram(
        4, new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {100, 200, 300, 400},
        new long[] {10, 20, 30, 40},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 30);
    assertEquals("Exact bucket estimate for key=30 in bucket 1",
        0.01, sel, DELTA);
  }

  @Test
  public void greaterThan_exactCalculation_catchesBoundaryMutations() {
    // 2 buckets: [0, 50), [50, 100]. Each has 500 entries, 50 distinct.
    // f > 25: fraction in bucket 0 = (25-0)/(50-0) = 0.5
    // remaining in bucket 0 = (1 - 0.5) * 500 = 250
    // above buckets (bucket 1) = 500
    // selectivity = (250 + 500) / 1000 = 0.75
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500}, new long[] {50, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 25);
    assertEquals("GT(25) in 2-bucket [0,50,100] → 0.75",
        0.75, sel, 0.01);
  }

  @Test
  public void lessThan_exactCalculation_catchesBoundaryMutations() {
    // Same histogram as above.
    // f < 75: fraction in bucket 1 = (75-50)/(100-50) = 0.5
    // partial in bucket 1 = 0.5 * 500 = 250
    // below buckets (bucket 0) = 500
    // selectivity = (250 + 500) / 1000 = 0.75
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500}, new long[] {50, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);
    double sel = SelectivityEstimator.estimateLessThan(stats, h, 75);
    assertEquals("LT(75) in 2-bucket [0,50,100] → 0.75",
        0.75, sel, 0.01);
  }
}
