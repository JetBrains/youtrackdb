package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that {@link ShapeClassifier#classify} maps each query AST to the correct {@link
 * CacheableShape}. The two shapes this foundation reconciles (RECORD and K0_NONE) are asserted
 * precisely; the AGGREGATE_* and MATCH branches are asserted to return their final enum values (their
 * delta paths land in later tracks). The most safety-critical case — an {@code ORDER BY} + {@code
 * LIMIT} query must classify as K0_NONE and never as RECORD — has a dedicated test that also confirms
 * the SKIP/LIMIT gate runs before the RECORD branch.
 */
public class ShapeClassifierTest extends DbTestBase {

  private SQLStatement parse(String sql) {
    return YqlStatementCache.get(sql, session);
  }

  /**
   * A plain {@code SELECT FROM Class WHERE simple-predicate} with no pagination, grouping, LET, or
   * aggregate is the canonical RECORD shape the per-record delta builder reconciles.
   */
  @Test
  public void plainSelectClassifiesAsRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD, ShapeClassifier.classify(parse("select from OUser where name = ?")));
  }

  /**
   * A RECORD-shaped query with a plain {@code ORDER BY} (no SKIP/LIMIT) stays RECORD: ORDER BY alone
   * does not make the result delta-irreconcilable, because the full result is still materialised.
   */
  @Test
  public void selectWithOrderByButNoLimitStaysRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD, ShapeClassifier.classify(parse("select from OUser order by name")));
  }

  /**
   * The load-bearing classify-ordering guard (I10 depends on it): an {@code ORDER BY} + {@code LIMIT}
   * query must classify as K0_NONE, never RECORD. {@code OrderByStep} + LIMIT is a bounded-heap
   * materialiser that discards rows past the top-N, so a cached top-N prefix could not promote row
   * N+1 after an in-tx delete. A future reorder that ran the RECORD branch before the SKIP/LIMIT gate
   * would silently break this; the assertion pins the ordering.
   */
  @Test
  public void orderByPlusLimitClassifiesAsK0NoneNotRecord() {
    var shape = ShapeClassifier.classify(parse("select from OUser order by name limit 10"));
    Assert.assertEquals(
        "ORDER BY + LIMIT must classify as K0_NONE so a bounded top-N prefix is never treated as a"
            + " complete delta-reconcilable RECORD result",
        CacheableShape.K0_NONE,
        shape);
    Assert.assertNotEquals(CacheableShape.RECORD, shape);
  }

  /** A bare SKIP routes to K0_NONE for the same paginated-prefix reason as LIMIT. */
  @Test
  public void skipClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE, ShapeClassifier.classify(parse("select from OUser skip 5")));
  }

  /** GROUP BY is not record-by-record reconcilable; routes to K0_NONE. */
  @Test
  public void groupByClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select name, count(*) from OUser group by name")));
  }

  /** A LET binding routes to K0_NONE; its computed aliases are not delta-reconcilable. */
  @Test
  public void letClauseClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select $a from OUser let $a = name")));
  }

  /** A subquery target routes to K0_NONE; the inner result is opaque to the per-record delta. */
  @Test
  public void subqueryTargetClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select from (select from OUser)")));
  }

  /**
   * Bare {@code COUNT(*) FROM C} (no WHERE) classifies K0_NONE, not AGGREGATE_COUNT. The planner
   * hardwires this shape to an O(1) {@code CountFromClassStep} built before any aggregation step
   * exists, so the aggregate side-tap can never reach it; routing it K0_NONE keeps the untappable shape
   * out of the aggregate replay path entirely. (It is already O(1) and tx-aware, so caching adds
   * nothing.)
   */
  @Test
  public void bareCountStarClassifiesAsK0None() {
    Assert.assertEquals(
        "bare COUNT(*) FROM C is hardwired and untappable; it must classify K0_NONE, not"
            + " AGGREGATE_COUNT",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select count(*) from OUser")));
  }

  /**
   * {@code COUNT(*)} with a (non-indexed) WHERE predicate stays AGGREGATE_COUNT: unlike the bare and
   * single-field-indexed forms, this shape builds a real {@code AggregateProjectionCalculationStep}, so
   * the side-tap can observe its contributing records and the aggregate cache path applies. The
   * classifier cannot see indexes (it runs on the AST alone), so it keeps every WHERE-carrying
   * {@code COUNT(*)} tappable; the indexed-WHERE residual is caught at the splice fallback instead.
   */
  @Test
  public void countStarWithWhereStaysAggregateCount() {
    Assert.assertEquals(
        CacheableShape.AGGREGATE_COUNT,
        ShapeClassifier.classify(parse("select count(*) from OUser where name = ?")));
  }

  /**
   * An aggregate buried under arithmetic ({@code count(*) + 1}) classifies K0_NONE, not
   * AGGREGATE_COUNT. The cached aggregate replay produces the bare scalar, so caching the arithmetic
   * result as an AGGREGATE_* shape would replay the wrong value; the K0 version gate serves it safely
   * instead. This is the tightening that matters now that aggregates actually cache — the earlier
   * looser match (return the inner call regardless of surrounding arithmetic) was harmless only while
   * aggregates were uncached.
   */
  @Test
  public void aggregateUnderArithmeticClassifiesAsK0None() {
    Assert.assertEquals(
        "count(*) + 1 is an expression over an aggregate, not a bare aggregate; it must classify"
            + " K0_NONE so the aggregate replay never returns the un-incremented scalar",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select count(*) + 1 from OUser")));
  }

  /**
   * SUM under arithmetic ({@code sum(age) * 2}) likewise classifies K0_NONE, confirming the
   * arithmetic-rejection rule is not specific to COUNT.
   */
  @Test
  public void sumUnderArithmeticClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select sum(age) * 2 from OUser")));
  }

  /** {@code SUM(prop)} maps to the sum aggregate shape. */
  @Test
  public void sumClassifiesAsAggregateSum() {
    Assert.assertEquals(
        CacheableShape.AGGREGATE_SUM,
        ShapeClassifier.classify(parse("select sum(age) from OUser")));
  }

  /**
   * {@code COUNT(DISTINCT(prop))} maps to the count-distinct shape, distinct from a plain count. In
   * this dialect the DISTINCT keyword inside a function call parses as a nested {@code distinct(...)}
   * function, which is what the classifier detects.
   */
  @Test
  public void countDistinctClassifiesAsAggregateCountDistinct() {
    Assert.assertEquals(
        CacheableShape.AGGREGATE_COUNT_DISTINCT,
        ShapeClassifier.classify(parse("select count(distinct(name)) from OUser")));
  }

  /**
   * A projection mixing an aggregate with another item is not a clean single-aggregate shape and is
   * not a plain RECORD either, so it falls to K0_NONE.
   */
  @Test
  public void mixedAggregateAndFieldProjectionClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select name, count(*) from OUser")));
  }

  /** A non-aggregate scalar function over a field keeps the query at RECORD shape. */
  @Test
  public void scalarFunctionProjectionStaysRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD,
        ShapeClassifier.classify(parse("select name.toLowerCase() from OUser")));
  }
}
