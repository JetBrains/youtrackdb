package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.IndexOrderedCostModel.MultiSourceStrategy;
import org.junit.Test;

/**
 * Unit tests for the cost-based strategy selection in {@link IndexOrderedCostModel}.
 * Tests the pure static methods directly — no database or index required.
 */
public class IndexOrderedEdgeStepCostTest {

  // ---- computeCostsStatic: threshold guards ----

  // LinkBag below MIN_LINKBAG threshold (default 10) → null (skip index scan)
  @Test
  public void testComputeCostsReturnsNullBelowMinLinkBag() {
    var result = IndexOrderedCostModel.computeCosts(
        5, // linkBagSize < 10
        1000, // indexSize
        10, // limit
        null, // no histogram
        true);
    assertNull("Should return null when linkBagSize < MIN_LINKBAG", result);
  }

  // indexSize <= 0 → null
  @Test
  public void testComputeCostsReturnsNullForZeroIndexSize() {
    var result = IndexOrderedCostModel.computeCosts(
        100, 0, 10, null, true);
    assertNull("Should return null when indexSize <= 0", result);
  }

  // ---- computeCostsStatic: basic cost computation ----

  // With linkBag=100, indexSize=1000, limit=10: should produce valid costs
  @Test
  public void testComputeCostsBasic() {
    var result = IndexOrderedCostModel.computeCosts(
        100, // linkBagSize
        1000, // indexSize
        10, // limit
        null, // no histogram
        true); // ASC
    assertNotNull("Should return non-null cost estimate", result);

    // k = min(limit, linkBagSize) = 10
    assertEquals("k should be min(limit, linkBagSize)", 10, result.k());

    // density = 100/1000 = 0.1
    // expectedScanLength = k / density = 10 / 0.1 = 100
    assertEquals("expectedScanLength should be k/density",
        100.0, result.expectedScanLength(), 0.01);

    // Both costs should be positive
    assertTrue("costUnionScan should be positive", result.costUnionScan() > 0);
    assertTrue("costLoadSort should be positive", result.costLoadSort() > 0);
  }

  // No LIMIT (limit=-1): k should equal linkBagSize
  @Test
  public void testComputeCostsNoLimit() {
    var result = IndexOrderedCostModel.computeCosts(
        100, 1000, -1, null, true);
    assertNotNull(result);
    assertEquals("k should equal linkBagSize when no limit", 100, result.k());
  }

  // ---- Single-source decision: indexScan vs loadAll ----

  // High density (linkBag ≈ indexSize) + small LIMIT → index scan wins
  // because expectedScanLength is small (k/density ≈ k) and only k records loaded
  @Test
  public void testHighDensitySmallLimitFavorsIndexScan() {
    // linkBag=500, index=500, limit=5 → density=1.0, scanLength=5
    var costs = IndexOrderedCostModel.computeCosts(
        500, 500, 5, null, true);
    assertNotNull(costs);
    assertTrue(
        "With density=1.0 and small limit, index scan should be cheaper."
            + " unionScan=" + costs.costUnionScan() + " loadSort=" + costs.costLoadSort(),
        costs.costUnionScan() < costs.costLoadSort());
  }

  // Low density + no LIMIT → loadAll wins because index scan must scan many entries
  @Test
  public void testLowDensityNoLimitFavorsLoadAll() {
    // linkBag=50, index=100000, limit=-1 → density=0.0005, scanLength=100000
    var costs = IndexOrderedCostModel.computeCosts(
        50, 100_000, -1, null, true);
    assertNotNull(costs);
    assertTrue(
        "With low density and no limit, loadAll should be cheaper."
            + " unionScan=" + costs.costUnionScan() + " loadSort=" + costs.costLoadSort(),
        costs.costUnionScan() > costs.costLoadSort());
  }

  // ---- Multi-source strategy selection ----

  // Small data → LOAD_ALL_SORT (cost model returns null → fallback)
  @Test
  public void testSmallDataFallsBackToLoadAllSort() {
    var strategy = IndexOrderedCostModel.pickMultiSourceStrategy(
        5, // totalEdges < MIN_LINKBAG
        1000,
        10,
        null,
        true);
    assertEquals("Below MIN_LINKBAG threshold should fallback to LOAD_ALL_SORT",
        MultiSourceStrategy.LOAD_ALL_SORT, strategy);
  }

