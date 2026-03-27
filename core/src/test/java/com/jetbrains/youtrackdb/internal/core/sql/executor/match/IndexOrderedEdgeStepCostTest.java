package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.IndexOrderedEdgeStep.MultiSourceStrategy;
import org.junit.Test;

/**
 * Unit tests for the cost-based strategy selection in {@link IndexOrderedEdgeStep}.
 * Tests the pure static methods directly — no database or index required.
 */
public class IndexOrderedEdgeStepCostTest {

  // ---- computeCostsStatic: threshold guards ----

  // LinkBag below MIN_LINKBAG threshold (default 10) → null (skip index scan)
  @Test
  public void testComputeCostsReturnsNullBelowMinLinkBag() {
    var result = IndexOrderedEdgeStep.computeCostsStatic(
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
    var result = IndexOrderedEdgeStep.computeCostsStatic(
        100, 0, 10, null, true);
    assertNull("Should return null when indexSize <= 0", result);
  }

  // ---- computeCostsStatic: basic cost computation ----

  // With linkBag=100, indexSize=1000, limit=10: should produce valid costs
  @Test
  public void testComputeCostsBasic() {
    var result = IndexOrderedEdgeStep.computeCostsStatic(
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
    var result = IndexOrderedEdgeStep.computeCostsStatic(
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
    var costs = IndexOrderedEdgeStep.computeCostsStatic(
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
    var costs = IndexOrderedEdgeStep.computeCostsStatic(
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
    var strategy = IndexOrderedEdgeStep.pickMultiSourceStrategyStatic(
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
    var strategy = IndexOrderedEdgeStep.pickMultiSourceStrategyStatic(
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
    var strategy = IndexOrderedEdgeStep.pickMultiSourceStrategyStatic(
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
    var strategy = IndexOrderedEdgeStep.pickMultiSourceStrategyStatic(
        1000, // totalEdges
        10_000, // indexSize
        20, // limit
        null,
        true);
    assertEquals(
        "Medium density: UNION should beat GLOBAL (bitmap filter avoids random reads)",
        MultiSourceStrategy.UNION_RIDSET_SCAN, strategy);
  }
}
