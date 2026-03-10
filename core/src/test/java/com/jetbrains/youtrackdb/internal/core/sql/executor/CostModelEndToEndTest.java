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

package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.index.engine.SelectivityEstimator;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.EdgeFanOutEstimator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end tests for the cost model integration across SELECT and MATCH
 * planners (Section 10.10).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Index seek is preferred over full scan at low selectivity</li>
 *   <li>Full scan is preferred over index seek at high selectivity</li>
 *   <li>MATCH root selection order reflects histogram-based estimates</li>
 *   <li>Histogram-based estimates improve over the old heuristics</li>
 *   <li>Cost model formulas produce correct relative ordering for
 *       known-suboptimal plans</li>
 * </ul>
 */
public class CostModelEndToEndTest {

  // Save original config values for restoration
  private Object origSeqPageRead;
  private Object origRandomPageRead;
  private Object origPerRowCpu;
  private Object origTreeDepth;

  private DatabaseSessionEmbedded session;
  private CommandContext ctx;
  private QueryStats queryStats;
  private ImmutableSchema schema;

  // Large table: 100,000 entries for meaningful cost comparison
  private static final long TABLE_SIZE = 100_000L;
  private static final long NDV = 10_000L;

  // Uniform histogram: 4 buckets over [0, 25000), [25000, 50000),
  // [50000, 75000), [75000, 100000]
  private IndexStatistics stats;
  private EquiDepthHistogram histogram;

  private Method estimateRootEntries;

