package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
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

  // No LIMIT (limit=-1): k should equal linkBagSize. Density is 1.0 here so the scan passes
  // the dominance rule and a cost estimate exists to read k from.
  @Test
  public void testComputeCostsNoLimit() {
    var result = IndexOrderedCostModel.computeCosts(
        100, 100, -1, null, true);
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

  // Low density + no LIMIT → loadAll wins because the index scan must walk many entries.
  // The scan asks to read 100,000 index entries where the alternative reads 50 records, and the
  // cost comparison is what says so: 6860 against 203 in cost units. No gate refuses this shape
  // ahead of the comparison, so the estimate comes back non-null and every caller reads the
  // verdict off the two costs.
  @Test
  public void testLowDensityNoLimitFavorsLoadAll() {
    // linkBag=50, index=100000, limit=-1 → density=0.0005, scanLength=100000
    var costs = IndexOrderedCostModel.computeCosts(
        50, 100_000, -1, null, true);
    assertNotNull(costs);
    assertTrue(
        "A scan of 100000 entries against 50 loadable records must lose to load-and-sort;"
            + " unionScan=" + costs.costUnionScan() + " loadSort=" + costs.costLoadSort(),
        costs.costLoadSort() < costs.costUnionScan());
    assertEquals(
        "and the strategy picker must load and sort",
        MultiSourceStrategy.LOAD_ALL_SORT,
        IndexOrderedCostModel.pickMultiSourceStrategy(50, 100_000, -1, null, true));
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

  // A multi-source estimate marked capped hit the index-size ceiling — not proof that every
  // entry is reachable. Density 1.0 would price GLOBAL_SCAN as a LIMIT-sized walk.
  @Test
  public void testCappedEstimateRefusesGlobalScanRegardlessOfIndexSize() {
    var large = IndexOrderedCostModel.pickMultiSourceStrategy(
        3_600_000, // totalEdges == indexSize
        3_600_000,
        20,
        null,
        false,
        true); // capped
    assertEquals(
        "Capped estimate must load from sources, not GLOBAL_SCAN",
        MultiSourceStrategy.LOAD_ALL_SORT, large);

    // Same rule below the old magic 100_000 index-size floor.
    var small = IndexOrderedCostModel.pickMultiSourceStrategy(
        50_000, 50_000, 20, null, true, true);
    assertEquals(
        "Capped estimate refuses GLOBAL_SCAN on a mid-size index too",
        MultiSourceStrategy.LOAD_ALL_SORT, small);
  }

  // Legitimate density=1.0 on a small index (every entry reachable, not a capped estimate)
  // may still scan.
  @Test
  public void testFullDensityOnSmallIndexStillAllowsIndexStrategy() {
    var strategy = IndexOrderedCostModel.pickMultiSourceStrategy(
        500, // totalEdges
        500, // small index, true density=1.0
        5,
        null,
        true,
        false); // not capped
    assertTrue(
        "True density=1.0 on a small index should keep an index strategy, got: " + strategy,
        strategy != MultiSourceStrategy.LOAD_ALL_SORT);
  }

  @Test
  public void testEntriesWorthTheLoadAlternativePositive() {
    var entries = IndexOrderedCostModel.entriesWorthTheLoadAlternative(20);
    assertTrue("20 records should be worth a positive entry budget, got: " + entries, entries > 0);
    // At shipped constants ~73 entries per record
    assertTrue("budget should be well above the record count, got: " + entries, entries > 20);
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

  // computeCosts with limit > linkBagSize: k should be clamped to linkBagSize. Index size
  // equals the LinkBag size so density is 1.0 and the dominance rule admits the scan.
  @Test
  public void testComputeCostsLimitGreaterThanLinkBag() {
    var result = IndexOrderedCostModel.computeCosts(
        50, // linkBagSize
        50, // indexSize
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

    // Index size equals the LinkBag size, so the histogram-corrected scan length stays inside
    // the dominance bound and the histogram branch is still the thing under test.
    var result = IndexOrderedCostModel.computeCosts(
        100, // linkBagSize
        100, // indexSize
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
  // k should equal linkBagSize. Density 1.0 keeps the scan admissible.
  @Test
  public void testComputeCostsLimitZero() {
    var result = IndexOrderedCostModel.computeCosts(
        100, 100, 0, null, true);
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
        100, 100, 10, histogram, false); // DESC, density 1.0 so the scan stays admissible
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

  /**
   * Small source LinkBag vs a huge ordered index and a small LIMIT: the scan would walk
   * far more index entries than the LinkBag has records. The cost comparison must refuse
   * that plan on its own, without a gate ahead of {@link IndexOrderedCostModel#computeCosts}.
   */
  @Test
  public void testSf1Is2LikeShapeRefusesTheIndexScan() {
    // ~2.4M indexed rows; source LinkBag ~50–200.
    int linkBag = 100;
    long indexSize = 2_400_000L;
    long limit = 10;
    int downstreamEdges = 2;

    // expectedScanLength = 10 / (100/2.4e6) = 240_000, against 100 loadable records.
    var costs = IndexOrderedCostModel.computeCosts(
        linkBag, indexSize, limit, null, false, downstreamEdges);
    assertNotNull(costs);
    assertEquals(240_000.0, costs.expectedScanLength(), 1.0);
    assertTrue(
        "Sparse LinkBag vs huge index must lose to load-and-sort by a wide margin; unionScan="
            + costs.costUnionScan() + " loadSort=" + costs.costLoadSort(),
        costs.costLoadSort() * 10 < costs.costUnionScan());
  }

  /**
   * Opposite of the sparse-LinkBag case: the source owns most of the indexed rows. Index
   * scan must win — the regime integration tests force with large single-source data and
   * no artificial MAX_SCAN=1.
   */
  @Test
  public void testHighDensityWithLimitPrefersIndexScan() {
    var costs = IndexOrderedCostModel.computeCosts(
        200, 200, 5, null, false, 0);
    assertNotNull(costs);
    assertTrue(
        "Dense LinkBag + small LIMIT must prefer index scan; unionScan="
            + costs.costUnionScan() + " loadSort=" + costs.costLoadSort(),
        costs.costUnionScan() < costs.costLoadSort());
  }

  // =====================================================================
  // The cost comparison is the whole admission decision
  // =====================================================================

  /**
   * THE CASE A DOMINANCE GATE WRONGLY REFUSED. A LinkBag of 1000 against a million-entry index
   * with {@code LIMIT 10} walks 10,000 entries. Charging an entry the cost of a record refused
   * that against 1000 records, while this model prices the scan at 739 against 4043 for
   * load-and-sort, a five-fold win it threw away.
   *
   * <p>Admitted now, and priced as a winner by both the cost comparison and the strategy picker,
   * which is the point: the estimates alone reach this verdict.
   */
  @Test
  public void testScanTheOldRuleWronglyRefusedIsAdmittedAndPreferred() {
    var costs = IndexOrderedCostModel.computeCosts(1000, 1_000_000L, 10, null, true);
    assertNotNull("a 10000-entry scan against 1000 loadable records must be admitted", costs);
    assertEquals(10_000.0, costs.expectedScanLength(), 1.0);
    assertTrue(
        "the model prices this scan below load-and-sort: unionScan="
            + costs.costUnionScan() + " loadSort=" + costs.costLoadSort(),
        costs.costUnionScan() < costs.costLoadSort());
    assertEquals(
        "and the strategy picker takes a scan",
        MultiSourceStrategy.UNION_RIDSET_SCAN,
        IndexOrderedCostModel.pickMultiSourceStrategy(1000, 1_000_000L, 10, null, true));
  }

  /**
   * Catastrophic sparse membership still loses without a dominance gate: 240,000 entries to
   * find ten rows where the alternative reads 100 records. Removing the gate must not hand
   * that shape back to the scan — the two cost estimates already separate by a wide margin.
   */
  @Test
  public void testCatastrophicScanStaysRefusedWithoutADominanceGate() {
    assertEquals(
        "240000 entries against 100 loadable records must load and sort",
        MultiSourceStrategy.LOAD_ALL_SORT,
        IndexOrderedCostModel.pickMultiSourceStrategy(100, 2_400_000L, 10, null, false));

    var costs = IndexOrderedCostModel.computeCosts(100, 2_400_000L, 10, null, false, 2);
    assertNotNull(costs);
    assertTrue(
        "the estimates alone separate by more than an order of magnitude; unionScan="
            + costs.costUnionScan() + " loadSort=" + costs.costLoadSort(),
        costs.costUnionScan() / costs.costLoadSort() > 10);
  }

  /**
   * Whether the model prefers load-and-sort over the ordered scan for one set of inputs. A null
   * estimate counts as a preference for loading, because every caller reads null that way.
   *
   * <p>Read off the two cost estimates rather than off an admission gate, which is where the
   * verdict now lives: the three monotonicity tests below pin the SHAPE of that verdict across a
   * sweep, so they keep holding when the constants move.
   */
  private static boolean prefersLoadAndSort(
      int reachableEdges, long indexSize, long limit) {
    var costs = IndexOrderedCostModel.computeCosts(
        reachableEdges, indexSize, limit, null, true);
    return costs == null || costs.costLoadSort() <= costs.costUnionScan();
  }

  /**
   * MONOTONIC IN INDEX SIZE. Holding the reachable edge count and the LIMIT fixed, a larger
   * index means a sparser reachable set and a longer scan per row, so the scan must never win
   * again once it has lost. The sweep below crosses the boundary between 10,000 and 100,000
   * entries and stays on the loading side.
   */
  @Test
  public void testTheVerdictIsMonotonicInIndexSize() {
    var edges = 100;
    var limit = 10L;
    var loadingFrom = -1L;
    for (long indexSize = 100; indexSize <= 100_000_000L; indexSize *= 10) {
      var loading = prefersLoadAndSort(edges, indexSize, limit);
      if (loading && loadingFrom < 0) {
        loadingFrom = indexSize;
      }
      if (loadingFrom >= 0) {
        assertTrue(
            "once loading wins at index size " + loadingFrom + ", size " + indexSize
                + " must keep loading",
            loading);
      }
    }
    assertTrue("a large enough index must eventually prefer loading", loadingFrom > 0);
  }

  /**
   * MONOTONIC IN REACHABLE EDGES. Holding the index size and the LIMIT fixed, more reachable
   * targets mean a denser scan region, so the scan must never lose again once it has won. Over a
   * 10,000-entry index with {@code LIMIT 10} the boundary sits near 50 edges; the sweep steps
   * across it with room on either side, so the test states the ordering and not the constants.
   */
  @Test
  public void testTheVerdictIsMonotonicInReachableEdges() {
    long indexSize = 10_000;
    long limit = 10;
    var scanningFrom = -1;
    for (var edges = 10; edges <= 5120; edges *= 2) {
      var scanning = !prefersLoadAndSort(edges, indexSize, limit);
      if (scanning && scanningFrom < 0) {
        scanningFrom = edges;
      }
      if (scanningFrom >= 0) {
        assertTrue(
            "once the scan wins at " + scanningFrom + " edges, " + edges
                + " edges must keep scanning",
            scanning);
      }
    }
    assertTrue("a dense enough reachable set must eventually scan", scanningFrom > 0);
    assertTrue("and 10 edges over a 10000-entry index are far too sparse to scan",
        prefersLoadAndSort(10, indexSize, limit));
  }

  /**
   * MONOTONIC IN LIMIT. A larger LIMIT lengthens the expected scan while leaving the loadable
   * record count untouched, so the scan must only ever lose ground as the LIMIT grows. With 100
   * edges over a 100,000-entry index the boundary sits between {@code LIMIT 5} and
   * {@code LIMIT 8}.
   */
  @Test
  public void testTheVerdictIsMonotonicInLimit() {
    var edges = 100;
    long indexSize = 100_000;
    assertTrue("LIMIT 1 is short enough to scan",
        !prefersLoadAndSort(edges, indexSize, 1));
    assertTrue("LIMIT 5 still scans",
        !prefersLoadAndSort(edges, indexSize, 5));
    assertTrue("LIMIT 8 has crossed over to loading",
        prefersLoadAndSort(edges, indexSize, 8));
    assertTrue("and a larger LIMIT keeps loading",
        prefersLoadAndSort(edges, indexSize, 50));
    assertTrue("as does a LIMIT past the reachable set",
        prefersLoadAndSort(edges, indexSize, 100));
  }

  /**
   * Single- and multi-source estimates share {@link IndexOrderedCostModel#scanCostPerEntry()}.
   * The default factor is 5 (page amort + 5×cpu for lock, bitmap, filter, compare, advance).
   * Changing it moves the scan-vs-load boundary for every ordered top-N plan.
   */
  @Test
  public void testPerEntryCursorTermsShareScanCostPerEntry() {
    var perEntry = IndexOrderedCostModel.scanCostPerEntry();
    assertEquals(1.0 / 200 + 5 * 0.01, perEntry, 1e-9);
    assertEquals(
        5.0,
        GlobalConfiguration.QUERY_INDEX_ORDERED_SCAN_CPU_FACTOR.getValueAsDouble(),
        1e-9);

    var costs = IndexOrderedCostModel.computeCosts(100, 2_400_000L, 10, null, true);
    assertNotNull(costs);
    assertTrue(
        "computeCosts union estimate carries scanCostPerEntry",
        costs.costUnionScan() > costs.expectedScanLength() * perEntry);

    // Uncapped true density=1.0 + small LIMIT still prefers an index strategy at default factor.
    assertEquals(
        "a dense small-LIMIT shape scans rather than sorts",
        MultiSourceStrategy.GLOBAL_SCAN,
        IndexOrderedCostModel.pickMultiSourceStrategy(500, 500, 5, null, true, false));
  }

  /**
   * Fan-out for FILTERED admission must not be {@code indexSize/sourceEstimate}: that saturates
   * density at 1.0 on a large index. With default fan-out only, a small source against a huge
   * index stays sparse and loses to load-and-sort.
   */
  @Test
  public void testDefaultFanOutDoesNotSaturateDensityOnLargeIndex() {
    int defaultFanOut =
        GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.getValueAsInteger();
    // sourceEstimate=10, fanOut=default only → edges = 10 * defaultFanOut
    int estimatedEdges = 10 * defaultFanOut;
    long indexSize = 2_400_000L;
    var costs = IndexOrderedCostModel.computeCosts(
        estimatedEdges, indexSize, 20, null, false);
    // Either refused (null / load wins) — must not look like density 1.0 (scan length ≈ LIMIT).
    if (costs != null) {
      assertTrue(
          "unsaturated fan-out must not collapse expected scan to ~LIMIT; got "
              + costs.expectedScanLength(),
          costs.expectedScanLength() > 100);
      assertTrue(
          "sparse product should prefer load-and-sort",
          costs.costLoadSort() <= costs.costUnionScan());
    }
  }

  /**
   * The strategy picker reaches the same verdict as the cost comparison on a sparse shape: both
   * put load-and-sort ahead of either scan, with no gate involved.
   */
  @Test
  public void testStrategyPickerAgreesOnASparseShape() {
    assertEquals(
        MultiSourceStrategy.LOAD_ALL_SORT,
        IndexOrderedCostModel.pickMultiSourceStrategy(100, 2_400_000L, 10, null, true));
  }
}
