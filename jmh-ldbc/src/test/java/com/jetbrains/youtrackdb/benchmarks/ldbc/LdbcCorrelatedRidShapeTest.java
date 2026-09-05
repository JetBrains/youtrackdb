package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the correlated RID predicates that the LDBC benchmark queries depend on for their reported
 * throughput.
 *
 * <p>IC1 and IC10 each carry a LET subquery of the shape
 * {@code SELECT ... FROM Person WHERE @rid = $parent.$current.<vertexAlias>}. The planner compiles
 * that shape to a direct correlated RID fetch instead of a full class scan plus a RID filter. The
 * gate that makes the choice is a closed syntactic whitelist,
 * {@code com.jetbrains.youtrackdb.internal.core.sql.executor.ParentOnlyChain}, and it admits only
 * a chain rooted at {@code $parent}.
 *
 * <p>Three things can silently erase the gain, and each is pinned somewhere:
 *
 * <ul>
 *   <li>The gate stops admitting the shape. Pinned in core by
 *       {@code SelectExecutionPlannerRidEqualityTest} for the plan text, and by
 *       {@code ParentOnlyChainTest.ldbcBenchmarkCorrelatedExpressions_areAccepted} for these two
 *       exact expression strings.
 *   <li>The benchmark query loses the predicate outright. That is what this class pins, by reading
 *       the shipped SQL resources.
 *   <li>The benchmark query is rewritten so the predicate text survives but the planner stops
 *       choosing the fetch, for instance a top-level OR or a nested {@code FROM} target. A text
 *       check cannot see that, so {@link LdbcQueryExplainTest} asserts the real plan of both
 *       queries instead.
 * </ul>
 *
 * <p>The core half lives in a different module because the gate class is package private in the
 * core executor package, so this module cannot call it. Reading the resources through
 * {@link LdbcQuerySql}, the module's own loader, keeps the assertion on the same text the
 * benchmark executes and avoids a hard-coded file path. This class needs no database, no schema
 * and no benchmark run, which is why it stays alongside the heavier plan assertions rather than
 * being folded into them. Rewriting a benchmark query means updating every half together.
 */
public class LdbcCorrelatedRidShapeTest {

  /** The correlated predicate that drives IC1's {@code $universities} and {@code $companies}. */
  private static final String IC1_CORRELATED_RID = "@rid = $parent.$current.friendVertex";

  /** The correlated predicate that drives IC10's {@code $scores}. */
  private static final String IC10_CORRELATED_RID = "@rid = $parent.$current.fofVertex";

  /**
   * IC1 must still correlate its two LET subqueries on {@code $parent.$current.friendVertex}. Both
   * the {@code STUDY_AT} and the {@code WORK_AT} subquery use that predicate, so the count is
   * asserted as well: losing one of the two would halve the benefit without failing a containment
   * check.
   */
  @Test
  public void ic1StillUsesTheCorrelatedRidPredicate() {
    var sql = LdbcQuerySql.IC1;
    assertTrue(
        "IC1 must still correlate on '" + IC1_CORRELATED_RID
            + "', otherwise the correlated RID fetch no longer applies and the benchmark silently"
            + " falls back to a Person scan. Query was:\n" + sql,
        sql.contains(IC1_CORRELATED_RID));
    assertEqualsCount(
        "IC1 must keep both correlated LET subqueries (STUDY_AT and WORK_AT) on the predicate",
        2,
        sql,
        IC1_CORRELATED_RID);
  }

  /**
   * IC10 must still correlate its {@code $scores} LET subquery on
   * {@code $parent.$current.fofVertex}. One occurrence is expected, matching the single LET.
   */
  @Test
  public void ic10StillUsesTheCorrelatedRidPredicate() {
    var sql = LdbcQuerySql.IC10;
    assertTrue(
        "IC10 must still correlate on '" + IC10_CORRELATED_RID
            + "', otherwise the correlated RID fetch no longer applies and the benchmark silently"
            + " falls back to a Person scan. Query was:\n" + sql,
        sql.contains(IC10_CORRELATED_RID));
    assertEqualsCount(
        "IC10 must keep its single correlated LET subquery on the predicate",
        1,
        sql,
        IC10_CORRELATED_RID);
  }

  /** Asserts that {@code needle} occurs exactly {@code expected} times in {@code haystack}. */
  private static void assertEqualsCount(
      String message, int expected, String haystack, String needle) {
    var count = 0;
    var from = 0;
    while (true) {
      var idx = haystack.indexOf(needle, from);
      if (idx < 0) {
        break;
      }
      count++;
      from = idx + needle.length();
    }
    assertEquals(message + ", found " + count + " occurrence(s)", expected, count);
  }
}
