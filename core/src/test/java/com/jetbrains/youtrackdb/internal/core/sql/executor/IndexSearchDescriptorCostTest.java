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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the histogram-based cost estimation in
 * {@link IndexSearchDescriptor#cost(CommandContext)}.
 *
 * <p>Verifies that cost() uses persistent histogram-based estimation via
 * {@link SelectivityEstimator}. Also tests combined range conditions,
 * composite index handling, and various fallback paths.
 */
public class IndexSearchDescriptorCostTest {

  private DatabaseSessionEmbedded session;
  private CommandContext ctx;
  private Index index;

  // Uniform histogram: 4 buckets [0,25), [25,50), [50,75), [75,100]
  // 250 entries/bucket, 50 distinct/bucket, 1000 total non-null entries
  private IndexStatistics stats;
  private EquiDepthHistogram histogram;

  @Before
  public void setUp() {
    session = mock(DatabaseSessionEmbedded.class);
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
        new Comparable<?>[] {0, 25, 50, 75, 100},
        new long[] {250, 250, 250, 250},
        new long[] {50, 50, 50, 50},
        1000,
        null, 0);

    when(index.getStatistics(session)).thenReturn(stats);
    when(index.getHistogram(session)).thenReturn(histogram);
  }

  // ── Unique index shortcut ──────────────────────────────────

  @Test
  public void uniqueIndex_exactEqualityOnAllFields_returnsCostOne() {
    // Unique single-field index with exact equality on all fields
    // should return the single-row lookup cost.
    when(index.isUnique()).thenReturn(true);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);

    assertEquals((int) CostModel.indexEqualityCost(1), desc.cost(ctx));
  }

  @Test
  public void uniqueIndex_rangeOnLastField_fallsBackToHistogram() {
    // Unique index with a range operator should NOT use the shortcut;
    // it falls through to histogram estimation.
    when(index.isUnique()).thenReturn(true);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLGtOperator(-1), 30),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateGreaterThan(stats, histogram, 30);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  @Test
  public void uniqueIndex_partialKeyMatch_fallsBackToHistogram() {
    // Unique composite index on [age, name] with only 1 of 2 fields
    // matched should NOT use the shortcut.
    when(index.isUnique()).thenReturn(true);
    var compositeDef = mock(IndexDefinition.class);
    when(compositeDef.getProperties()).thenReturn(List.of("age", "name"));
    when(index.getDefinition()).thenReturn(compositeDef);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 30);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  @Test
  public void uniqueIndex_withAdditionalRange_skipsShortcut() {
    // Unique index with additionalRangeCondition != null should skip
    // the shortcut and fall through to histogram estimation.
    when(index.isUnique()).thenReturn(true);

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
  public void uniqueCompositeIndex_exactMatchAllFields_returnsCostOne() {
    // Unique composite index on [age, name] with equality on ALL fields
    // should return the single-row lookup cost.
    when(index.isUnique()).thenReturn(true);
    var compositeDef = mock(IndexDefinition.class);
    when(compositeDef.getProperties()).thenReturn(List.of("age", "name"));
    when(index.getDefinition()).thenReturn(compositeDef);

    var andBlock = new SQLAndBlock(-1);
    andBlock.addSubBlock(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));
    andBlock.addSubBlock(
        binaryCondition("name", new SQLEqualsOperator(-1), "Alice"));

    var desc = new IndexSearchDescriptor(index, andBlock, null, null);

    assertEquals((int) CostModel.indexEqualityCost(1), desc.cost(ctx));
  }

  @Test
  public void uniqueCompositeIndex_equalityPlusRangeOnLast_skipsShortcut() {
    // Unique composite index on [a, b] with equality on the leading field
    // and a range on the last field should NOT use the shortcut — the
    // allEquality check rejects it because the last sub-block uses GT.
    when(index.isUnique()).thenReturn(true);
    var compositeDef = mock(IndexDefinition.class);
    when(compositeDef.getProperties()).thenReturn(List.of("a", "b"));
    when(index.getDefinition()).thenReturn(compositeDef);

    var andBlock = new SQLAndBlock(-1);
    andBlock.addSubBlock(
        binaryCondition("a", new SQLEqualsOperator(-1), 1));
    andBlock.addSubBlock(
        binaryCondition("b", new SQLGtOperator(-1), 5));

    var desc = new IndexSearchDescriptor(index, andBlock, null, null);

    var cost = desc.cost(ctx);

    // Shortcut skipped → histogram estimation. Leading field (a=1) uses
    // histogram equality, second field uses defaultSelectivity. isRangeEstimate
    // checks the FIRST sub-block (equality) so the cost model uses
    // indexEqualityCost.
    var leadingSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 1);
    var combinedSel = leadingSel * SelectivityEstimator.defaultSelectivity();
    long estimatedRows =
        Math.max(1, (long) (stats.totalCount() * combinedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  @Test
  public void uniqueIndex_inCondition_doesNotTriggerShortcut() {
    // An IN condition on a unique index can match multiple rows (one per
    // IN value). The shortcut must NOT fire because SQLInOperator is not
    // SQLEqualsOperator.
    when(index.isUnique()).thenReturn(true);

    var bc = new SQLBinaryCondition(-1);
    bc.setLeft(fieldExpr("age"));
    bc.setOperator(new SQLInOperator(-1));
    bc.setRight(valueExpr(List.of(10, 30, 70)));

    var desc = new IndexSearchDescriptor(index, bc, null, null);

    var cost = desc.cost(ctx);

    // Should fall through to histogram IN estimation, not the unique shortcut.
    var expectedSel = SelectivityEstimator.estimateIn(
        stats, histogram, List.of(10, 30, 70));
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  @Test
  public void nonUniqueIndex_equalityOnAllFields_doesNotReturnCostOne() {
    // Non-unique index with exact equality should NOT use the unique
    // shortcut — guards against accidental removal of the isUnique() check.
    when(index.isUnique()).thenReturn(false);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);

    var cost = desc.cost(ctx);

    // Should fall through to histogram estimation, not the unique shortcut.
    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 30);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  // ── Equality: f = X ──────────────────────────────────────────

  @Test
  public void equalityCondition_usesHistogramEstimation() {
    // Given: WHERE age = 30
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
  public void estimateNeverReturnsBelowOne_nearBoundary() {
    // Value just above the histogram upper bound (100). The near-boundary
    // out-of-range path uses 1/NDV selectivity, producing a small but
    // positive cost that must be at least 1.
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 101),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 101);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
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

  // ── Null key condition ─────────────────────────────────────

  @Test
  public void cost_nullKeyCondition_returnsMaxValue() {
    // When keyCondition is null, cost() should short-circuit to MAX_VALUE
    // without consulting histogram.
    var desc = new IndexSearchDescriptor(index);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  // ── isRangeEstimate: equality vs range cost model selection ──
  // Note: GT, GE, LT, LE → indexRangeCost is already verified by the
  // operator-specific tests above. These tests verify the two distinct
  // outcomes: equality uses indexEqualityCost, and additionalRange
  // forces indexRangeCost.

  @Test
  public void isRangeEstimate_withEquality_returnsFalse() {
    // An equality condition is not a range estimate → uses indexEqualityCost.
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 50),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 50);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    // Equality → indexEqualityCost, NOT indexRangeCost
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  // ── isRangeEstimate with additionalRangeCondition ─────────

  @Test
  public void isRangeEstimate_withAdditionalRange_returnsTrue() {
    // When additionalRangeCondition is non-null, isRangeEstimate returns true
    // regardless of the leading operator. Here leading is equality but
    // additionalRange makes it a range estimate → indexRangeCost.
    var leading = binaryCondition("age", new SQLGeOperator(-1), 20);
    var additional = binaryCondition("age", new SQLLtOperator(-1), 80);

    var desc = new IndexSearchDescriptor(index, leading, additional, null);

    var cost = desc.cost(ctx);

    // Combined range → indexRangeCost
    var expectedSel = SelectivityEstimator.estimateRange(
        stats, histogram, 20, 80, true, false);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── estimateFromHistogram: no stats / zero totalCount ─────

  @Test
  public void estimateFromHistogram_noStats_returnsMaxValue() {
    // When index.getStatistics() returns null, estimateFromHistogram
    // should return MAX_VALUE.
    when(index.getStatistics(session)).thenReturn(null);

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLGtOperator(-1), 10),
        null, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  @Test
  public void estimateFromHistogram_zeroTotalCount_returnsMaxValue() {
    // When totalCount is 0, estimateFromHistogram should return MAX_VALUE.
    when(index.getStatistics(session))
        .thenReturn(new IndexStatistics(0, 0, 0));

    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLGtOperator(-1), 10),
        null, null);

    assertEquals(Integer.MAX_VALUE, desc.cost(ctx));
  }

  // ── estimateFromHistogram: minimum cost of 1 ─────────────

  @Test
  public void estimateFromHistogram_ensuresMinimumCost1() {
    // When selectivity is very small (value far outside histogram range),
    // the cost must still be at least 1 — never 0.
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 999_999),
        null, null);

    var cost = desc.cost(ctx);

    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 999_999);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexEqualityCost(estimatedRows), cost);
  }

  // ── estimateCombinedRange: f >= 20 AND f < 30 ─────────────

  @Test
  public void estimateCombinedRange_geAndLt() {
    // Verify combined range estimation for f >= 20 AND f < 30.
    var lower = binaryCondition("age", new SQLGeOperator(-1), 20);
    var upper = binaryCondition("age", new SQLLtOperator(-1), 30);

    var desc = new IndexSearchDescriptor(index, lower, upper, null);

    var cost = desc.cost(ctx);

    var expectedSel = SelectivityEstimator.estimateRange(
        stats, histogram, 20, 30, true, false);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  @Test
  public void estimateCombinedRange_gtAndLe() {
    // Verify combined range estimation for f > 20 AND f <= 30.
    var lower = binaryCondition("age", new SQLGtOperator(-1), 20);
    var upper = binaryCondition("age", new SQLLeOperator(-1), 30);

    var desc = new IndexSearchDescriptor(index, lower, upper, null);

    var cost = desc.cost(ctx);

    var expectedSel = SelectivityEstimator.estimateRange(
        stats, histogram, 20, 30, false, true);
    long estimatedRows = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals((int) CostModel.indexRangeCost(estimatedRows), cost);
  }

  // ── estimateHits() ─────────────────────────────────────────

  // estimateHits with null key condition returns -1.
  @Test
  public void estimateHits_nullKeyCondition_returnsNegative() {
    var desc = new IndexSearchDescriptor(index);
    assertEquals(-1, desc.estimateHits(ctx));
  }

  // estimateHits with null statistics returns -1.
  @Test
  public void estimateHits_nullStats_returnsNegative() {
    when(index.getStatistics(session)).thenReturn(null);
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);
    assertEquals(-1, desc.estimateHits(ctx));
  }

  // estimateHits with zero totalCount returns -1.
  @Test
  public void estimateHits_zeroTotalCount_returnsNegative() {
    when(index.getStatistics(session))
        .thenReturn(new IndexStatistics(0, 0, 0));
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);
    assertEquals(-1, desc.estimateHits(ctx));
  }

  // estimateHits with unsupported operator returns -1.
  @Test
  public void estimateHits_unsupportedOperator_returnsNegative() {
    var bc = new SQLBinaryCondition(-1);
    bc.setLeft(fieldExpr("age"));
    var unknownOp = mock(SQLBinaryCompareOperator.class);
    when(unknownOp.isRangeOperator()).thenReturn(false);
    bc.setOperator(unknownOp);
    bc.setRight(valueExpr(42));

    var desc = new IndexSearchDescriptor(index, bc, null, null);
    assertEquals(-1, desc.estimateHits(ctx));
  }

  // estimateHits with equality condition returns positive estimate.
  @Test
  public void estimateHits_equality_returnsPositive() {
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);
    var hits = desc.estimateHits(ctx);

    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 30);
    assertEquals(
        Math.max(1, (long) (stats.totalCount() * expectedSel)), hits);
  }

  // estimateHits with combined range uses tighter estimate.
  @Test
  public void estimateHits_combinedRange_usesRangeEstimate() {
    var lower = binaryCondition("age", new SQLGeOperator(-1), 20);
    var upper = binaryCondition("age", new SQLLtOperator(-1), 30);
    var desc = new IndexSearchDescriptor(index, lower, upper, null);

    var hits = desc.estimateHits(ctx);

    var expectedSel = SelectivityEstimator.estimateRange(
        stats, histogram, 20, 30, true, false);
    assertEquals(
        Math.max(1, (long) (stats.totalCount() * expectedSel)), hits);
  }

  // estimateHits with composite index multiplies default selectivity.
  @Test
  public void estimateHits_compositeIndex_multipliesDefaultSelectivity() {
    var compositeDef = mock(IndexDefinition.class);
    when(compositeDef.getProperties())
        .thenReturn(List.of("age", "name"));
    when(index.getDefinition()).thenReturn(compositeDef);

    var andBlock = new SQLAndBlock(-1);
    andBlock.addSubBlock(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));
    andBlock.addSubBlock(
        binaryCondition("name", new SQLGeOperator(-1), "A"));

    var desc = new IndexSearchDescriptor(index, andBlock, null, null);
    var hits = desc.estimateHits(ctx);

    var leadingSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 30);
    var combinedSel =
        leadingSel * SelectivityEstimator.defaultSelectivity();
    assertEquals(
        Math.max(1, (long) (stats.totalCount() * combinedSel)), hits);
  }

  // estimateHits always returns at least 1 even for tiny selectivity.
  @Test
  public void estimateHits_floorIsOne() {
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 999_999),
        null, null);
    var hits = desc.estimateHits(ctx);

    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 999_999);
    long expectedHits = Math.max(1, (long) (stats.totalCount() * expectedSel));
    assertEquals(expectedHits, hits);
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
    // Mock getDefaultAlias() for field matching via
    // getDefaultAlias().getStringValue() (unquoted identifier comparison).
    var alias = mock(SQLIdentifier.class);
    when(alias.getStringValue()).thenReturn(name);
    when(expr.getDefaultAlias()).thenReturn(alias);
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

  // ── estimateSelectivity ──────────────────────────────────

  /**
   * estimateSelectivity with null key condition returns -1.0 (unknown).
   */
  @Test
  public void estimateSelectivity_nullKeyCondition_returnsNegative() {
    var desc = new IndexSearchDescriptor(index);
    assertEquals(-1.0, desc.estimateSelectivity(ctx), 0.0);
  }

  /**
   * estimateSelectivity with null statistics returns -1.0 (unknown).
   */
  @Test
  public void estimateSelectivity_nullStats_returnsNegative() {
    when(index.getStatistics(session)).thenReturn(null);
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);
    assertEquals(-1.0, desc.estimateSelectivity(ctx), 0.0);
  }

  /**
   * estimateSelectivity with zero totalCount returns -1.0 (unknown).
   */
  @Test
  public void estimateSelectivity_zeroTotalCount_returnsNegative() {
    when(index.getStatistics(session)).thenReturn(
        new IndexStatistics(0, 0, 0));
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);
    assertEquals(-1.0, desc.estimateSelectivity(ctx), 0.0);
  }

  /**
   * estimateSelectivity with an equality condition returns a fraction
   * in [0.0, 1.0]. The exact value depends on the histogram, but it
   * must be clamped within bounds.
   */
  @Test
  public void estimateSelectivity_equality_returnsFractionInRange() {
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);
    double sel = desc.estimateSelectivity(ctx);
    assertThat(sel).isGreaterThanOrEqualTo(0.0);
    assertThat(sel).isLessThanOrEqualTo(1.0);
  }

  /**
   * estimateSelectivity and estimateHits must be consistent: the hit count
   * should equal totalCount * selectivity (both rounded to at least 1).
   */
  @Test
  public void estimateSelectivity_consistentWithEstimateHits() {
    var desc = new IndexSearchDescriptor(
        index,
        binaryCondition("age", new SQLEqualsOperator(-1), 30),
        null, null);
    double sel = desc.estimateSelectivity(ctx);
    long hits = desc.estimateHits(ctx);
    long expectedHits = Math.max(1, (long) (stats.totalCount() * sel));
    assertEquals(expectedHits, hits);
  }
}
