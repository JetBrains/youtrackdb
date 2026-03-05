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

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Integration-level selectivity estimation tests (Section 10.8).
 *
 * <p>Tests selectivity accuracy against known data distributions using
 * realistic 128-bucket histograms, compound predicate composition,
 * string range interpolation, boolean/enum single-value bucket behavior,
 * and MCV short-circuit scenarios.
 *
 * <p>These complement the unit-level tests in {@link SelectivityEstimatorTest}
 * by verifying end-to-end accuracy rather than individual formula correctness.
 */
public class SelectivityEstimationIntegrationTest {

  private static final double DELTA = 1e-9;

  // ═══════════════════════════════════════════════════════════════════
  //  Helper: build a 128-bucket histogram from known uniform data
  // ═══════════════════════════════════════════════════════════════════

  /**
   * Builds a 128-bucket equi-depth histogram from a uniform distribution
   * of integers [0, totalEntries). Each bucket has approximately
   * totalEntries/128 entries.
   */
  private static EquiDepthHistogram buildUniform128(int totalEntries) {
    int buckets = 128;
    Comparable<?>[] boundaries = new Comparable<?>[buckets + 1];
    long[] frequencies = new long[buckets];
    long[] distinctCounts = new long[buckets];

    int entriesPerBucket = totalEntries / buckets;
    int remainder = totalEntries % buckets;
    long nonNull = 0;

    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = i * (totalEntries / buckets);
    }
    // Adjust last boundary to exactly totalEntries - 1 for proper range
    boundaries[buckets] = totalEntries - 1;

    for (int i = 0; i < buckets; i++) {
      frequencies[i] = entriesPerBucket + (i < remainder ? 1 : 0);
      distinctCounts[i] = frequencies[i]; // uniform: each key unique
      nonNull += frequencies[i];
    }

    return new EquiDepthHistogram(
        buckets, boundaries, frequencies, distinctCounts, nonNull,
        null, 0);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Known data within 1% accuracy (128 buckets)
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void knownUniformData128BucketsEqualityWithin1Percent() {
    // Given: 128,000 entries uniformly distributed [0, 128000)
    // 128 buckets, each with 1000 entries, 1000 distinct values.
    // Expected: (1/distinctCounts[b]) * (frequencies[b]/nonNull)
    //         = (1/1000) * (1000/128000) = 1/128000
    int total = 128_000;
    var h = buildUniform128(total);
    var stats = new IndexStatistics(total, total, 0);

    double sel = SelectivityEstimator.estimateEquality(stats, h, 50_000);
    double expected = 1.0 / total;

    // Within 1% of true selectivity
    Assert.assertEquals(expected, sel, expected * 0.01);
  }

  @Test
  public void knownUniformData128BucketsRangeWithin1Percent() {
    // Given: 128,000 entries uniformly distributed.
    // Range [32000, 96000) covers 50% of the data (64000 / 128000).
    int total = 128_000;
    var h = buildUniform128(total);
    var stats = new IndexStatistics(total, total, 0);

    double sel = SelectivityEstimator.estimateRange(
        stats, h, 32_000, 96_000, true, false);
    double expected = 0.5;

    Assert.assertEquals(expected, sel, expected * 0.01);
  }

  @Test
  public void knownUniformData128BucketsGreaterThanWithin1Percent() {
    // f > 64000: exactly half the data is above midpoint.
    int total = 128_000;
    var h = buildUniform128(total);
    var stats = new IndexStatistics(total, total, 0);

    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, 64_000);
    double expected = 0.5;