  // High density + small limit → UNION_RIDSET_SCAN or GLOBAL_SCAN
  // (both are cheaper than full load+sort for large data)
  @Test
  public void testHighDensitySmallLimitPicksIndexStrategy() {
    var strategy = IndexOrderedCostModel.pickMultiSourceStrategy(
        500, // totalEdges
        500, // indexSize (density=1.0)
        5, // small limit
        null,
        true);
    assertTrue(
        "High density + small limit should not pick LOAD_ALL_SORT, got: " + strategy,
        strategy != MultiSourceStrategy.LOAD_ALL_SORT);
  }

  // Low density + no limit → LOAD_ALL_SORT (index scan too expensive)
  @Test
  public void testLowDensityNoLimitPicksLoadAllSort() {
    var strategy = IndexOrderedCostModel.pickMultiSourceStrategy(
        50, // totalEdges
        100_000, // huge index
        -1, // no limit
        null,
        true);
    assertEquals("Low density + no limit should pick LOAD_ALL_SORT",
        MultiSourceStrategy.LOAD_ALL_SORT, strategy);
  }

  // Medium density: UNION_RIDSET_SCAN should beat GLOBAL_SCAN when union build
  // cost (cpu per edge) is less than per-entry random read in global scan
  @Test
  public void testMediumDensityUnionBeatsGlobal() {
    // density = 1000/10000 = 0.1, limit=20
    // expectedScanLength = 20/0.1 = 200
    // unionScan: builds RidSet(1000*cpu) + scan(200*(seq+cpu)) + load(20*rand)
    // globalScan: scan(200*(rand+cpu)) — rand per entry is much more expensive
    var strategy = IndexOrderedCostModel.pickMultiSourceStrategy(
        1000, // totalEdges
        10_000, // indexSize
        20, // limit
        null,
        true);
    assertEquals(
        "Medium density: UNION should beat GLOBAL (bitmap filter avoids random reads)",
        MultiSourceStrategy.UNION_RIDSET_SCAN, strategy);
  }

  // computeCosts with limit > linkBagSize: k should be clamped to linkBagSize
  @Test
  public void testComputeCostsLimitGreaterThanLinkBag() {
    var result = IndexOrderedCostModel.computeCosts(
        50, // linkBagSize
        1000, // indexSize
        200, // limit > linkBagSize
        null,
        true);
    assertNotNull("Should return non-null for valid inputs", result);
    // k = min(limit, linkBagSize) = min(200, 50) = 50
    assertEquals("k should be clamped to linkBagSize", 50, result.k());
  }

  // pickMultiSourceStrategy when computeCosts returns null → LOAD_ALL_SORT
  // (tests the null guard at the top of pickMultiSourceStrategy)
  @Test
  public void testPickMultiSourceStrategyNullCosts() {
    // indexSize=0 → computeCosts returns null → should get LOAD_ALL_SORT
    var strategy = IndexOrderedCostModel.pickMultiSourceStrategy(
        100, // totalEdges (above min threshold)
        0, // indexSize → forces null from computeCosts
        10,
        null,
        true);
    assertEquals("null costs should produce LOAD_ALL_SORT",
        MultiSourceStrategy.LOAD_ALL_SORT, strategy);
  }

  // applyHistogramSkew with DESC direction — scans last buckets instead of first
  @Test
  public void testApplyHistogramSkewDesc() {
    // Create a histogram with 4 buckets, skewed: more entries at the end (DESC region)
    var boundaries = new Comparable<?>[] {1, 25, 50, 75, 100};
    var frequencies = new long[] {10, 10, 10, 70}; // last bucket is heavy
    var distinctCounts = new long[] {10, 10, 10, 70};
    var histogram = new EquiDepthHistogram(
        4, boundaries, frequencies, distinctCounts, 100, null, 0);

    // expectedScanLength=25, indexSize=100 → targetFraction=0.25 → 1 bucket scanned
    // DESC: scans last bucket (index 3) with frequency 70
    // uniformExpected = 0.25 * 100 = 25
    // skew = 70 / 25 = 2.8 (within [0.5, 3.0] clamp)
    double adjusted = IndexOrderedCostModel.applyHistogramSkew(
        25.0, 100, histogram, false);
    // adjusted = 25.0 * 2.8 = 70.0
    assertTrue("DESC skew should inflate scan length for heavy tail, got: " + adjusted,
        adjusted > 25.0);
    assertEquals("DESC skew should be 25 * 2.8 = 70", 70.0, adjusted, 0.5);
  }

