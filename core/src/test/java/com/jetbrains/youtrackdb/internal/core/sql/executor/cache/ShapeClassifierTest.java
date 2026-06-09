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

  /** {@code COUNT(*)} is the count aggregate shape; the value is final though the delta lands later. */
  @Test
  public void countStarClassifiesAsAggregateCount() {
    Assert.assertEquals(
        CacheableShape.AGGREGATE_COUNT,
        ShapeClassifier.classify(parse("select count(*) from OUser")));
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