  @Before
  public void setUp() throws Exception {
    origSeqPageRead = GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ
        .getValue();
    origRandomPageRead = GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ
        .getValue();
    origPerRowCpu = GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU
        .getValue();
    origTreeDepth = GlobalConfiguration.QUERY_STATS_DEFAULT_INDEX_TREE_DEPTH
        .getValue();

    session = mock(DatabaseSessionEmbedded.class);
    var sharedContext = mock(SharedContext.class);
    var indexManager = mock(IndexManagerEmbedded.class);
    queryStats = new QueryStats();
    when(session.getSharedContext()).thenReturn(sharedContext);
    when(sharedContext.getQueryStats()).thenReturn(queryStats);
    when(sharedContext.getIndexManager()).thenReturn(indexManager);
    when(indexManager.getIndex(any())).thenReturn(null);

    var metadata = mock(MetadataDefault.class);
    schema = mock(ImmutableSchema.class);
    when(session.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);

    ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(session);

    stats = new IndexStatistics(TABLE_SIZE, NDV, 0);
    histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 25000, 50000, 75000, 100000},
        new long[] {25000, 25000, 25000, 25000},
        new long[] {2500, 2500, 2500, 2500},
        TABLE_SIZE,
        null, 0);

    estimateRootEntries = MatchExecutionPlanner.class.getDeclaredMethod(
        "estimateRootEntries", Map.class, Map.class, Map.class,
        CommandContext.class);
    estimateRootEntries.setAccessible(true);
  }

  @After
  public void restoreDefaults() {
    GlobalConfiguration.QUERY_STATS_COST_SEQ_PAGE_READ
        .setValue(origSeqPageRead);
    GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ
        .setValue(origRandomPageRead);
    GlobalConfiguration.QUERY_STATS_COST_PER_ROW_CPU
        .setValue(origPerRowCpu);
    GlobalConfiguration.QUERY_STATS_DEFAULT_INDEX_TREE_DEPTH
        .setValue(origTreeDepth);
  }

  // ── Index seek preferred at low selectivity ───────────────────

  @Test
  public void indexSeekPreferredOverScanAtLowSelectivity() {
    // Scenario: equality lookup on a large table with low selectivity.
    // The index seek (fetching ~10 rows) should be cheaper than a full scan.
    double selectivity =
        SelectivityEstimator.estimateEquality(stats, histogram, 42000);
    long estimatedRows = Math.max(1, (long) (TABLE_SIZE * selectivity));

    double seekCost = CostModel.indexEqualityCost(estimatedRows);
    double scanCost = CostModel.fullClassScanCost(TABLE_SIZE);

    assertTrue(
        "Index seek (" + seekCost + ") should be cheaper than full scan ("
            + scanCost + ") at low selectivity",
        seekCost < scanCost);
  }

  @Test
  public void indexRangeSeekPreferredOverScanForNarrowRange() {
    // Scenario: narrow range query (~1% of data) should prefer index.
    // With defaults: scan cost = 100k×0.01 + (100k/40)×1.0 = 3500
    // Range scan (1% = 1000 rows): 16 + 1000×1.0 = 1016 → cheaper.
    double selectivity =
        SelectivityEstimator.estimateRange(
            stats, histogram, 40000, 41000, true, false);
    long estimatedRows = Math.max(1, (long) (TABLE_SIZE * selectivity));

    double rangeCost = CostModel.indexRangeCost(estimatedRows);
    double scanCost = CostModel.fullClassScanCost(TABLE_SIZE);

    assertTrue(
        "Index range scan (" + rangeCost + ") should be cheaper than "
            + "full scan (" + scanCost + ") for a narrow range",
        rangeCost < scanCost);
  }

  @Test
  public void indexSearchDescriptorPicksSeekForPointLookup() {
    // Scenario: IndexSearchDescriptor.cost() returns a value lower than
    // the full scan cost for a point lookup (equality on indexed field).
    var index = mockIndex("idx_val", "val", stats, histogram);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("val", new SQLEqualsOperator(-1), 42000),
        null, null);

    int cost = desc.cost(ctx);
    double scanCost = CostModel.fullClassScanCost(TABLE_SIZE);

    assertTrue(
        "IndexSearchDescriptor cost (" + cost + ") should be below "
            + "full scan cost (" + scanCost + ")",
        cost < scanCost);
  }

  // ── Scan preferred at high selectivity ────────────────────────

  @Test
  public void scanPreferredOverIndexSeekAtHighSelectivity() {
    // Scenario: fetching 90% of a large table via random page reads is
    // more expensive than a sequential full scan.
    long rowsToFetch = (long) (TABLE_SIZE * 0.9);

    double seekCost = CostModel.indexEqualityCost(rowsToFetch);
    double scanCost = CostModel.fullClassScanCost(TABLE_SIZE);

    assertTrue(
        "Full scan (" + scanCost + ") should be cheaper than index "
            + "seek (" + seekCost + ") when fetching 90% of rows",
        scanCost < seekCost);
  }

  @Test
  public void scanPreferredOverIndexRangeForWideRange() {
    // Scenario: a wide range covering 80% of the table is cheaper via scan.
    double selectivity =
        SelectivityEstimator.estimateRange(
            stats, histogram, 0, 80000, true, false);
    long estimatedRows = Math.max(1, (long) (TABLE_SIZE * selectivity));

    double rangeCost = CostModel.indexRangeCost(estimatedRows);
    double scanCost = CostModel.fullClassScanCost(TABLE_SIZE);

    assertTrue(
        "Full scan (" + scanCost + ") should be cheaper than index "
            + "range scan (" + rangeCost + ") for 80% selectivity",
        scanCost < rangeCost);
  }

  @Test
  public void indexSearchDescriptorHighCostForWideRange() {
    // Scenario: IndexSearchDescriptor.cost() for a wide range (covering
    // most of the table) should produce a cost higher than a scan.
    var index = mockIndex("idx_val", "val", stats, histogram);

    // Range covering ~75% of the table: val >= 0 AND val < 75000
    var lower = binaryCondition("val", new SQLGeOperator(-1), 0);
    var upper = binaryCondition("val", new SQLLtOperator(-1), 75000);
    var desc = new IndexSearchDescriptor(index, lower, upper, null);

    int cost = desc.cost(ctx);
    double scanCost = CostModel.fullClassScanCost(TABLE_SIZE);

    assertTrue(
        "Wide range index cost (" + cost + ") should exceed "
            + "full scan cost (" + scanCost + ")",
        cost > scanCost);
  }

  // ── Crossover point: seek vs scan ─────────────────────────────

  @Test
  public void crossoverPoint_seekCheaperBelowThresholdScanAbove() {
    // Verify there exists a sensible crossover: for small result sets,
    // seek wins; for large result sets, scan wins. With defaults:
    // scan cost = 100k×0.01 + (100k/40)×1.0 = 3500
    // Equality seek (N rows) = 16 + N×4.0
    // Crossover: 16 + N×4 = 3500 → N ≈ 871
    // So at ~0.87% selectivity the costs break even.
    double scanCost = CostModel.fullClassScanCost(TABLE_SIZE);

    // At 0.1% selectivity (100 rows): seek should win
    long smallResult = (long) (TABLE_SIZE * 0.001);
    assertTrue(
        "0.1% selectivity: seek should beat scan",
        CostModel.indexEqualityCost(smallResult) < scanCost);

    // At 50% selectivity: scan should win
    long halfResult = (long) (TABLE_SIZE * 0.50);
    assertTrue(
        "50% selectivity: scan should beat seek",
        scanCost < CostModel.indexEqualityCost(halfResult));
  }

  // ── MATCH root selection order ────────────────────────────────

  @Test
  public void matchRootSelection_smallerEstimateIsPreferredRoot()
      throws Exception {
    // Scenario: two MATCH aliases with different cardinalities.
    // The alias with fewer estimated entries should appear first in the
    // root ordering (lower weight = earlier root).
    mockSchemaClass("Person", 10_000L);
    mockSchemaClass("City", 50L);

    var aliasClasses = new LinkedHashMap<String, String>();
    aliasClasses.put("p", "Person");
    aliasClasses.put("c", "City");

    var result = invokeEstimateRootEntries(
        aliasClasses, Map.of(), Map.of());

    // City (50) should have a lower estimate than Person (10000).
    assertTrue(
        "City (50) should have lower estimate than Person (10000)",
        result.get("c") < result.get("p"));
  }

  @Test
  public void matchRootSelection_filteredAliasPreferredOverUnfiltered()
      throws Exception {
    // Scenario: Person has 10k records, but a selective filter reduces
    // the estimate. City has 200 records with no filter.
    // The filtered Person alias should beat unfiltered City if the
    // estimate is lower.
    mockSchemaClass("Person", 10_000L);
    mockSchemaClass("City", 200L);

    var filter = mock(SQLWhereClause.class);
    // Histogram-based estimate: only 50 records match the filter.
    when(filter.estimate(any(), anyLong(), any())).thenReturn(50L);

    var aliasClasses = new LinkedHashMap<String, String>();
    aliasClasses.put("p", "Person");
    aliasClasses.put("c", "City");

    var result = invokeEstimateRootEntries(
        aliasClasses, Map.of(), Map.of("p", filter));

    // Filtered Person (50) should beat unfiltered City (200).
    assertEquals(50L, (long) result.get("p"));
    assertEquals(200L, (long) result.get("c"));
    assertTrue(
        "Filtered Person (50) should be preferred root over City (200)",
        result.get("p") < result.get("c"));
  }

  @Test
  public void matchRootSelection_ridAlwaysPreferred() throws Exception {
    // Scenario: a RID alias always has estimate 1, so it is always the
    // preferred root — even over a small class.
    mockSchemaClass("City", 10L);

    var aliasClasses = new LinkedHashMap<String, String>();
    aliasClasses.put("c", "City");
    var aliasRids = new HashMap<String, SQLRid>();
    aliasRids.put("r", mock(SQLRid.class));

    var result = invokeEstimateRootEntries(
        aliasClasses, aliasRids, Map.of());

    assertEquals(1L, (long) result.get("r"));
    assertTrue(
        "RID (1) should be preferred over City (10)",
        result.get("r") < result.get("c"));
  }

  @Test
  public void matchRootSelection_multipleFilteredAliasesOrderedByEstimate()
      throws Exception {
    // Scenario: three aliases, all with different histogram-based
    // estimates. The root ordering should sort by ascending estimate.
    mockSchemaClass("Person", 100_000L);
    mockSchemaClass("City", 1_000L);
    mockSchemaClass("Country", 500L);

    var filterPerson = mock(SQLWhereClause.class);
    when(filterPerson.estimate(any(), anyLong(), any())).thenReturn(100L);

    var filterCity = mock(SQLWhereClause.class);
    when(filterCity.estimate(any(), anyLong(), any())).thenReturn(500L);

    // Country has no filter → uses raw class count of 500.

    var aliasClasses = new LinkedHashMap<String, String>();
    aliasClasses.put("p", "Person");
    aliasClasses.put("c", "City");
    aliasClasses.put("co", "Country");

    var aliasFilters = new HashMap<String, SQLWhereClause>();
    aliasFilters.put("p", filterPerson);
    aliasFilters.put("c", filterCity);

    var result = invokeEstimateRootEntries(
        aliasClasses, Map.of(), aliasFilters);

    // Person(100) < City(500) = Country(500)
    assertEquals(100L, (long) result.get("p"));
    assertEquals(500L, (long) result.get("c"));
    assertEquals(500L, (long) result.get("co"));
    assertTrue(
        "Filtered Person should have lowest estimate",
        result.get("p") < result.get("c"));
  }

  // ── Histogram estimates improve over heuristics ───────────────

  @Test
  public void histogramEstimateBetterThanCountDividedByTwo() {
    // Scenario: the old heuristic was count/2 for filtered classes.
    // With histograms, a selective equality on a unique column should
    // produce an estimate much lower than count/2.
    long heuristicEstimate = TABLE_SIZE / 2; // 50,000

    // Histogram-based equality estimate: 1/NDV * TABLE_SIZE = 10
    double selectivity =
        SelectivityEstimator.estimateEquality(stats, histogram, 42000);
    long histogramEstimate = Math.max(1, (long) (TABLE_SIZE * selectivity));

    assertTrue(
        "Histogram estimate (" + histogramEstimate + ") should be much "
            + "lower than count/2 heuristic (" + heuristicEstimate + ")",
        histogramEstimate < heuristicEstimate);
    // At least 10× improvement for a table with 10k distinct values.
    assertTrue(
        "Histogram should be at least 10x better",
        histogramEstimate * 10 < heuristicEstimate);
  }

  @Test
  public void histogramRangeEstimateBetterThanHeuristic() {
    // Scenario: range query on 10% of data. Heuristic is count/2.
    // Histogram should give ~10% of count.
    long heuristicEstimate = TABLE_SIZE / 2;

    double selectivity =
        SelectivityEstimator.estimateRange(
            stats, histogram, 0, 10000, true, false);
    long histogramEstimate = Math.max(1, (long) (TABLE_SIZE * selectivity));

    assertTrue(
        "Histogram range estimate (" + histogramEstimate + ") should be "
            + "closer to 10% than count/2 (" + heuristicEstimate + ")",
        histogramEstimate < heuristicEstimate);

    // The estimate should be roughly 10% (10,000) ± tolerance.
    assertTrue(
        "Histogram range estimate should be roughly 10k",
        histogramEstimate > 5000 && histogramEstimate < 15000);
  }

  // ── Cost model consistency for known-suboptimal plans ─────────

  @Test
  public void indexRangeCheaperThanEqualityForSameResultSize() {
    // When the same number of rows is returned, index equality (random
    // reads) is more expensive than range (sequential reads) for the
    // I/O component, but both include seek cost. For larger result sets,
    // range should be cheaper due to sequential I/O.
    long rows = 1000;
    double eqCost = CostModel.indexEqualityCost(rows);
    double rangeCost = CostModel.indexRangeCost(rows);

    assertTrue(
        "Range scan (" + rangeCost + ") should be cheaper than "
            + "equality seek (" + eqCost + ") for same row count",
        rangeCost < eqCost);
  }

  @Test
  public void edgeTraversalCostScalesWithFanOutAndSourceRows() {
    // Edge traversal cost should increase with both source rows and
    // fan-out. Verify the relative ordering.
    double costLowFanOut = CostModel.edgeTraversalCost(100, 2.0);
    double costHighFanOut = CostModel.edgeTraversalCost(100, 20.0);
    double costFewSources = CostModel.edgeTraversalCost(10, 10.0);
    double costManySources = CostModel.edgeTraversalCost(1000, 10.0);

    assertTrue(
        "Higher fan-out should increase cost",
        costHighFanOut > costLowFanOut);
    assertTrue(
        "More source rows should increase cost",
        costManySources > costFewSources);
  }

  @Test
  public void cheapEdgeCostPreferredInMatchSchedule() {
    // Scenario: when choosing between two edges from the same source
    // node, the planner should prefer the cheaper one (lower fan-out).
    // Verify the cost ordering matches expectations.
    mockSchemaClass("Person", 1000);
    mockSchemaClass("City", 100);
    mockEdgeClassWithVertexLinks("Knows", 5000, "Person", "Person");
    mockEdgeClassWithVertexLinks("LivesIn", 1000, "Person", "City");

    // .out('Knows') from Person: fanOut = 5000/1000 = 5.0
    double knowsFanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.OUT, "Person", "Person");
    double knowsCost = CostModel.edgeTraversalCost(100, knowsFanOut);

    // .out('LivesIn') from Person: fanOut = 1000/1000 = 1.0
    double livesInFanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "LivesIn", "Person", Direction.OUT, "Person", "City");
    double livesInCost = CostModel.edgeTraversalCost(100, livesInFanOut);

    assertTrue(
        "LivesIn (fanOut=1) should be cheaper than Knows (fanOut=5)",
        livesInCost < knowsCost);
  }

  // ── Configuration tuning affects cost decisions ───────────────

  @Test
  public void configTuning_higherRandomPageCostFavorsRangeOverEquality() {
    // When random page read cost increases, equality seeks become
    // relatively more expensive, making range scans more attractive.
    long rows = 100;

    // Default: random=4.0, seq=1.0
    double defaultEqCost = CostModel.indexEqualityCost(rows);
    double defaultRangeCost = CostModel.indexRangeCost(rows);
    double defaultRatio = defaultEqCost / defaultRangeCost;

    // Increase random page read cost
    GlobalConfiguration.QUERY_STATS_COST_RANDOM_PAGE_READ.setValue(16.0);

    double tunedEqCost = CostModel.indexEqualityCost(rows);
    double tunedRangeCost = CostModel.indexRangeCost(rows);
    double tunedRatio = tunedEqCost / tunedRangeCost;

    assertTrue(
        "Higher random page cost should widen equality/range gap",
        tunedRatio > defaultRatio);
  }

  @Test
  public void configTuning_shallowerTreeReducesSeekCost() {
    // A shallower tree depth reduces index seek cost, making index
    // lookups cheaper for small result sets.
    long rows = 1;

    // Default: depth=4, random=4.0 → seek=16.0
    double defaultCost = CostModel.indexEqualityCost(rows);

    // Reduce tree depth to 2 → seek=8.0
    GlobalConfiguration.QUERY_STATS_DEFAULT_INDEX_TREE_DEPTH.setValue(2);
    double shallowCost = CostModel.indexEqualityCost(rows);

    assertTrue(
        "Shallower tree (" + shallowCost + ") should be cheaper than "
            + "deeper tree (" + defaultCost + ")",
        shallowCost < defaultCost);
  }

  // ── Descriptor cost ranking with two indexes ──────────────────

  @Test
  public void selectiveIndexPreferredOverNonSelective() {
    // Scenario: two indexes on the same table. Index A has a histogram
    // showing the queried value is rare (low selectivity). Index B has
    // no histogram (falls back to MAX_VALUE). The planner should prefer
    // Index A (lower cost).
    var indexA = mockIndex("idx_a", "rare_col", stats, histogram);
    var indexB = mockIndex("idx_b", "other_col", null, null);

    var descA = new IndexSearchDescriptor(
        indexA,
        binaryCondition("rare_col", new SQLEqualsOperator(-1), 42000),
        null, null);
    var descB = new IndexSearchDescriptor(
        indexB,
        binaryCondition("other_col", new SQLEqualsOperator(-1), 1),
        null, null);

    int costA = descA.cost(ctx);
    int costB = descB.cost(ctx);

    assertTrue(
        "Index A with histogram (" + costA + ") should be preferred "
            + "over Index B without stats (" + costB + ")",
        costA < costB);
    assertEquals(
        "Index B without stats should return MAX_VALUE",
        Integer.MAX_VALUE, costB);
  }

  @Test
  public void narrowRangeIndexPreferredOverWideRange() {
    // Scenario: two range queries on the same index. The narrow range
    // should have lower cost.
    var index = mockIndex("idx_val", "val", stats, histogram);

    // Narrow range: 5% of data
    var narrowLower = binaryCondition("val", new SQLGeOperator(-1), 40000);
    var narrowUpper = binaryCondition("val", new SQLLtOperator(-1), 45000);
    var narrowDesc = new IndexSearchDescriptor(
        index, narrowLower, narrowUpper, null);

    // Wide range: 75% of data
    var wideLower = binaryCondition("val", new SQLGeOperator(-1), 0);
    var wideUpper = binaryCondition("val", new SQLLtOperator(-1), 75000);
    var wideDesc = new IndexSearchDescriptor(
        index, wideLower, wideUpper, null);

    int narrowCost = narrowDesc.cost(ctx);
    int wideCost = wideDesc.cost(ctx);

    assertTrue(
        "Narrow range (" + narrowCost + ") should be cheaper than "
            + "wide range (" + wideCost + ")",
        narrowCost < wideCost);
  }

  // ── Edge fan-out combined with root cardinality ───────────────

  @Test
  public void edgeCostCombinesSourceCardinalityAndFanOut() {
    // Scenario: traversal cost = sourceRows × fanOut × randomPageRead.
    // Verify that starting from a smaller root (lower sourceRows) gives
    // a cheaper edge traversal.
    mockSchemaClass("Person", 10_000);
    mockEdgeClassWithVertexLinks("Knows", 50_000, "Person", "Person");

    // Compute fan-out via public API
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.OUT, "Person", "Person");

    // Small root: 10 rows
    double costSmallRoot = CostModel.edgeTraversalCost(10, fanOut);

    // Large root: 5000 rows
    double costLargeRoot = CostModel.edgeTraversalCost(5000, fanOut);

    assertTrue(
        "Small root traversal (" + costSmallRoot + ") should be cheaper "
            + "than large root (" + costLargeRoot + ")",
        costSmallRoot < costLargeRoot);
    // Cost should scale linearly with source rows.
    assertEquals(
        "Cost should scale 500x with source row ratio",
        costLargeRoot / costSmallRoot, 500.0, 0.001);
  }

  // ── Zero-estimate boundary ─────────────────────────────────────

  @Test
  public void zeroSelectivityClampedToOneRow() {
    // Scenario: an out-of-range equality produces near-zero selectivity.
    // The Math.max(1, ...) clamp in estimateFromHistogram ensures cost
    // is never based on zero rows — it should equal indexEqualityCost(1).
    var index = mockIndex("idx_val", "val", stats, histogram);

    // Value far outside histogram range [0, 100000] — selectivity
    // is 1/nonNullCount ≈ 0.00001, so TABLE_SIZE * sel ≈ 1.
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("val", new SQLEqualsOperator(-1), 999_999),
        null, null);

    int cost = desc.cost(ctx);

    // Cost should equal indexEqualityCost(1) = seekCost + 1×randomPageRead
    assertEquals(
        "Out-of-range value should produce cost for 1 row",
        (int) CostModel.indexEqualityCost(1), cost);
  }

  // ── Helpers ───────────────────────────────────────────────────

  private Index mockIndex(
      String name, String field,
      IndexStatistics indexStats, EquiDepthHistogram hist) {
    var indexDef = mock(IndexDefinition.class);
    when(indexDef.getProperties()).thenReturn(List.of(field));

    var index = mock(Index.class);
    when(index.getName()).thenReturn(name);
    when(index.getDefinition()).thenReturn(indexDef);
    when(index.getStatistics(session)).thenReturn(indexStats);
    when(index.getHistogram(session)).thenReturn(hist);
    return index;
  }

  private SchemaClassInternal mockSchemaClass(String name, long count) {
    var clazz = mock(SchemaClassInternal.class);
    when(schema.existsClass(name)).thenReturn(true);
    when(schema.getClassInternal(name)).thenReturn(clazz);
    when(schema.getClass(name)).thenReturn(clazz);
    when(clazz.approximateCount(any(DatabaseSessionEmbedded.class)))
        .thenReturn(count);
    when(clazz.getName()).thenReturn(name);
    when(clazz.isSubClassOf(name)).thenReturn(true);
    return clazz;
  }

  private void mockEdgeClassWithVertexLinks(
      String edgeClassName, long edgeCount,
      String outVertexClass, String inVertexClass) {
    var edgeClass = mockSchemaClass(edgeClassName, edgeCount);

    if (outVertexClass != null) {
      var outProp = mock(SchemaPropertyInternal.class);
      var outClass = schema.getClassInternal(outVertexClass);
      if (outClass == null) {
        outClass = mockSchemaClass(outVertexClass, 0);
      }
      when(outProp.getLinkedClass()).thenReturn(outClass);
      when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);
    }

    if (inVertexClass != null) {
      var inProp = mock(SchemaPropertyInternal.class);
      var inClass = schema.getClassInternal(inVertexClass);
      if (inClass == null) {
        inClass = mockSchemaClass(inVertexClass, 0);
      }
      when(inProp.getLinkedClass()).thenReturn(inClass);
      when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Long> invokeEstimateRootEntries(
      Map<String, String> aliasClasses,
      Map<String, SQLRid> aliasRids,
      Map<String, SQLWhereClause> aliasFilters) throws Exception {
    try {
      return (Map<String, Long>) estimateRootEntries.invoke(
          null, aliasClasses, aliasRids, aliasFilters, ctx);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw e;
    }
  }

  private SQLBinaryCondition binaryCondition(
      String fieldName, SQLBinaryCompareOperator op, Object value) {
    var bc = new SQLBinaryCondition(-1);
    bc.setLeft(fieldExpr(fieldName));
    bc.setOperator(op);
    bc.setRight(valueExpr(value));
    return bc;
  }

  private SQLExpression fieldExpr(String name) {
    var expr = mock(SQLExpression.class);
    when(expr.isBaseIdentifier()).thenReturn(true);
    when(expr.toString()).thenReturn(name);
    // Mock getDefaultAlias() for field matching via
    // getDefaultAlias().getStringValue() (unquoted identifier comparison).
    var alias = mock(SQLIdentifier.class);
    when(alias.getStringValue()).thenReturn(name);
    when(expr.getDefaultAlias()).thenReturn(alias);
    return expr;
  }

  private SQLExpression valueExpr(Object value) {
    var expr = mock(SQLExpression.class);
    when(expr.isEarlyCalculated(any())).thenReturn(true);
    when(expr.execute(
        nullable(Result.class), any(CommandContext.class))).thenReturn(value);
    when(expr.execute(
        nullable(Identifiable.class),
        any(CommandContext.class))).thenReturn(value);
    when(expr.isBaseIdentifier()).thenReturn(false);
    return expr;
  }
}