  // applyHistogramSkew with ASC direction — scans first buckets
  @Test
  public void testApplyHistogramSkewAsc() {
    // Histogram with 4 buckets, skewed: more entries at the start (ASC region)
    var boundaries = new Comparable<?>[] {1, 25, 50, 75, 100};
    var frequencies = new long[] {70, 10, 10, 10}; // first bucket is heavy
    var distinctCounts = new long[] {70, 10, 10, 10};
    var histogram = new EquiDepthHistogram(
        4, boundaries, frequencies, distinctCounts, 100, null, 0);

    // ASC: scans first bucket (index 0) with frequency 70
    double adjusted = IndexOrderedCostModel.applyHistogramSkew(
        25.0, 100, histogram, true);
    assertTrue("ASC skew should inflate scan length for heavy head, got: " + adjusted,
        adjusted > 25.0);
    assertEquals("ASC skew should be 25 * 2.8 = 70", 70.0, adjusted, 0.5);
  }

  // applyHistogramSkew clamp: skew > 3.0 should be clamped to 3.0
  @Test
  public void testApplyHistogramSkewClampedMax() {
    // Extremely skewed: all entries in one bucket
    var boundaries = new Comparable<?>[] {1, 25, 50, 75, 100};
    var frequencies = new long[] {100, 0, 0, 0}; // all in first bucket
    var distinctCounts = new long[] {100, 0, 0, 0};
    var histogram = new EquiDepthHistogram(
        4, boundaries, frequencies, distinctCounts, 100, null, 0);

    // ASC: scans first bucket, frequency=100, uniformExpected=25
    // skew = 100/25 = 4.0 → clamped to 3.0
    double adjusted = IndexOrderedCostModel.applyHistogramSkew(
        25.0, 100, histogram, true);
    assertEquals("Skew should be clamped to 3.0, so result = 75", 75.0, adjusted, 0.5);
  }

  // computeCosts with histogram provided: verifies histogram path is exercised
  @Test
  public void testComputeCostsWithHistogram() {
    var boundaries = new Comparable<?>[] {1, 50, 100};
    var frequencies = new long[] {50, 50};
    var distinctCounts = new long[] {50, 50};
    var histogram = new EquiDepthHistogram(
        2, boundaries, frequencies, distinctCounts, 100, null, 0);

    var result = IndexOrderedCostModel.computeCosts(
        100, // linkBagSize
        1000, // indexSize
        10, // limit
        histogram,
        true);
    assertNotNull("Should produce cost estimate with histogram", result);
    assertTrue("costUnionScan should be positive", result.costUnionScan() > 0);
    assertTrue("costLoadSort should be positive", result.costLoadSort() > 0);
  }

  // =====================================================================
  // Additional coverage tests for cost model edge cases
  // =====================================================================

  // Density approaches zero when linkBagSize=1 and indexSize is huge.
  // MIN_LINKBAG threshold (10) catches this: linkBagSize(1) < 10 → null.
  @Test
  public void testComputeCostsDensityZero() {
    var result = IndexOrderedCostModel.computeCosts(
        1, // linkBagSize — well below MIN_LINKBAG (default 10)
        Long.MAX_VALUE, // indexSize — huge, density near 0
        10,
        null,
        true);
    assertNull(
        "Should return null when linkBagSize < MIN_LINKBAG (density near zero)",
        result);
  }

  // Override QUERY_INDEX_ORDERED_MAX_SCAN to a small value (10).
  // With linkBagSize=100, indexSize=100, limit=-1: density=1.0,
  // k=100, expectedScanLength=100 > maxScan(10) → null.
  @Test
  public void testComputeCostsExceedsMaxScan() {
    var oldMaxScan =
        com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN
            .getValue();
    com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN
        .setValue(10L);
    try {
      var result = IndexOrderedCostModel.computeCosts(
          100, // linkBagSize
          100, // indexSize → density = 1.0
          -1, // no limit → k = 100
          null,
          true);
      // expectedScanLength = 100/1.0 = 100 > maxScan(10) → null
      assertNull(
          "Should return null when expectedScanLength exceeds maxScan",
          result);
    } finally {
      com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_INDEX_ORDERED_MAX_SCAN
          .setValue(oldMaxScan);
    }
  }

