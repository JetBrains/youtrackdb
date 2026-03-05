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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.index.engine.SelectivityEstimator;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the histogram-based cost estimation fallback in
 * {@link IndexSearchDescriptor#cost(CommandContext)}.
 *
 * <p>Verifies that when {@link QueryStats} has no data (returns -1),
 * cost() falls back to persistent histogram-based estimation using
 * {@link SelectivityEstimator}. Also tests combined range conditions,
 * composite index handling, and various fallback paths.
 */
public class IndexSearchDescriptorCostTest {

  private DatabaseSessionEmbedded session;
  private CommandContext ctx;
  private QueryStats queryStats;
  private Index index;

  // Uniform histogram: 4 buckets [0,25), [25,50), [50,75), [75,100]
  // 250 entries/bucket, 50 distinct/bucket, 1000 total non-null entries
  private IndexStatistics stats;
  private EquiDepthHistogram histogram;

  @Before
  public void setUp() {
    session = mock(DatabaseSessionEmbedded.class);
    var sharedContext = mock(SharedContext.class);
    var indexManager = mock(IndexManagerEmbedded.class);
    queryStats = new QueryStats();
    when(session.getSharedContext()).thenReturn(sharedContext);
    when(sharedContext.getQueryStats()).thenReturn(queryStats);
    // QueryStats.getIndexStats() checks unique indexes via IndexManager
    when(sharedContext.getIndexManager()).thenReturn(indexManager);
    when(indexManager.getIndex(any())).thenReturn(null);

    ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(session);

    var indexDef = mock(IndexDefinition.class);
    when(indexDef.getProperties()).thenReturn(List.of("age"));

    index = mock(Index.class);
    when(index.getName()).thenReturn("idx_age");
    when(index.getDefinition()).thenReturn(indexDef);

    stats = new IndexStatistics(1000, 200, 0);
    histogram = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{250, 250, 250, 250},
        new long[]{50, 50, 50, 50},
        1000,
        null, 0);

    when(index.getStatistics(session)).thenReturn(stats);
    when(index.getHistogram(session)).thenReturn(histogram);
  }

  // ── QueryStats takes priority ──────────────────────────────────

  @Test
  public void queryStatsAvailable_usesQueryStatsInsteadOfHistogram() {
    // Given: QueryStats has data for this index (returns 42)
    queryStats.pushIndexStats("idx_age", 1, false, false, 42L);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);

    // When
    var cost = desc.cost(ctx);

    // Then: should use QueryStats value, not histogram
    assertEquals(42, cost);
  }

  // ── Equality: f = X ──────────────────────────────────────────

  @Test
  public void equalityCondition_usesHistogramEstimation() {
    // Given: WHERE age = 30 (no QueryStats data)
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);

    var cost = desc.cost(ctx);

    // Then: cost = CostModel.indexEqualityCost(estimatedRows)
    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 30);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    int expectedCost = (int) CostModel.indexEqualityCost(estimatedRows);
    assertEquals(expectedCost, cost);
  }

  // ── Greater-than: f > X ──────────────────────────────────────

  @Test
  public void greaterThanCondition_usesHistogramEstimation() {
    // Given: WHERE age > 60
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLGtOperator(-1), 60),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateGreaterThan(stats, histogram, 60);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── Less-than: f < X ────────────────────────────────────────

  @Test
  public void lessThanCondition_usesHistogramEstimation() {
    // Given: WHERE age < 30
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLLtOperator(-1), 30),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateLessThan(stats, histogram, 30);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── Greater-or-equal: f >= X ─────────────────────────────────

  @Test
  public void greaterOrEqualCondition_usesHistogramEstimation() {
    // Given: WHERE age >= 80
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLGeOperator(-1), 80),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateGreaterOrEqual(stats, histogram, 80);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── Less-or-equal: f <= X ───────────────────────────────────

  @Test
  public void lessOrEqualCondition_usesHistogramEstimation() {
    // Given: WHERE age <= 20
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLLeOperator(-1), 20),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateLessOrEqual(stats, histogram, 20);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── IN: f IN (v1, v2, ...) ──────────────────────────────────

  @Test
  public void inOperator_usesHistogramEstimation() {
    // Given: WHERE age IN [10, 30, 70]
    var bc = new SQLBinaryCondition(-1);
    bc.setLeft(fieldExpr("age"));
    bc.setOperator(new SQLInOperator(-1));
    bc.setRight(valueExpr(List.of(10, 30, 70)));

    var desc = new IndexSearchDescriptor(index, bc, null, null);

    var cost = desc.cost(ctx);

    var expectedSel = SelectivityEstimator.estimateIn(
        stats, histogram, List.of(10, 30, 70));
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  // ── Combined range: f >= X AND f < Y ─────────────────────────

  @Test
  public void combinedRange_lowerThenUpper_usesRangeEstimation() {
    // Given: WHERE age >= 20 AND age < 60 (single-field, two bounds)
    var lower = binaryCondition("age", new SQLGeOperator(-1), 20);
    var upper = binaryCondition("age", new SQLLtOperator(-1), 60);

    var desc = new IndexSearchDescriptor(index, lower, upper, null);

    var cost = desc.cost(ctx);

    var expectedSel = SelectivityEstimator.estimateRange(
        stats, histogram, 20, 60, true, false);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  @Test
  public void combinedRange_upperThenLower_usesRangeEstimation() {
    // Given: WHERE age < 80 AND age > 30 (reversed order)
    var upper = binaryCondition("age", new SQLLtOperator(-1), 80);
    var lower = binaryCondition("age", new SQLGtOperator(-1), 30);

    var desc = new IndexSearchDescriptor(index, upper, lower, null);

    var cost = desc.cost(ctx);

    var expectedSel = SelectivityEstimator.estimateRange(
        stats, histogram, 30, 80, false, false);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  @Test
  public void combinedRange_inclusiveOnBothEnds() {
    // Given: WHERE age >= 25 AND age <= 75
    var lower = binaryCondition("age", new SQLGeOperator(-1), 25);
    var upper = binaryCondition("age", new SQLLeOperator(-1), 75);

    var desc = new IndexSearchDescriptor(index, lower, upper, null);

    var cost = desc.cost(ctx);

    var expectedSel = SelectivityEstimator.estimateRange(
        stats, histogram, 25, 75, true, true);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── Composite index: equality + range on non-leading field ────

  @Test
  public void compositeIndex_equalityOnLeadingField_defaultSelectivityForRest() {
    // Given: index on [age, name], WHERE age = 30 AND name > 'A'
    // Histogram is on the leading field "age" with integer boundaries.
    var compositeDef = mock(IndexDefinition.class);
    when(compositeDef.getProperties())
        .thenReturn(List.of("age", "name"));
    when(index.getDefinition()).thenReturn(compositeDef);

    var andBlock = new SQLAndBlock(-1);
    andBlock.addSubBlock(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));
    andBlock.addSubBlock(
        binaryCondition("name", new SQLGtOperator(-1), "A"));

    var desc = new IndexSearchDescriptor(index, andBlock, null, null);

    var cost = desc.cost(ctx);

    // Leading field (age = 30) uses histogram estimation.
    // Second field (name > 'A') uses defaultSelectivity().
    var leadingSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 30);
    var combinedSel = leadingSel * SelectivityEstimator.defaultSelectivity();
    long estimatedRows =
        Math.max(1, (long) (stats.totalCount() * combinedSel));
    // Equality on leading field → indexEqualityCost
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  @Test
  public void compositeIndex_multipleNonLeadingFields() {
    // Given: index on [a, b, c], WHERE a = 1 AND b = 2 AND c = 3
    var compositeDef = mock(IndexDefinition.class);
    when(compositeDef.getProperties())
        .thenReturn(List.of("a", "b", "c"));
    when(index.getDefinition()).thenReturn(compositeDef);

    var andBlock = new SQLAndBlock(-1);
    andBlock.addSubBlock(
        binaryCondition("a", new SQLEqualsOperator(-1), 1));
    andBlock.addSubBlock(
        binaryCondition("b", new SQLEqualsOperator(-1), 2));
    andBlock.addSubBlock(
        binaryCondition("c", new SQLEqualsOperator(-1), 3));

    var desc = new IndexSearchDescriptor(index, andBlock, null, null);

    var cost = desc.cost(ctx);

    // Leading field uses histogram; 2 non-leading fields each use default.
    var leadingSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 1);
    var combinedSel = leadingSel
        * SelectivityEstimator.defaultSelectivity()
        * SelectivityEstimator.defaultSelectivity();
    long estimatedRows =
        Math.max(1, (long) (stats.totalCount() * combinedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  // ── Additional range on composite index is NOT combined ───────

  @Test
  public void compositeIndex_additionalRangeOnNonLeadingField_ignored() {
    // Given: index on [age, name], WHERE age = 30 AND name >= 'A'
    // with additionalRange name < 'Z'. Since there are 2 sub-blocks,
    // the additional range is on a non-leading field — not combined.
    var compositeDef = mock(IndexDefinition.class);
    when(compositeDef.getProperties())
        .thenReturn(List.of("age", "name"));
    when(index.getDefinition()).thenReturn(compositeDef);

    var andBlock = new SQLAndBlock(-1);
    andBlock.addSubBlock(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));
    andBlock.addSubBlock(
        binaryCondition("name", new SQLGeOperator(-1), "A"));

    var additional = binaryCondition("name", new SQLLtOperator(-1), "Z");

    var desc = new IndexSearchDescriptor(index, andBlock, additional, null);

    var cost = desc.cost(ctx);

    // Leading field uses histogram; non-leading uses default.
    // Additional range is NOT combined (subBlocks.size() > 1).
    // additionalRange != null → isRangeEstimate returns true → range cost.
    var leadingSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 30);
    var combinedSel = leadingSel * SelectivityEstimator.defaultSelectivity();
    long estimatedRows =
        Math.max(1, (long) (stats.totalCount() * combinedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── Fallback paths ──────────────────────────────────────────

  @Test
  public void noStatistics_returnsMaxValue() {
    // Given: index returns null statistics
    when(index.getStatistics(session)).thenReturn(null);
    when(index.getHistogram(session)).thenReturn(null);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  @Test
  public void emptyStatistics_returnsMaxValue() {
    // Given: index has totalCount=0
    when(index.getStatistics(session))
        .thenReturn(new IndexStatistics(0, 0, 0));

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  @Test
  public void noHistogram_usesUniformEstimation() {
    // Given: stats available but no histogram
    when(index.getHistogram(session)).thenReturn(null);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);

    var cost = desc.cost(ctx);

    // Uniform equality: 1/NDV = 1/200 = 0.005; 1000 * 0.005 = 5
    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, null, 30);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  @Test
  public void nonEarlyCalculableValue_returnsMaxValue() {
    // Given: WHERE age = (complex expression)
    var bc = new SQLBinaryCondition(-1);
    bc.setLeft(fieldExpr("age"));
    bc.setOperator(new SQLEqualsOperator(-1));
    var complexExpr = mock(SQLExpression.class);
    when(complexExpr.isEarlyCalculated(any())).thenReturn(false);
    bc.setRight(complexExpr);

    var desc = new IndexSearchDescriptor(index, bc, null, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  @Test
  public void nullValueFromExpression_returnsMaxValue() {
    // Given: WHERE age = NULL (expression evaluates to null)
    var bc = new SQLBinaryCondition(-1);
    bc.setLeft(fieldExpr("age"));
    bc.setOperator(new SQLEqualsOperator(-1));
    var nullExpr = mock(SQLExpression.class);
    when(nullExpr.isEarlyCalculated(any())).thenReturn(true);
    when(nullExpr.execute(
        nullable(Result.class), any(CommandContext.class))).thenReturn(null);
    bc.setRight(nullExpr);

    var desc = new IndexSearchDescriptor(index, bc, null, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  @Test
  public void fieldMismatch_returnsMaxValue() {
    // Given: leading field is "age" but condition is on "name"
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("name", new SQLEqualsOperator(-1), "Alice"),
        null, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  @Test
  public void unsupportedOperator_returnsMaxValue() {
    // Given: an operator not dispatched by estimateBlockSelectivity
    var bc = new SQLBinaryCondition(-1);
    bc.setLeft(fieldExpr("age"));
    var unknownOp = mock(SQLBinaryCompareOperator.class);
    when(unknownOp.isRangeOperator()).thenReturn(false);
    bc.setOperator(unknownOp);
    bc.setRight(valueExpr(42));

    var desc = new IndexSearchDescriptor(index, bc, null, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  // ── Minimum cost floor ──────────────────────────────────────

  @Test
  public void estimateNeverReturnsBelowOne() {
    // Given: a value far outside histogram range (selectivity near zero)
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 999),
        null, null);

    var cost = desc.cost(ctx);

    // Selectivity is tiny but cost must be at least 1
    assertTrue("Cost should be at least 1", cost >= 1);
  }

  // ── Combined range: non-binary first expression falls back ───

  @Test
  public void combinedRange_nonBinaryFirstExpr_fallsBackToSingleBound() {
    // Given: first expression is not SQLBinaryCondition but additional is
    // This shouldn't normally happen, but tests the defensive code path
    var nonBinary = mock(SQLBooleanExpression.class);

    // Since nonBinary is not SQLBinaryCondition, estimateBlockSelectivity
    // returns -1, so cost falls back to MAX_VALUE.
    var additional = binaryCondition("age", new SQLLtOperator(-1), 80);
    var desc = new IndexSearchDescriptor(index, nonBinary, additional, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  // ── Combined range: non-calculable additional bound ──────────

  @Test
  public void combinedRange_nonCalculableAdditional_usesFirstBoundOnly() {
    // Given: age >= 20 with non-calculable additional bound
    var lower = binaryCondition("age", new SQLGeOperator(-1), 20);
    var additional = new SQLBinaryCondition(-1);
    additional.setLeft(fieldExpr("age"));
    additional.setOperator(new SQLLtOperator(-1));
    var complexExpr = mock(SQLExpression.class);
    when(complexExpr.isEarlyCalculated(any())).thenReturn(false);
    additional.setRight(complexExpr);

    var desc = new IndexSearchDescriptor(index, lower, additional, null);

    var cost = desc.cost(ctx);

    // Combined range fails → falls back to the single lower-bound estimate.
    // additionalRange != null → isRangeEstimate returns true → range cost.
    var expectedSel =
        SelectivityEstimator.estimateGreaterOrEqual(stats, histogram, 20);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── Combined range: two lower bounds (no upper) ──────────────

  @Test
  public void combinedRange_twoLowerBounds_fallsBackToSingleBound() {
    // Given: age > 20 AND age > 30 (both lower bounds — invalid combo)
    var first = binaryCondition("age", new SQLGtOperator(-1), 20);
    var additional = binaryCondition("age", new SQLGtOperator(-1), 30);

    var desc = new IndexSearchDescriptor(index, first, additional, null);

    var cost = desc.cost(ctx);

    // Combined range fails → falls back to single bound (age > 20).
    // additionalRange != null → isRangeEstimate returns true → range cost.
    var expectedSel =
        SelectivityEstimator.estimateGreaterThan(stats, histogram, 20);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── Helpers ──────────────────────────────────────────────────

  /**
   * Creates a binary condition: {@code fieldName op value}.
   */
  private SQLBinaryCondition binaryCondition(
      String fieldName, SQLBinaryCompareOperator op, Object value) {
    var bc = new SQLBinaryCondition(-1);
    bc.setLeft(fieldExpr(fieldName));
    bc.setOperator(op);
    bc.setRight(valueExpr(value));
    return bc;
  }

  /**
   * Creates a mock SQLExpression that acts as a simple field reference.
   */
  private SQLExpression fieldExpr(String name) {
    var expr = mock(SQLExpression.class);
    when(expr.isBaseIdentifier()).thenReturn(true);
    when(expr.toString()).thenReturn(name);
    return expr;
  }

  /**
   * Creates a mock SQLExpression that returns a constant value.
   */
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
