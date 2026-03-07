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

package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.index.engine.SelectivityEstimator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the histogram-based selectivity estimation wiring in
 * {@link SQLWhereClause#estimate(SchemaClassInternal, long, CommandContext)}.
 *
 * <p>Verifies that the estimate() method correctly dispatches to
 * {@link SelectivityEstimator} methods for each predicate type,
 * combines selectivities for AND blocks via multiplication, and
 * falls back to the legacy index-probe path when no histogram is available.
 */
public class SQLWhereClauseHistogramEstimateTest {

  private static final long CLASS_COUNT = 10_000;
  private static final long THRESHOLD = 100;

  private DatabaseSessionEmbedded session;
  private CommandContext ctx;
  private SchemaClassInternal schemaClass;
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

    schemaClass = mock(SchemaClassInternal.class);
    when(schemaClass.approximateCount(session)).thenReturn(CLASS_COUNT);

    var indexDef = mock(IndexDefinition.class);
    when(indexDef.getProperties()).thenReturn(List.of("age"));

    index = mock(Index.class);
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
    when(schemaClass.getIndexesInternal()).thenReturn(Set.of(index));
  }

  // ── Equality: f = X ──────────────────────────────────────────

  @Test
  public void equalityCondition_usesHistogramEstimation() {
    // Given: WHERE age = 30
    var where = buildWhereClause(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));

    // When
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // Then: estimate should match histogram equality selectivity × classCount
    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, histogram, 30);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── Greater-than: f > X ──────────────────────────────────────

  @Test
  public void greaterThanCondition_usesHistogramEstimation() {
    // Given: WHERE age > 60
    var where = buildWhereClause(
        binaryCondition("age", new SQLGtOperator(-1), 60));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var expectedSel =
        SelectivityEstimator.estimateGreaterThan(stats, histogram, 60);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── Less-than: f < X ────────────────────────────────────────

  @Test
  public void lessThanCondition_usesHistogramEstimation() {
    // Given: WHERE age < 30
    var where = buildWhereClause(
        binaryCondition("age", new SQLLtOperator(-1), 30));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var expectedSel =
        SelectivityEstimator.estimateLessThan(stats, histogram, 30);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── Greater-or-equal: f >= X ─────────────────────────────────

  @Test
  public void greaterOrEqualCondition_usesHistogramEstimation() {
    // Use a high threshold so selectivity < 0.5 (stays below count/2 cap)
    var where = buildWhereClause(
        binaryCondition("age", new SQLGeOperator(-1), 80));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var expectedSel =
        SelectivityEstimator.estimateGreaterOrEqual(stats, histogram, 80);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── Less-or-equal: f <= X ───────────────────────────────────

  @Test
  public void lessOrEqualCondition_usesHistogramEstimation() {
    // Use a low threshold so selectivity < 0.5 (stays below count/2 cap)
    var where = buildWhereClause(
        binaryCondition("age", new SQLLeOperator(-1), 20));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var expectedSel =
        SelectivityEstimator.estimateLessOrEqual(stats, histogram, 20);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── GE/LE on exact bucket boundaries ────────────────────────

  @Test
  public void greaterOrEqualOnBoundary_includesEntireBucket() {
    // WHERE age >= 50 — boundary between bucket 2 and 3.
    // Selectivity ≈ 0.505 → exceeds count/2 cap → capped at 5000.
    var where = buildWhereClause(
        binaryCondition("age", new SQLGeOperator(-1), 50));
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);
    var expectedSel =
        SelectivityEstimator.estimateGreaterOrEqual(stats, histogram, 50);
    // High selectivity (>= 0.5) is capped at count/2
    var raw = Math.max(1, (long) (CLASS_COUNT * expectedSel));
    var expected = Math.min(raw, CLASS_COUNT / 2);
    assertEquals(expected, estimate);
  }

  @Test
  public void lessOrEqualOnBoundary_includesEntireBucket() {
    // WHERE age <= 25 — boundary between bucket 0 and 1
    var where = buildWhereClause(
        binaryCondition("age", new SQLLeOperator(-1), 25));
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);
    var expectedSel =
        SelectivityEstimator.estimateLessOrEqual(stats, histogram, 25);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  @Test
  public void greaterOrEqualOnMinBoundary_selectsAll() {
    // WHERE age >= 0 — at minimum boundary, selectivity ≈ 1.0.
    // Estimate is capped at count/2.
    var where = buildWhereClause(
        binaryCondition("age", new SQLGeOperator(-1), 0));
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  @Test
  public void lessOrEqualOnMaxBoundary_selectsAll() {
    // WHERE age <= 100 — at maximum boundary, selectivity ≈ 1.0.
    // Estimate is capped at count/2.
    var where = buildWhereClause(
        binaryCondition("age", new SQLLeOperator(-1), 100));
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── Not-equal: f != X ───────────────────────────────────────

  @Test
  public void notEqualCondition_usesOneMinusEqualitySelectivity() {
    // != selectivity is ~0.995 (very high), so it exceeds count/2 cap.
    // The estimate is capped at classCount/2.
    var where = buildWhereClause(
        binaryCondition("age", new SQLNeOperator(-1), 50));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // Histogram estimate ≈ 9950, but capped at count/2 = 5000
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  @Test
  public void neqOperatorCondition_usesOneMinusEqualitySelectivity() {
    // <> operator (SQL standard syntax) — same cap behavior as !=
    var where = buildWhereClause(
        binaryCondition("age", new SQLNeqOperator(-1), 50));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── BETWEEN: f BETWEEN X AND Y ──────────────────────────────

  @Test
  public void betweenCondition_usesHistogramRangeEstimation() {
    // Given: WHERE age BETWEEN 20 AND 60
    var where = buildWhereClause(
        betweenCondition("age", 20, 60));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var expectedSel = SelectivityEstimator.estimateRange(
        stats, histogram, 20, 60, true, true);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── IS NULL ──────────────────────────────────────────────────

  @Test
  public void isNullCondition_usesHistogramEstimation() {
    // Given: stats with some nulls
    var statsWithNulls = new IndexStatistics(1000, 200, 100);
    when(index.getStatistics(session)).thenReturn(statsWithNulls);

    var where = buildWhereClause(isNullCondition("age"));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var expectedSel =
        SelectivityEstimator.estimateIsNull(statsWithNulls, histogram);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── IS NOT NULL ──────────────────────────────────────────────

  @Test
  public void isNotNullCondition_usesHistogramEstimation() {
    // Given: stats with 50% nulls so IS NOT NULL selectivity is ~0.5
    var statsHalfNull = new IndexStatistics(1000, 200, 500);
    var histHalfNull = new EquiDepthHistogram(
        4,
        new Comparable<?>[]{0, 25, 50, 75, 100},
        new long[]{125, 125, 125, 125},
        new long[]{50, 50, 50, 50},
        500,
        null, 0);
    when(index.getStatistics(session)).thenReturn(statsHalfNull);
    when(index.getHistogram(session)).thenReturn(histHalfNull);

    var where = buildWhereClause(isNotNullCondition("age"));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var expectedSel =
        SelectivityEstimator.estimateIsNotNull(statsHalfNull, histHalfNull);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── NOT: NOT (f = X) ────────────────────────────────────────

  @Test
  public void notCondition_invertsInnerSelectivity() {
    // NOT (f = X) has selectivity ~0.995 (very high), capped at count/2
    var inner = binaryCondition("age", new SQLEqualsOperator(-1), 50);
    var notBlock = new SQLNotBlock(-1);
    notBlock.setSub(inner);
    notBlock.setNegate(true);

    var where = buildWhereClause(notBlock);

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // Capped at classCount/2 since NOT(eq) selectivity ≈ 1.0
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  @Test
  public void notCondition_withLowSelectivityInner_producesHighEstimate() {
    // NOT (age > 80) → selectivity ≈ 1.0 - sel(age > 80) < 0.5
    // Use GT with high value so inner selectivity is low
    var inner = binaryCondition("age", new SQLGtOperator(-1), 80);
    var notBlock = new SQLNotBlock(-1);
    notBlock.setSub(inner);
    notBlock.setNegate(true);

    var where = buildWhereClause(notBlock);

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var innerSel =
        SelectivityEstimator.estimateGreaterThan(stats, histogram, 80);
    var expectedSel = 1.0 - innerSel;
    // NOT (age > 80) should have high selectivity, still capped
    assertTrue("NOT selectivity should be > 0.5", expectedSel > 0.5);
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── AND combination: f > X AND f < Y ─────────────────────────

  @Test
  public void andCombination_multipliesSelectivities() {
    // Given: WHERE age > 60 AND age < 90
    // sel(age > 60) ≈ 0.4, sel(age < 90) ≈ 0.9 → combined ≈ 0.36
    var where = buildWhereClause(
        binaryCondition("age", new SQLGtOperator(-1), 60),
        binaryCondition("age", new SQLLtOperator(-1), 90));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var selGt =
        SelectivityEstimator.estimateGreaterThan(stats, histogram, 60);
    var selLt =
        SelectivityEstimator.estimateLessThan(stats, histogram, 90);
    var combinedSel = selGt * selLt;
    assertEquals(Math.max(1, (long) (CLASS_COUNT * combinedSel)), estimate);
  }

  // ── IN: f IN (v1, v2, ...) ─────────────────────────────────

  @Test
  public void inOperator_usesHistogramEstimation() {
    // Given: WHERE age IN [10, 30, 70]
    var bc = new SQLBinaryCondition(-1);
    bc.left = fieldExpr("age");
    bc.operator = new SQLInOperator(-1);
    bc.right = valueExpr(List.of(10, 30, 70));

    var where = buildWhereClause(bc);

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    var expectedSel = SelectivityEstimator.estimateIn(
        stats, histogram, List.of(10, 30, 70));
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── OR conditions (multiple flattened AND blocks) ────────────

  @Test
  public void orConditions_sumsEstimatesAcrossBranches() {
    // Given: WHERE age = 10 OR age = 80
    // Flattened into two AND blocks, one per OR branch
    var branch1 = new SQLAndBlock(-1);
    branch1.addSubBlock(
        binaryCondition("age", new SQLEqualsOperator(-1), 10));

    var branch2 = new SQLAndBlock(-1);
    branch2.addSubBlock(
        binaryCondition("age", new SQLEqualsOperator(-1), 80));

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(mock(SQLBooleanExpression.class));
    where.setFlattened(List.of(branch1, branch2));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // Each branch independently estimates, then sums
    var sel1 =
        SelectivityEstimator.estimateEquality(stats, histogram, 10);
    var sel2 =
        SelectivityEstimator.estimateEquality(stats, histogram, 80);
    var expected1 = Math.max(1, (long) (CLASS_COUNT * sel1));
    var expected2 = Math.max(1, (long) (CLASS_COUNT * sel2));
    assertEquals(expected1 + expected2, estimate);
  }

  // ── No-stats fallback ────────────────────────────────────────

  @Test
  public void noStatistics_fallsBackToCountHalfHeuristic() {
    // Given: index returns null statistics (no histogram manager)
    when(index.getStatistics(session)).thenReturn(null);
    when(index.getHistogram(session)).thenReturn(null);

    // AND: an equality condition that can't use histograms
    var where = buildWhereClause(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // Then: falls back to count/2 since no index probes match either
    // (the mock index has no real data for estimateFromIndex)
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── No-histogram (stats only) uses uniform estimation ────────

  @Test
  public void noHistogram_usesUniformEstimation() {
    // Given: stats available but no histogram
    when(index.getHistogram(session)).thenReturn(null);

    var where = buildWhereClause(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // Uniform equality: 1/NDV = 1/200 = 0.005
    var expectedSel =
        SelectivityEstimator.estimateEquality(stats, null, 30);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── Zero class count returns 0 ──────────────────────────────

  @Test
  public void zeroClassCount_returnsZero() {
    when(schemaClass.approximateCount(session)).thenReturn(0L);

    var where = buildWhereClause(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));

    assertEquals(0, where.estimate(schemaClass, THRESHOLD, ctx));
  }

  // ── Predicate on non-indexed field is not estimated ──────────

  @Test
  public void predicateOnNonIndexedField_notEstimated() {
    // Given: WHERE name = 'Alice' but index is on "age"
    var where = buildWhereClause(
        binaryCondition("name", new SQLEqualsOperator(-1), "Alice"));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // No predicate matches the index field, so histogram can't help.
    // Falls back to count/2 heuristic.
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── Non-early-calculable value is not estimated ──────────────

  @Test
  public void nonEarlyCalculableValue_notEstimated() {
    // Given: WHERE age = (complex expression that can't be pre-computed)
    var bc = new SQLBinaryCondition(-1);
    bc.left = fieldExpr("age");
    bc.operator = new SQLEqualsOperator(-1);
    var complexExpr = mock(SQLExpression.class);
    when(complexExpr.isEarlyCalculated(any())).thenReturn(false);
    when(complexExpr.isIndexedFunctionCal(any())).thenReturn(false);
    bc.right = complexExpr;

    var where = buildWhereClause(bc);
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // Falls back to count/2 since the value can't be evaluated at
    // plan time
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── Null value from expression is not estimated ──────────────

  @Test
  public void nullValueFromExpression_notEstimated() {
    // Given: WHERE age = NULL (the value expression evaluates to null)
    var bc = new SQLBinaryCondition(-1);
    bc.left = fieldExpr("age");
    bc.operator = new SQLEqualsOperator(-1);
    var nullExpr = mock(SQLExpression.class);
    when(nullExpr.isEarlyCalculated(any())).thenReturn(true);
    when(nullExpr.execute(
        nullable(Result.class), any(CommandContext.class))).thenReturn(null);
    when(nullExpr.execute(
        nullable(Identifiable.class),
        any(CommandContext.class))).thenReturn(null);
    when(nullExpr.isIndexedFunctionCal(any())).thenReturn(false);
    bc.right = nullExpr;

    var where = buildWhereClause(bc);
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── Small class count triggers early return ──────────────────

  @Test
  public void smallClassCount_returnsEarlyWithHalfCount() {
    // Given: class with fewer than 2 * threshold records
    when(schemaClass.approximateCount(session)).thenReturn(150L);

    var where = buildWhereClause(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // 150/2 = 75 < threshold (100) → returns 75 immediately
    assertEquals(75, estimate);

    // Verify no histogram call was made (early return before loop)
    verify(index, never()).getStatistics(any());
  }

  // ── Histogram estimate is bounded by count/2 ceiling ─────────

  @Test
  public void histogramEstimate_boundedByCountHalfCeiling() {
    // Given: a predicate with very high selectivity (nearly 1.0)
    // WHERE age IS NOT NULL on an index with zero nulls → sel ≈ 1.0
    var where = buildWhereClause(isNotNullCondition("age"));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // The histogram estimate (≈ 10000) exceeds count/2 (5000),
    // so the method returns count/2 as the upper bound.
    assertTrue("Estimate should not exceed count/2",
        estimate <= CLASS_COUNT / 2);
  }

  // ── NOT without negate acts as identity ──────────────────────

  @Test
  public void notBlockWithoutNegate_delegatesToInner() {
    // SQLNotBlock with negate=false is a transparent wrapper
    var inner = binaryCondition("age", new SQLEqualsOperator(-1), 30);
    var notBlock = new SQLNotBlock(-1);
    notBlock.setSub(inner);
    notBlock.setNegate(false);

    var where = buildWhereClause(notBlock);
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // With negate=false, the not block is not recognized as a
    // negation by the histogram estimator → returns -1 (unestimated).
    // Falls back to count/2.
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── Mixed indexed and non-indexed predicates ─────────────────

  @Test
  public void mixedPredicates_onlyIndexedFieldEstimated() {
    // Given: WHERE age > 50 AND name = 'Alice'
    // "age" has a histogram, "name" does not match any index
    var where = buildWhereClause(
        binaryCondition("age", new SQLGtOperator(-1), 50),
        binaryCondition("name", new SQLEqualsOperator(-1), "Alice"));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // Only the "age > 50" predicate is estimated; "name = 'Alice'" is
    // not matched (returns -1). The combined selectivity uses only the
    // age predicate.
    var expectedSel =
        SelectivityEstimator.estimateGreaterThan(stats, histogram, 50);
    assertEquals(Math.max(1, (long) (CLASS_COUNT * expectedSel)), estimate);
  }

  // ── BETWEEN with non-calculable bounds ───────────────────────

  @Test
  public void betweenWithNonCalculableBounds_notEstimated() {
    // Given: WHERE age BETWEEN (complex) AND 60
    var bt = new SQLBetweenCondition(-1);
    bt.setFirst(fieldExpr("age"));
    var complexExpr = mock(SQLExpression.class);
    when(complexExpr.isEarlyCalculated(any())).thenReturn(false);
    bt.setSecond(complexExpr);
    bt.setThird(valueExpr(60));

    var where = buildWhereClause(bt);
    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── Empty stats (totalCount == 0) skips histogram ────────────

  @Test
  public void emptyStats_returnsCountHalf() {
    // Given: index has stats with totalCount=0 (empty index)
    var emptyStats = new IndexStatistics(0, 0, 0);
    when(index.getStatistics(session)).thenReturn(emptyStats);

    var where = buildWhereClause(
        binaryCondition("age", new SQLEqualsOperator(-1), 30));

    var estimate = where.estimate(schemaClass, THRESHOLD, ctx);

    // totalCount=0 causes histogram path to return -1, falling through
    // to count/2 heuristic
    assertEquals(CLASS_COUNT / 2, estimate);
  }

  // ── Helpers ──────────────────────────────────────────────────

  /**
   * Builds a WHERE clause with a single AND block containing the given
   * conditions. Pre-sets the flattened list to bypass flatten() logic.
   */
  private SQLWhereClause buildWhereClause(
      SQLBooleanExpression... conditions) {
    var andBlock = new SQLAndBlock(-1);
    for (var cond : conditions) {
      andBlock.addSubBlock(cond);
    }
    var where = new SQLWhereClause(-1);
    // Set non-null base expression so flatten() doesn't short-circuit
    where.setBaseExpression(mock(SQLBooleanExpression.class));
    where.setFlattened(List.of(andBlock));
    return where;
  }

  /**
   * Creates a binary condition: {@code fieldName op value}.
   */
  private SQLBinaryCondition binaryCondition(
      String fieldName, SQLBinaryCompareOperator op, Object value) {
    var bc = new SQLBinaryCondition(-1);
    bc.left = fieldExpr(fieldName);
    bc.operator = op;
    bc.right = valueExpr(value);
    return bc;
  }

  /**
   * Creates a BETWEEN condition: {@code fieldName BETWEEN lo AND hi}.
   */
  private SQLBetweenCondition betweenCondition(
      String fieldName, Object lo, Object hi) {
    var bt = new SQLBetweenCondition(-1);
    bt.setFirst(fieldExpr(fieldName));
    bt.setSecond(valueExpr(lo));
    bt.setThird(valueExpr(hi));
    return bt;
  }

  /**
   * Creates an IS NULL condition: {@code fieldName IS NULL}.
   */
  private SQLIsNullCondition isNullCondition(String fieldName) {
    var nc = new SQLIsNullCondition(-1);
    nc.setExpression(fieldExpr(fieldName));
    return nc;
  }

  /**
   * Creates an IS NOT NULL condition: {@code fieldName IS NOT NULL}.
   */
  private SQLIsNotNullCondition isNotNullCondition(String fieldName) {
    var nnc = new SQLIsNotNullCondition(-1);
    // protected field; accessible from same package
    nnc.expression = fieldExpr(fieldName);
    return nnc;
  }

  /**
   * Creates a mock SQLExpression that acts as a simple field reference.
   */
  private SQLExpression fieldExpr(String name) {
    var expr = mock(SQLExpression.class);
    when(expr.isBaseIdentifier()).thenReturn(true);
    when(expr.toString()).thenReturn(name);
    // Not an indexed function
    when(expr.isIndexedFunctionCal(any())).thenReturn(false);
    return expr;
  }

  /**
   * Creates a mock SQLExpression that returns a constant value.
   */
  private SQLExpression valueExpr(Object value) {
    var expr = mock(SQLExpression.class);
    when(expr.isEarlyCalculated(any())).thenReturn(true);
    // Disambiguate overloaded execute() methods
    when(expr.execute(
        nullable(Result.class), any(CommandContext.class))).thenReturn(value);
    when(expr.execute(
        nullable(Identifiable.class),
        any(CommandContext.class))).thenReturn(value);
    // Not a base identifier or indexed function
    when(expr.isBaseIdentifier()).thenReturn(false);
    when(expr.isIndexedFunctionCal(any())).thenReturn(false);
    return expr;
  }
}