  // Histogram where scan region has 0 entries → skew = 0/expected.
  // This should be clamped to 0.5 (minimum skew) and reduce the
  // expected scan length by half.
  @Test
  public void testApplyHistogramSkewLowerClamp() {
    // 4 buckets: all entries in bucket 3, buckets 0-2 empty.
    // ASC scan with small targetFraction → scans bucket 0 (empty).
    var boundaries = new Comparable<?>[] {1, 25, 50, 75, 100};
    var frequencies = new long[] {0, 0, 0, 100}; // all in last bucket
    var distinctCounts = new long[] {0, 0, 0, 100};
    var histogram = new EquiDepthHistogram(
        4, boundaries, frequencies, distinctCounts, 100, null, 0);

    // ASC: scans first 1 bucket (index 0), frequency=0
    // uniformExpected = 0.25 * 100 = 25
    // skew = 0 / 25 = 0.0 → clamped to 0.5
    // adjusted = 25.0 * 0.5 = 12.5
    double adjusted = IndexOrderedCostModel.applyHistogramSkew(
        25.0, 100, histogram, true);
    assertEquals(
        "Skew should be clamped to 0.5, so result = 12.5",
        12.5, adjusted, 0.5);
    assertTrue(
        "Adjusted scan length should be less than original when bucket is empty",
        adjusted < 25.0);
  }

  // computeCosts with density approaching zero: linkBagSize >= MIN_LINKBAG but
  // indexSize = MAX_VALUE → density = linkBagSize/MAX_VALUE ≈ 0.
  // density > 0 check passes, but expectedScanLength is huge → exceeds maxScan → null.
  @Test
  public void testComputeCostsDensityNearZeroExceedsMaxScan() {
    // linkBagSize=10 (meets MIN_LINKBAG default of 10), indexSize=MAX_VALUE
    // density = 10/MAX_VALUE ≈ 0, expectedScanLength = 10/density ≈ MAX_VALUE
    // This exceeds QUERY_INDEX_ORDERED_MAX_SCAN → null
    var result = IndexOrderedCostModel.computeCosts(
        10, Long.MAX_VALUE, 10, null, true);
    assertNull(
        "Expected null when density is near zero causing scan to exceed maxScan",
        result);
  }

  // applyHistogramSkew lower bound clamp: histogram with all entries in last
  // bucket, ASC scan reads first bucket (empty). skew = 0/expected → 0.0 →
  // clamped to 0.5. Result is expectedScanLength * 0.5.
  @Test
  public void testApplyHistogramSkewLowerClampZeroEntries() {
    // 4 buckets: all 200 entries in bucket 3, buckets 0-2 empty.
    var boundaries = new Comparable<?>[] {1, 25, 50, 75, 100};
    var frequencies = new long[] {0, 0, 0, 200};
    var distinctCounts = new long[] {0, 0, 0, 200};
    var histogram = new EquiDepthHistogram(
        4, boundaries, frequencies, distinctCounts, 200, null, 0);

    // ASC: scans first 1 bucket (index 0), frequency=0.
    // uniformExpected = 0.25 * 200 = 50
    // skew = 0 / 50 = 0.0 → clamped to 0.5
    // adjusted = 50.0 * 0.5 = 25.0
    double adjusted = IndexOrderedCostModel.applyHistogramSkew(
        50.0, 200, histogram, true);
    assertEquals("Skew clamped to 0.5, result should be 25.0",
        25.0, adjusted, 0.5);
  }

  // pickMultiSourceStrategy: explicitly trigger all 3 strategies with distinct
  // parameter combinations and verify each one individually.
  @Test
  public void testPickMultiSourceExplicitlyAllStrategies() {
    // Strategy 1: UNION_RIDSET_SCAN
    // Medium density, moderate limit. Union build cost < global random read cost.
    var s1 = IndexOrderedCostModel.pickMultiSourceStrategy(
        500, 5000, 10, null, true);
    assertEquals("Medium density + moderate limit → UNION_RIDSET_SCAN",
        MultiSourceStrategy.UNION_RIDSET_SCAN, s1);

    // Strategy 2: GLOBAL_SCAN
    // Very high density + tiny limit. Union build cost (many edges * cpu)
    // dominates. Global scan of ~2 entries is cheaper.
    var s2 = IndexOrderedCostModel.pickMultiSourceStrategy(
        9000, 10_000, 2, null, true);
    assertEquals("Very high density + tiny limit → GLOBAL_SCAN",
        MultiSourceStrategy.GLOBAL_SCAN, s2);

    // Strategy 3: LOAD_ALL_SORT
    // Below MIN_LINKBAG → computeCosts returns null → LOAD_ALL_SORT.
    var s3 = IndexOrderedCostModel.pickMultiSourceStrategy(
        5, 1000, 10, null, true);
    assertEquals("Below MIN_LINKBAG → LOAD_ALL_SORT",
        MultiSourceStrategy.LOAD_ALL_SORT, s3);
  }