    Assert.assertEquals(expected, sel, expected * 0.01);
  }

  @Test
  public void knownUniformData128BucketsLessThanWithin1Percent() {
    // f < 32000: exactly 25% of the data.
    int total = 128_000;
    var h = buildUniform128(total);
    var stats = new IndexStatistics(total, total, 0);

    double sel = SelectivityEstimator.estimateLessThan(stats, h, 32_000);
    double expected = 0.25;

    Assert.assertEquals(expected, sel, expected * 0.01);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Uniform-mode estimates within reasonable bounds
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void uniformModeEqualityBetweenZeroAndOne() {
    var stats = new IndexStatistics(10_000, 500, 0);
    double sel = SelectivityEstimator.estimateEquality(stats, null, 42);
    // 1/500 = 0.002 — must be in (0, 1)
    Assert.assertTrue(sel > 0.0);
    Assert.assertTrue(sel < 1.0);
    Assert.assertEquals(1.0 / 500, sel, DELTA);
  }

  @Test
  public void uniformModeRangeReturnsOneThird() {
    // All range operators return 1/3 in uniform mode
    var stats = new IndexStatistics(10_000, 500, 0);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateGreaterThan(stats, null, 42), DELTA);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateGreaterOrEqual(stats, null, 42), DELTA);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateLessThan(stats, null, 42), DELTA);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateLessOrEqual(stats, null, 42), DELTA);
    Assert.assertEquals(1.0 / 3.0,
        SelectivityEstimator.estimateRange(
            stats, null, 10, 90, true, true), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Edge cases: single-value, all-null, very high NDV
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void singleValueIndexEqualityMatchReturnsOne() {
    // Index with a single distinct value: all 1000 entries are "active".
    // 1 bucket, NDV=1, MCV = "active", frequency = 1000.
    var h = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{"active", "active"},
        new long[]{1000},
        new long[]{1},
        1000,
        "active", 1000);
    var stats = new IndexStatistics(1000, 1, 0);

    // MCV match → exact frequency = 1000/1000 = 1.0
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateEquality(stats, h, "active"), DELTA);
  }

  @Test
  public void singleValueIndexEqualityMissReturnsMinimal() {
    // Same single-value index but querying a different value.
    var h = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{"active", "active"},
        new long[]{1000},
        new long[]{1},
        1000,
        "active", 1000);
    var stats = new IndexStatistics(1000, 1, 0);

    // "inactive" is out of range (both boundaries are "active")
    // → out-of-range returns 1/nonNullCount = 1/1000
    Assert.assertEquals(1.0 / 1000,
        SelectivityEstimator.estimateEquality(stats, h, "inactive"), DELTA);
  }

  @Test
  public void allNullIndexReturnsZeroForEqualityAndIn() {
    // 500 entries, all null → totalCount=500, distinctCount=0, nullCount=500.
    // Equality and IN return 0 (distinctCount=0 → uniform formula = 0).
    // Range operators (GT/LT/GTE/LTE) return 1/3 (uniform default) because
    // totalCount > 0 and they don't check distinctCount.
    var stats = new IndexStatistics(500, 0, 500);

    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateEquality(stats, null, 42), DELTA);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateIn(stats, null, List.of(1, 2)), DELTA);
  }

  @Test
  public void allNullIndexIsNullReturnsOne() {
    var stats = new IndexStatistics(500, 0, 500);
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateIsNull(stats, null), DELTA);
  }

  @Test
  public void allNullIndexIsNotNullReturnsZero() {
    var stats = new IndexStatistics(500, 0, 500);
    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateIsNotNull(stats, null), DELTA);
  }

  @Test
  public void veryHighNdvEqualityApproachesZero() {
    // 1,000,000 entries with 1,000,000 distinct values.
    // 128-bucket histogram, each bucket ~7812 entries, 7812 distinct.
    int total = 1_000_000;
    var h = buildUniform128(total);
    var stats = new IndexStatistics(total, total, 0);

    double sel = SelectivityEstimator.estimateEquality(stats, h, 500_000);
    Assert.assertTrue("High-NDV equality should be very small",
        sel < 0.001);
    Assert.assertTrue("High-NDV equality should be positive",
        sel > 0.0);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  IS NULL / IS NOT NULL / IN with histograms
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void isNullWithHistogramUsesHistogramNonNullCount() {
    // Histogram says nonNull=800, stats says nullCount=200
    // IS NULL = 200 / (800 + 200) = 0.2
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{400, 400},
        new long[]{25, 25},
        800, null, 0);
    var stats = new IndexStatistics(1000, 50, 200);

    Assert.assertEquals(0.2,
        SelectivityEstimator.estimateIsNull(stats, h), DELTA);
  }

  @Test
  public void isNotNullWithHistogramUsesHistogramNonNullCount() {
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{400, 400},
        new long[]{25, 25},
        800, null, 0);
    var stats = new IndexStatistics(1000, 50, 200);

    Assert.assertEquals(0.8,
        SelectivityEstimator.estimateIsNotNull(stats, h), DELTA);
  }

  @Test
  public void inWithHistogramSumsEqualitiesFromDistinctBuckets() {
    // 4 buckets [0,100), [100,200), [200,300), [300,400].
    // Each: 250 entries, 50 distinct. Total = 1000.
    // IN(50, 250) → values in buckets 0 and 2
    // Each: (1/50) * (250/1000) = 0.005
    // Sum = 0.01
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 100, 200, 300, 400},
        new long[]{250, 250, 250, 250},
        new long[]{50, 50, 50, 50},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 200, 0);

    Assert.assertEquals(0.01,
        SelectivityEstimator.estimateIn(stats, h, List.of(50, 250)),
        DELTA);
  }

  @Test
  public void inWithManyValuesClampedToOne() {
    // IN with enough values that summed equalities exceed 1.0
    var h = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{0, 10},
        new long[]{10},
        new long[]{2},
        10, null, 0);
    var stats = new IndexStatistics(10, 2, 0);

    // Each equality: (1/2) * (10/10) = 0.5. Three values = 1.5 → clamped
    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateIn(stats, h, List.of(1, 5, 8)),
        DELTA);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Compound predicates (AND / OR / NOT)
  //  SelectivityEstimator is per-predicate; compound composition is
  //  tested here via manual formula application matching the
  //  independence assumption used in SQLWhereClause.
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void andOfTwoPredicatesMultipliesSelectivities() {
    // age > 30 AND age < 60 on [0, 100] uniform histogram
    // Approximation: P(GT 30) * P(LT 60) under independence
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    double gt30 = SelectivityEstimator.estimateGreaterThan(stats, h, 30);
    double lt60 = SelectivityEstimator.estimateLessThan(stats, h, 60);
    double combined = gt30 * lt60;

    // Both should be reasonable fractions
    Assert.assertTrue(gt30 > 0.5 && gt30 < 0.9);
    Assert.assertTrue(lt60 > 0.4 && lt60 < 0.8);
    // Combined AND should be less than either individual
    Assert.assertTrue(combined < gt30);
    Assert.assertTrue(combined < lt60);
    Assert.assertTrue(combined > 0.0);
  }

  @Test
  public void orOfTwoPredicatesUsesInclusionExclusion() {
    // P(A OR B) = P(A) + P(B) - P(A) * P(B) (independence)
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    double eq10 = SelectivityEstimator.estimateEquality(stats, h, 10);
    double eq90 = SelectivityEstimator.estimateEquality(stats, h, 90);
    double orSel = eq10 + eq90 - eq10 * eq90;

    // OR should be greater than either individual equality
    Assert.assertTrue(orSel > eq10);
    Assert.assertTrue(orSel > eq90);
    Assert.assertTrue(orSel < 1.0);
  }

  @Test
  public void notInvertsSelectivity() {
    // NOT (age = 50) = 1 - P(age = 50)
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    double eqSel = SelectivityEstimator.estimateEquality(stats, h, 50);
    double notSel = 1.0 - eqSel;

    Assert.assertTrue(notSel > 0.9);
    Assert.assertEquals(1.0, eqSel + notSel, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  String range queries use scalar conversion for interpolation
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void stringRangeInterpolationProducesReasonableEstimate() {
    // Histogram with string boundaries ["apple", "mango", "zebra"]
    // Range query: "banana" < f < "orange"
    // The scalar conversion should interpolate between the string
    // boundaries to produce a fraction.
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{"apple", "mango", "zebra"},
        new long[]{500, 500},
        new long[]{100, 100},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 200, 0);

    double sel = SelectivityEstimator.estimateRange(
        stats, h, "banana", "orange", true, true);

    // Should be a reasonable fraction — "banana" to "orange" covers
    // roughly the middle portion of "apple" to "zebra"
    Assert.assertTrue("String range should produce > 0", sel > 0.0);
    Assert.assertTrue("String range should produce < 1", sel < 1.0);
  }

  @Test
  public void stringGreaterThanInterpolatesWithScalarConversion() {
    // f > "m" with boundaries ["aaa", "zzz"]
    var h = new EquiDepthHistogram(
        1,
        new Comparable<?>[]{"aaa", "zzz"},
        new long[]{1000},
        new long[]{260},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 260, 0);

    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, "m");
    // "m" is roughly in the middle of "aaa" to "zzz", so ~0.5
    Assert.assertTrue("f > 'm' should be roughly ~0.5", sel > 0.3);
    Assert.assertTrue("f > 'm' should be roughly ~0.5", sel < 0.7);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Out-of-range equality and range
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void outOfRangeEqualityBelowMinReturnsMinimal() {
    // Key below boundaries[0] → 1/nonNullCount
    var h = buildUniform128(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);

    double sel = SelectivityEstimator.estimateEquality(stats, h, -100);
    Assert.assertEquals(1.0 / 10_000, sel, DELTA);
  }

  @Test
  public void outOfRangeEqualityAboveMaxReturnsMinimal() {
    var h = buildUniform128(10_000);
    var stats = new IndexStatistics(10_000, 10_000, 0);

    double sel = SelectivityEstimator.estimateEquality(stats, h, 999_999);
    Assert.assertEquals(1.0 / 10_000, sel, DELTA);
  }

  @Test
  public void outOfRangeGreaterThanAboveMaxReturnsZero() {
    // f > max_key → nothing above → selectivity = 0
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateGreaterThan(stats, h, 100), DELTA);
  }

  @Test
  public void outOfRangeGreaterThanBelowMinReturnsApproxOne() {
    // f > (value below min) → everything above → ~1.0
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    double sel = SelectivityEstimator.estimateGreaterThan(stats, h, -10);
    Assert.assertEquals(1.0, sel, DELTA);
  }

  @Test
  public void outOfRangeLessThanBelowMinReturnsZero() {
    // f < min_key → nothing below → 0.0
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateLessThan(stats, h, 0), DELTA);
  }

  @Test
  public void outOfRangeLessThanAboveMaxReturnsApproxOne() {
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    Assert.assertEquals(1.0,
        SelectivityEstimator.estimateLessThan(stats, h, 110), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Single-value bucket optimization (boolean index)
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void booleanIndexGreaterThanTrueReturnsZero() {
    // Boolean index: {false=300, true=700}
    // 2 buckets: [false, false] (NDV=1), [true, true] (NDV=1)
    // f > true: bucket 1 is single-value, STRICT_ABOVE fraction = 1.0
    // remainingInB1 = 0, no buckets above → 0.0
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{false, true, true},
        new long[]{300, 700},
        new long[]{1, 1},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 2, 0);

    Assert.assertEquals(0.0,
        SelectivityEstimator.estimateGreaterThan(stats, h, true), DELTA);
  }

  @Test
  public void booleanIndexGreaterThanFalseReturnsTrueBucketFraction() {
    // f > false: bucket 0 is single-value at "false",
    // STRICT_ABOVE fraction = 1.0 (value equals bucketVal)
    // → remainingInB0 = 0, bucket 1 = 700
    // selectivity = 700/1000 = 0.7
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{false, true, true},
        new long[]{300, 700},
        new long[]{1, 1},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 2, 0);

    Assert.assertEquals(0.7,
        SelectivityEstimator.estimateGreaterThan(stats, h, false), DELTA);
  }

  @Test
  public void booleanIndexEqualityTrueUsesDirectBucketFormula() {
    // Equality on true: bucket 1, NDV=1
    // (1/1) * (700/1000) = 0.7
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{false, true, true},
        new long[]{300, 700},
        new long[]{1, 1},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 2, 0);

    Assert.assertEquals(0.7,
        SelectivityEstimator.estimateEquality(stats, h, true), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Single-value bucket range: enum field {A, B, C}
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void enumFieldRangeCountsDiscreteBuckets() {
    // Enum index: {A=200, B=500, C=300}. 3 buckets, each NDV=1.
    // Range A <= f <= B: covers buckets containing A and B.
    // Bucket 0 [A, B): A is at bucket 0, RANGE fraction of A in bucket 0
    // Bucket 1 [B, C): B is at bucket 1
    var h = new EquiDepthHistogram(
        3,
        new Comparable<?>[]{"A", "B", "C", "C"},
        new long[]{200, 500, 300},
        new long[]{1, 1, 1},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 3, 0);

    // Range ["A", "B"]: A in bucket 0, B in bucket 1
    // Bucket 0 is single-value (NDV=1), val="A":
    //   fracX of "A" == bucketVal "A" → RANGE mode → 0.5
    //   lowerPart = (1 - 0.5) * 200 = 100
    // No middle buckets (bx=0, by=1, bx+1=1 == by)
    // Bucket 1 is single-value (NDV=1), val="B":
    //   fracY of "B" == bucketVal "B" → RANGE mode → 0.5
    //   upperPart = 0.5 * 500 = 250
    // Total = 100 + 250 = 350. sel = 350/1000 = 0.35
    double sel = SelectivityEstimator.estimateRange(
        stats, h, "A", "B", true, true);
    Assert.assertEquals(0.35, sel, DELTA);
  }

  @Test
  public void enumFieldEqualityOnKnownValue() {
    // Equality on "B": bucket 1, NDV=1, freq=500
    // (1/1) * (500/1000) = 0.5
    var h = new EquiDepthHistogram(
        3,
        new Comparable<?>[]{"A", "B", "C", "C"},
        new long[]{200, 500, 300},
        new long[]{1, 1, 1},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 3, 0);

    Assert.assertEquals(0.5,
        SelectivityEstimator.estimateEquality(stats, h, "B"), DELTA);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  MCV short-circuit / vs bucket average / miss
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void mcvShortCircuitUsesExactFrequency() {
    // 90% of entries have key=50 (MCV), 10% spread across other buckets.
    // Manually construct a 4-bucket histogram with known nonNullCount.
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 100, 200, 300, 400},
        new long[]{900, 34, 33, 33},
        new long[]{10, 10, 10, 10},
        1000,
        50, 900); // MCV = 50, freq = 900
    var stats = new IndexStatistics(1000, 40, 0);

    // MCV match → exact frequency: 900/1000 = 0.9
    Assert.assertEquals(0.9,
        SelectivityEstimator.estimateEquality(stats, h, 50), DELTA);
  }

  @Test
  public void mcvSelectivityHigherThanBucketAverage() {
    // Skewed: bucket 0 has 900 entries, 10 distinct.
    // Bucket-averaged equality for bucket 0: (1/10) * (900/1000) = 0.09
    // MCV exact frequency: 900/1000 = 0.9 — much higher.
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 100, 200, 300, 400},
        new long[]{900, 34, 33, 33},
        new long[]{10, 10, 10, 10},
        1000,
        50, 900); // MCV = 50, in bucket 0
    var stats = new IndexStatistics(1000, 40, 0);

    double mcvSel = SelectivityEstimator.estimateEquality(stats, h, 50);
    // 50 matches MCV → 900/1000 = 0.9
    Assert.assertEquals(0.9, mcvSel, DELTA);

    // Compare with bucket-averaged for a non-MCV value in the same bucket
    double bucketAvg = SelectivityEstimator.estimateEquality(stats, h, 25);
    // 25 doesn't match MCV → (1/10) * (900/1000) = 0.09
    Assert.assertEquals(0.09, bucketAvg, DELTA);

    // MCV should be higher than bucket average
    Assert.assertTrue(mcvSel > bucketAvg);
  }

  @Test
  public void mcvMissFallsThroughToBucketFormula() {
    // MCV = 42, query value = 150 (non-MCV, different bucket)
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 100, 200, 300, 400},
        new long[]{250, 250, 250, 250},
        new long[]{50, 50, 50, 50},
        1000,
        42, 500);
    var stats = new IndexStatistics(1000, 200, 0);

    // 150 is in bucket 1: (1/50) * (250/1000) = 0.005
    Assert.assertEquals(0.005,
        SelectivityEstimator.estimateEquality(stats, h, 150), DELTA);
  }

  @Test
  public void mcvInLargeDatasetMaintainsAccuracy() {
    // 100,000 entries with strong MCV: "admin" appears 50,000 times.
    // Other values spread across 127 remaining buckets.
    int total = 100_000;
    int mcvFreq = 50_000;
    int buckets = 128;
    Comparable<?>[] boundaries = new Comparable<?>[buckets + 1];
    long[] frequencies = new long[buckets];
    long[] distinctCounts = new long[buckets];
    for (int i = 0; i <= buckets; i++) {
      boundaries[i] = i * 1000;
    }
    // MCV is in bucket 0
    frequencies[0] = mcvFreq;
    distinctCounts[0] = 100;
    long nonNull = mcvFreq;
    int remaining = total - mcvFreq;
    int perBucket = remaining / (buckets - 1);
    for (int i = 1; i < buckets; i++) {
      frequencies[i] = perBucket;
      distinctCounts[i] = Math.max(1, perBucket / 3);
      nonNull += perBucket;
    }
    var h = new EquiDepthHistogram(
        buckets, boundaries, frequencies, distinctCounts, nonNull,
        500, mcvFreq); // MCV key = 500 (in bucket 0)
    var stats = new IndexStatistics(total, total / 2, 0);

    // MCV hit: exact frequency
    double mcvSel =
        SelectivityEstimator.estimateEquality(stats, h, 500);
    Assert.assertEquals((double) mcvFreq / nonNull, mcvSel, 0.001);

    // Non-MCV in different bucket: uses bucket formula
    double otherSel =
        SelectivityEstimator.estimateEquality(stats, h, 5000);
    Assert.assertTrue("Non-MCV selectivity should be small",
        otherSel < 0.01);
  }

  // ═══════════════════════════════════════════════════════════════════
  //  Complementary property tests
  // ═══════════════════════════════════════════════════════════════════

  @Test
  public void gtePlusLtApproximatesOne() {
    // P(f >= X) + P(f < X) should approximate 1.0 for any X in range
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    for (int x : new int[]{10, 25, 50, 75, 90}) {
      double gte = SelectivityEstimator.estimateGreaterOrEqual(stats, h, x);
      double lt = SelectivityEstimator.estimateLessThan(stats, h, x);
      Assert.assertEquals("GTE + LT should be ~1.0 for x=" + x,
          1.0, gte + lt, 0.02);
    }
  }

  @Test
  public void ltePlusGtApproximatesOne() {
    var h = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{25, 25, 25, 25},
        1000, null, 0);
    var stats = new IndexStatistics(1000, 100, 0);

    for (int x : new int[]{10, 25, 50, 75, 90}) {
      double lte = SelectivityEstimator.estimateLessOrEqual(stats, h, x);
      double gt = SelectivityEstimator.estimateGreaterThan(stats, h, x);
      Assert.assertEquals("LTE + GT should be ~1.0 for x=" + x,
          1.0, lte + gt, 0.02);
    }
  }

  @Test
  public void isNullPlusIsNotNullEqualsOne() {
    // For any stats, IS_NULL + IS_NOT_NULL = 1.0
    var h = new EquiDepthHistogram(
        2,
        new Comparable<?>[]{0, 50, 100},
        new long[]{300, 500},
        new long[]{10, 20},
        800, null, 0);
    var stats = new IndexStatistics(1000, 30, 200);

    double isNull = SelectivityEstimator.estimateIsNull(stats, h);
    double isNotNull = SelectivityEstimator.estimateIsNotNull(stats, h);
    Assert.assertEquals(1.0, isNull + isNotNull, DELTA);
  }
}
