package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Mutation-killing tests for {@link SelectivityEstimator}.
 *
 * <p>Each test targets specific survived mutations identified by pitest,
 * asserting on exact numeric values and boundary conditions that would
 * change if the mutation were applied.
 */
public class SelectivityEstimatorMutationTest {

  private static final double DELTA = 1e-9;

  // ── Helper: create a histogram with known values ──────────────

  /**
   * Creates a 4-bucket histogram with boundaries [0, 25, 50, 75, 100],
   * each bucket with given freq and distinct count, and specified MCV.
   */
  private static EquiDepthHistogram hist4(
      long[] freqs, long[] distincts, long nonNull,
      Comparable<?> mcv, long mcvFreq) {
    return new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25, 50, 75, 100},
        freqs, distincts, nonNull, mcv, mcvFreq);
  }

  /**
   * Creates a uniform 4-bucket histogram: 250 entries per bucket,
   * 25 distinct per bucket, 1000 total non-null.
   */
  private static EquiDepthHistogram uniformHist4() {
    return hist4(
        new long[] {250, 250, 250, 250},
        new long[] {25, 25, 25, 25},
        1000, null, 0);
  }

  private static IndexStatistics stats(long total, long distinct, long nulls) {
    return new IndexStatistics(total, distinct, nulls);
  }

  // ═══════════════════════════════════════════════════════════════
  // Equality: boundary mutations at line 131 (<=0 boundary), 143 (<=0)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void equality_uniformDistinctCountExactlyOne_returnsOne() {
    // distinctCount=1: 1/1 = 1.0. Boundary mutation <=0 vs <0 would
    // return 0.0 for distinctCount=0, so test with distinctCount=1.
    var stats = stats(100, 1, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, null, 42);
    assertEquals("1/distinctCount = 1.0", 1.0, sel, DELTA);
  }

  @Test
  public void equality_uniformDistinctCountZero_returnsZero() {
    // distinctCount=0: boundary check (<=0) returns 0.0
    var stats = stats(100, 0, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, null, 42);
    assertEquals(0.0, sel, DELTA);
  }

  @Test
  public void equality_uniformAllNulls_returnsZero() {
    // totalCount=100, nullCount=100 → nonNull=0 → returns 0.0
    // Tests line 131 boundary: nonNull <= 0
    var stats = stats(100, 10, 100);
    double sel = SelectivityEstimator.estimateEquality(stats, null, 42);
    assertEquals(0.0, sel, DELTA);
  }

  @Test
  public void equality_histogramNonNullZero_returnsZero() {
    // histogram with nonNullCount=0 → returns 0.0
    // Tests line 143 boundary
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {0, 0}, new long[] {0, 0}, 0, null, 0);
    var stats = stats(100, 10, 100);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 42);
    assertEquals(0.0, sel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Equality: MCV comparison swap (line 156), param order matters
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void equality_mcvMatch_usesExactFrequency() {
    // MCV=50, freq=100, nonNull=1000 → selectivity = 100/1000 = 0.1
    // Swapping compare params would break this.
    var h = hist4(
        new long[] {250, 250, 250, 250},
        new long[] {25, 25, 25, 25},
        1000, 50, 100);
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 50);
    assertEquals("MCV match should use exact frequency",
        0.1, sel, DELTA);
  }

  @Test
  public void equality_mcvNoMatch_usesBucketEstimate() {
    // key=30, MCV=50 → should NOT use MCV frequency
    var h = hist4(
        new long[] {250, 250, 250, 250},
        new long[] {25, 25, 25, 25},
        1000, 50, 500);
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 30);
    // Bucket estimate: (1/25) * (250/1000) = 0.01
    assertEquals(0.01, sel, DELTA);
    // If MCV were incorrectly matched, result would be 500/1000=0.5
    assertTrue("Should not use MCV for non-matching key", sel < 0.5);
  }

  // ═══════════════════════════════════════════════════════════════
  // Out-of-range: key below min and above max
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void equality_keyBelowMin_returnsMinimal() {
    // key=-10 < boundaries[0]=0 → 1/nonNull = 1/1000
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, -10);
    assertEquals(1.0 / 1000, sel, DELTA);
  }

  @Test
  public void equality_keyAboveMax_returnsMinimal() {
    // key=200 > boundaries[4]=100 → 1/nonNull = 1/1000
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 200);
    assertEquals(1.0 / 1000, sel, DELTA);
  }

  @Test
  public void equality_keyAtExactMin_doesNotShortCircuit() {
    // key=0 == boundaries[0] → NOT out of range, should use bucket estimate
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 0);
    // Should be bucket estimate, not 1/nonNull
    assertNotEquals("Key at min should use bucket estimate, not out-of-range",
        1.0 / 1000, sel, DELTA);
  }

  @Test
  public void equality_keyAtExactMax_doesNotShortCircuit() {
    // key=100 == boundaries[4] → NOT out of range (compare > 0 fails)
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, h, 100);
    assertNotEquals("Key at max should use bucket estimate, not out-of-range",
        1.0 / 1000, sel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Greater-than: boundary at line 194 (comparison direction)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void greaterThan_atMinBoundary_returnsAlmostAll() {
    // f > 0 (min boundary) → nearly all entries match
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 0);
    // Most of bucket 0 + all of buckets 1-3
    assertTrue("f > min should return high selectivity", sel > 0.7);
  }

  @Test
  public void greaterThan_atMaxBoundary_returnsZeroish() {
    // f > 100 (max boundary) → nothing matches
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 100);
    assertTrue("f > max should return ~0", sel < 0.05);
  }

  @Test
  public void greaterThan_atMidpoint_returnsAboutHalf() {
    // f > 50 → about half of entries
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 50);
    // Should be approximately 0.5
    assertTrue("f > midpoint ~0.5", sel > 0.3 && sel < 0.7);
  }

  // ═══════════════════════════════════════════════════════════════
  // Less-than: boundary at line 231 (<=0 boundary on nonNull)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void lessThan_histogramNonNullZero_returnsZero() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {0, 0}, new long[] {0, 0}, 0, null, 0);
    var stats = stats(100, 10, 100);
    double sel = SelectivityEstimator.estimateLessThan(stats, h, 42);
    assertEquals(0.0, sel, DELTA);
  }

  @Test
  public void lessThan_atMaxBoundary_returnsAlmostAll() {
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateLessThan(stats, h, 100);
    assertTrue("f < max should return high selectivity", sel > 0.7);
  }

  @Test
  public void lessThan_atMinBoundary_returnsZeroish() {
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateLessThan(stats, h, 0);
    assertTrue("f < min should return ~0", sel < 0.05);
  }

  // ═══════════════════════════════════════════════════════════════
  // GTE/LTE: boundary mutations at lines 269, 308
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void greaterOrEqual_includesEqualityContribution() {
    // f >= 50: should be GT(50) + equality contribution for bucket containing 50
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double gte = SelectivityEstimator.estimateGreaterOrEqual(stats, h, 50);
    double gt = SelectivityEstimator.estimateGreaterThan(stats, h, 50);
    assertTrue("GTE should be >= GT due to equality contribution",
        gte >= gt - DELTA);
  }

  @Test
  public void lessOrEqual_includesEqualityContribution() {
    // f <= 50: should be LT(50) + equality contribution
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double lte = SelectivityEstimator.estimateLessOrEqual(stats, h, 50);
    double lt = SelectivityEstimator.estimateLessThan(stats, h, 50);
    assertTrue("LTE should be >= LT due to equality contribution",
        lte >= lt - DELTA);
  }

  @Test
  public void greaterOrEqual_nonNullZero_returnsZero() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {0, 0}, new long[] {0, 0}, 0, null, 0);
    var stats = stats(100, 10, 100);
    assertEquals(0.0,
        SelectivityEstimator.estimateGreaterOrEqual(stats, h, 50), DELTA);
  }

  @Test
  public void lessOrEqual_nonNullZero_returnsZero() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {0, 0}, new long[] {0, 0}, 0, null, 0);
    var stats = stats(100, 10, 100);
    assertEquals(0.0,
        SelectivityEstimator.estimateLessOrEqual(stats, h, 50), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Range: boundary mutations at lines 371, 377
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void range_invertedBounds_returnsZero() {
    // fromKey > toKey → empty range
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateRange(
        stats, h, 80, 20, true, true);
    assertEquals(0.0, sel, DELTA);
  }

  @Test
  public void range_degenerateEqualBounds_delegatesToEquality() {
    // BETWEEN 50 AND 50 → same as equality for 50
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double range = SelectivityEstimator.estimateRange(
        stats, h, 50, 50, true, true);
    double eq = SelectivityEstimator.estimateEquality(stats, h, 50);
    assertEquals("BETWEEN X AND X should equal equality",
        eq, range, DELTA);
  }

  @Test
  public void range_equalBoundsOneExclusive_returnsZero() {
    // (50, 50] → empty range
    var stats = stats(1000, 100, 0);
    var h = uniformHist4();
    assertEquals(0.0, SelectivityEstimator.estimateRange(
        stats, h, 50, 50, false, true), DELTA);
    assertEquals(0.0, SelectivityEstimator.estimateRange(
        stats, h, 50, 50, true, false), DELTA);
    assertEquals(0.0, SelectivityEstimator.estimateRange(
        stats, h, 50, 50, false, false), DELTA);
  }

  @Test
  public void range_sameBucket_interpolatesSubRange() {
    // Range entirely within bucket 1 [25, 50): from=30, to=40
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateRange(
        stats, h, 30, 40, true, true);
    // ~40% of bucket 1 (10/25 of bucket range), bucket has 250/1000
    assertTrue("Intra-bucket range should be small fraction", sel > 0.0);
    assertTrue("Intra-bucket range should be < full bucket", sel < 0.25);
  }

  @Test
  public void range_crossBucket_sumsCorrectly() {
    // Range from bucket 0 to bucket 2: from=10, to=60
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateRange(
        stats, h, 10, 60, true, true);
    assertTrue("Cross-bucket range should be substantial", sel > 0.3);
    assertTrue("Cross-bucket range should be < 1.0", sel < 0.8);
  }

  @Test
  public void range_histogramNonNullZero_returnsZero() {
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {0, 0}, new long[] {0, 0}, 0, null, 0);
    var stats = stats(100, 10, 100);
    assertEquals(0.0, SelectivityEstimator.estimateRange(
        stats, h, 10, 90, true, true), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // IS NULL / IS NOT NULL: boundary mutations at lines 437, 456, 459
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void isNull_withHistogram_usesComputeTotal() {
    // stats: total=1200, nullCount=200
    // histogram: nonNull=1000
    // computeTotal = 1000 + 200 = 1200
    // selectivity = 200/1200
    var h = uniformHist4(); // nonNull=1000
    var stats = stats(1200, 800, 200);
    double sel = SelectivityEstimator.estimateIsNull(stats, h);
    assertEquals(200.0 / 1200, sel, DELTA);
  }

  @Test
  public void isNotNull_withHistogram_usesHistogramNonNull() {
    // nonNull from histogram = 1000, null from stats = 200
    // total = 1000 + 200 = 1200
    // selectivity = 1000 / 1200
    var h = uniformHist4();
    var stats = stats(1200, 800, 200);
    double sel = SelectivityEstimator.estimateIsNotNull(stats, h);
    assertEquals(1000.0 / 1200, sel, DELTA);
  }

  @Test
  public void isNotNull_withoutHistogram_usesStatsDirectly() {
    // Without histogram: nonNull = totalCount - nullCount = 1000 - 200 = 800
    // total = stats.totalCount() = 1000
    // selectivity = 800 / 1000
    var stats = stats(1000, 800, 200);
    double sel = SelectivityEstimator.estimateIsNotNull(stats, null);
    assertEquals(0.8, sel, DELTA);
  }

  @Test
  public void isNull_totalZero_returnsZero() {
    var stats = stats(0, 0, 0);
    assertEquals(0.0, SelectivityEstimator.estimateIsNull(stats, null), DELTA);
  }

  @Test
  public void isNotNull_totalZero_returnsZero() {
    var stats = stats(0, 0, 0);
    assertEquals(0.0, SelectivityEstimator.estimateIsNotNull(stats, null), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // IN: boundary at line 496 (distinctCount <= 0)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void in_uniformDistinctZero_returnsZero() {
    var stats = stats(100, 0, 0);
    double sel = SelectivityEstimator.estimateIn(
        stats, null, List.of(1, 2, 3));
    assertEquals(0.0, sel, DELTA);
  }

  @Test
  public void in_uniformNonZeroDistinct_returnsNOverD() {
    // n=3, distinctCount=100 → 3/100 = 0.03
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateIn(
        stats, null, List.of(1, 2, 3));
    assertEquals(0.03, sel, DELTA);
  }

  @Test
  public void in_histogramMode_sumsEqualityEstimates() {
    // IN with histogram: sum of individual equality estimates
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double inSel = SelectivityEstimator.estimateIn(
        stats, h, List.of(10, 30, 60));
    double sum = SelectivityEstimator.estimateEquality(stats, h, 10)
        + SelectivityEstimator.estimateEquality(stats, h, 30)
        + SelectivityEstimator.estimateEquality(stats, h, 60);
    assertEquals("IN should be sum of individual equalities",
        sum, inSel, DELTA);
  }

  @Test
  public void in_emptyValues_returnsZero() {
    var stats = stats(1000, 100, 0);
    assertEquals(0.0, SelectivityEstimator.estimateIn(
        stats, null, Collections.emptyList()), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // fractionOf: single-value bucket + scalarize param swap (lines 556, 563)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void singleValueBucket_greaterThan_returnsFractionOne() {
    // Single-value bucket: distinctCount=1, all entries at boundary value.
    // f > bucketVal → fraction=1.0 → remainingInB = 0
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {100}, new long[] {1}, 100, null, 0);
    var stats = stats(100, 1, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 50);
    // All 100 entries are at 50, f > 50 → 0 entries match
    assertEquals(0.0, sel, DELTA);
  }

  @Test
  public void singleValueBucket_lessThan_returnsFractionZero() {
    // f < 50 on single-value bucket [50,50] → fraction=0 → partialB=0
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {100}, new long[] {1}, 100, null, 0);
    var stats = stats(100, 1, 0);
    double sel = SelectivityEstimator.estimateLessThan(stats, h, 50);
    assertEquals(0.0, sel, DELTA);
  }

  @Test
  public void singleValueBucket_valueBelowBucket_greaterThan() {
    // f > 30 on single-value bucket at 50 → all 100 entries > 30
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {100}, new long[] {1}, 100, null, 0);
    var stats = stats(100, 1, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 30);
    // fraction(30, bucket at 50, STRICT_ABOVE) = 0.0 (value below bucket)
    // remainingInB = (1-0) * 100 = 100
    assertEquals(1.0, sel, DELTA);
  }

  @Test
  public void singleValueBucket_valueAboveBucket_lessThan() {
    // f < 80 on single-value bucket at 50 → all 100 entries < 80
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {50, 50},
        new long[] {100}, new long[] {1}, 100, null, 0);
    var stats = stats(100, 1, 0);
    double sel = SelectivityEstimator.estimateLessThan(stats, h, 80);
    // fraction(80, bucket at 50, STRICT_BELOW) = 1.0 (value above bucket)
    // partialB = 1.0 * 100 = 100
    assertEquals(1.0, sel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // equalityContribution: boundary at line 626 (<=0 guard)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void gte_bucketWithZeroFrequency_noEqualityContribution() {
    // Bucket with freq=0, distinct=0 → equalityContribution=0
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {0, 1000}, new long[] {0, 100},
        1000, null, 0);
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateGreaterOrEqual(stats, h, 10);
    // Bucket 0 has freq=0, so equalityContribution for bucket 0 = 0
    assertTrue("Result should come from bucket 1 only", sel > 0.0);
  }

  @Test
  public void gte_bucketWithZeroDistinct_noEqualityContribution() {
    // Bucket with freq>0 but distinct=0 → equalityContribution=0
    var h = new EquiDepthHistogram(
        2, new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500}, new long[] {0, 50},
        1000, null, 0);
    var stats = stats(1000, 50, 0);
    double sel = SelectivityEstimator.estimateGreaterOrEqual(stats, h, 10);
    assertTrue("Should still produce a result", sel > 0.0);
  }

  // ═══════════════════════════════════════════════════════════════
  // NE operator: line 695 (subtraction mutation)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void ne_returnsOneMinusEquality() {
    // NE: 1.0 - equality. If subtraction is mutated to addition,
    // result would be > 1.0 (clamped to 1.0) or wrong.
    var stats = stats(1000, 100, 0);
    double eq = SelectivityEstimator.estimateEquality(stats, null, 42);
    double ne = SelectivityEstimator.estimateForOperator(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator(-1),
        stats, null, 42);
    assertEquals("NE = 1.0 - equality", 1.0 - eq, ne, DELTA);
    assertTrue("NE should be < 1.0 for non-trivial case", ne < 1.0);
    assertTrue("NE should be > 0.0", ne > 0.0);
  }

  // ═══════════════════════════════════════════════════════════════
  // Scalarize param swap: line 563 (swapped params 2 and 3)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scalarize_paramOrder_affectsResult() {
    // String histogram where lo="aaa", hi="zzz", value="mmm"
    // If scalarize params are swapped, the fraction would be different.
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {"aaa", "zzz"},
        new long[] {1000}, new long[] {100},
        1000, null, 0);
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, "mmm");
    // "mmm" is roughly in the middle of ["aaa","zzz"], so ~0.5
    assertTrue("String interpolation should give ~0.5 for midpoint, got " + sel,
        sel > 0.2 && sel < 0.8);
  }

  // ═══════════════════════════════════════════════════════════════
  // Range estimate: param swap on line 398 (scalarize)
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void range_stringKeys_interpolatesCorrectly() {
    // Range with string keys in a single bucket
    var h = new EquiDepthHistogram(
        1, new Comparable<?>[] {"aaa", "zzz"},
        new long[] {1000}, new long[] {100},
        1000, null, 0);
    var stats = stats(1000, 100, 0);
    double sel = SelectivityEstimator.estimateRange(
        stats, h, "bbb", "yyy", true, true);
    // Most of the bucket range
    assertTrue("Range bbb-yyy in aaa-zzz should be large", sel > 0.5);
    assertTrue("Range should be < 1.0", sel < 1.0);
  }

  // ═══════════════════════════════════════════════════════════════
  // Complementary relationship: GT + LT + EQ ≈ 1.0
  // This catches many boundary/conditional mutations at once.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void complementary_gtPlusLtPlusEq_approxOne() {
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    int key = 50;
    double gt = SelectivityEstimator.estimateGreaterThan(stats, h, key);
    double lt = SelectivityEstimator.estimateLessThan(stats, h, key);
    double eq = SelectivityEstimator.estimateEquality(stats, h, key);
    double sum = gt + lt + eq;
    // Should be approximately 1.0 (may not be exact due to interpolation)
    assertTrue("GT + LT + EQ should be ~1.0, got " + sum,
        sum > 0.85 && sum < 1.15);
  }

  @Test
  public void complementary_gtePlusLt_approxOne() {
    var h = uniformHist4();
    var stats = stats(1000, 100, 0);
    double gte = SelectivityEstimator.estimateGreaterOrEqual(stats, h, 50);
    double lt = SelectivityEstimator.estimateLessThan(stats, h, 50);
    double sum = gte + lt;
    assertTrue("GTE + LT should be ~1.0, got " + sum,
        sum > 0.85 && sum < 1.15);
  }

  // ═══════════════════════════════════════════════════════════════
  // Clamp: NaN handling
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void clamp_nan_returnsZero() {
    assertEquals(0.0, SelectivityEstimator.clamp(Double.NaN), DELTA);
  }

  @Test
  public void clamp_negative_returnsZero() {
    assertEquals(0.0, SelectivityEstimator.clamp(-0.5), DELTA);
  }

  @Test
  public void clamp_aboveOne_returnsOne() {
    assertEquals(1.0, SelectivityEstimator.clamp(1.5), DELTA);
  }

  @Test
  public void clamp_normal_returnsUnchanged() {
    assertEquals(0.7, SelectivityEstimator.clamp(0.7), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // Multi-bucket histogram with asymmetric data
  // Catches boundary mutations in fractionOf, sumFrequencies
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void greaterThan_asymmetricHistogram_correctInterpolation() {
    // Bucket 0: [0,25) freq=100, distinct=10
    // Bucket 1: [25,50) freq=300, distinct=30
    // Bucket 2: [50,75) freq=400, distinct=40
    // Bucket 3: [75,100] freq=200, distinct=20
    // nonNull = 1000
    var h = hist4(
        new long[] {100, 300, 400, 200},
        new long[] {10, 30, 40, 20},
        1000, null, 0);
    var stats = stats(1000, 100, 0);

    // f > 50: bucket 2 partial + bucket 3 full
    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 50);
    // At boundary 50: fraction in bucket 2 = 0 (key == lower boundary)
    // remainingInB = (1-0) * 400 = 400, aboveBuckets = 200
    // selectivity = 600/1000 = 0.6
    assertEquals(0.6, sel, 0.05);

    // f < 25: bucket 0 partial
    double selLt = SelectivityEstimator.estimateLessThan(stats, h, 25);
    // At boundary 25: in bucket 0, fraction = 1.0 (key == upper boundary of bucket 0)
    // partialB = 1.0 * 100, belowBuckets = 0
    // selectivity = 100/1000 = 0.1
    assertEquals(0.1, selLt, 0.05);
  }

  // ═══════════════════════════════════════════════════════════════
  // Empty bucket in middle: ensures sumFrequencies handles zeros
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void range_emptyMiddleBucket_sumsCorrectly() {
    // Bucket 1 has freq=0 (gap in data)
    var h = hist4(
        new long[] {250, 0, 250, 500},
        new long[] {25, 0, 25, 50},
        1000, null, 0);
    var stats = stats(1000, 100, 0);
    // Range spanning buckets 0-2
    double sel = SelectivityEstimator.estimateRange(
        stats, h, 5, 60, true, true);
    assertTrue("Range with empty middle bucket should still work", sel > 0.0);
  }

  // ═══════════════════════════════════════════════════════════════
  // estimateForOperator dispatch: covers all operator types
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void estimateForOperator_allTypes() {
    var stats = stats(1000, 100, 0);
    var h = uniformHist4();

    // Each operator should return a value in [0, 1], not -1
    assertTrue(SelectivityEstimator.estimateForOperator(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator(-1),
        stats, h, 50) >= 0);
    assertTrue(SelectivityEstimator.estimateForOperator(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator(-1),
        stats, h, 50) >= 0);
    assertTrue(SelectivityEstimator.estimateForOperator(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator(-1),
        stats, h, 50) >= 0);
    assertTrue(SelectivityEstimator.estimateForOperator(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator(-1),
        stats, h, 50) >= 0);
    assertTrue(SelectivityEstimator.estimateForOperator(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator(-1),
        stats, h, 50) >= 0);
    assertTrue(SelectivityEstimator.estimateForOperator(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator(-1),
        stats, h, 50) >= 0);
  }
}