  // computeCosts with limit=0: treated same as no limit (limit > 0 is false).
  // k should equal linkBagSize.
  @Test
  public void testComputeCostsLimitZero() {
    var result = IndexOrderedCostModel.computeCosts(
        100, 1000, 0, null, true);
    assertNotNull("limit=0 should still produce valid costs", result);
    assertEquals("k should equal linkBagSize when limit=0", 100, result.k());
  }

  // computeCosts with histogram where nonNullCount > 0 and DESC direction:
  // exercises the DESC branch of applyHistogramSkew inside computeCosts.
  @Test
  public void testComputeCostsWithHistogramDesc() {
    var boundaries = new Comparable<?>[] {1, 50, 100};
    var frequencies = new long[] {50, 50};
    var distinctCounts = new long[] {50, 50};
    var histogram = new EquiDepthHistogram(
        2, boundaries, frequencies, distinctCounts, 100, null, 0);

    var result = IndexOrderedCostModel.computeCosts(
        100, 1000, 10, histogram, false); // DESC
    assertNotNull("Should produce cost estimate with histogram + DESC", result);
    assertTrue("costUnionScan should be positive", result.costUnionScan() > 0);
  }

  // sumFrequencies with empty range (from == to): should return 0.
  @Test
  public void testSumFrequenciesEmptyRange() {
    long sum = IndexOrderedCostModel.sumFrequencies(
        new long[] {10, 20, 30}, 1, 1);
    assertEquals("Empty range should sum to 0", 0L, sum);
  }

  // sumFrequencies with negative values: negatives are clamped to 0.
  @Test
  public void testSumFrequenciesNegativeValues() {
    long sum = IndexOrderedCostModel.sumFrequencies(
        new long[] {-5, 10, -3, 20}, 0, 4);
    assertEquals("Negative frequencies clamped to 0: 0+10+0+20=30", 30L, sum);
  }

  // Three test cases to trigger each of the three multi-source strategies:
  // UNION_RIDSET_SCAN, GLOBAL_SCAN, and LOAD_ALL_SORT.
  @Test
  public void testPickMultiSourceAllThreeStrategies() {
    // Case 1: UNION_RIDSET_SCAN — medium density, moderate limit.
    // totalEdges=1000, indexSize=10000, limit=20 → density=0.1
    // Union: builds RidSet(1000*cpu) + scan(200*(seq+cpu)) + load(20*(rand+cpu))
    // Global: scan(200*(rand+cpu)) — more expensive per entry
    // LoadAll: 1000*rand + 1000*cpu + 1000*log2(20)*cpu
    var strategyUnion = IndexOrderedCostModel.pickMultiSourceStrategy(
        1000, 10_000, 20, null, true);
    assertEquals(
        "Medium density + moderate limit should pick UNION_RIDSET_SCAN",
        MultiSourceStrategy.UNION_RIDSET_SCAN, strategyUnion);

    // Case 2: GLOBAL_SCAN — very high density, very small limit.
    // totalEdges=9000, indexSize=10000, limit=2 → density=0.9
    // Union build cost (9000*cpu) dominates. Global scan of ~2 entries is cheaper.
    var strategyGlobal = IndexOrderedCostModel.pickMultiSourceStrategy(
        9000, 10_000, 2, null, true);
    assertEquals(
        "Very high density + tiny limit should pick GLOBAL_SCAN",
        MultiSourceStrategy.GLOBAL_SCAN, strategyGlobal);

    // Case 3: LOAD_ALL_SORT — low density, no limit.
    // totalEdges=50, indexSize=100000, limit=-1 → density=0.0005
    // Index scan too expensive (scan 100000 entries).
    var strategySort = IndexOrderedCostModel.pickMultiSourceStrategy(
        50, 100_000, -1, null, true);
    assertEquals(
        "Low density + no limit should pick LOAD_ALL_SORT",
        MultiSourceStrategy.LOAD_ALL_SORT, strategySort);
  }
}
