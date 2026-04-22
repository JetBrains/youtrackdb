package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared helpers for MATCH pre-filter optimization tests. Provides
 * {@code explainPlan()} overloads, result-collection helpers, and
 * EXPLAIN assertion shortcuts used by both
 * {@link MatchPreFilterComprehensiveTest} and
 * {@link MatchPreFilterSchemaVariationsTest}.
 */
public abstract class MatchPreFilterTestBase extends DbTestBase {

  // ---- EXPLAIN helpers ----

  /** Runs EXPLAIN and returns the executionPlanAsString. */
  protected String explainPlan(String query) {
    var result = session.query("EXPLAIN " + query).toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    return plan;
  }

  /** Runs EXPLAIN with positional parameters and returns the plan. */
  protected String explainPlan(String query, Object... args) {
    var result = session.query("EXPLAIN " + query, args).toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    return plan;
  }

  /** Runs EXPLAIN with named parameters and returns the plan. */
  protected String explainPlan(String query, Map<String, Object> params) {
    var result = session.query("EXPLAIN " + query, params).toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    return plan;
  }

  // ---- EXPLAIN assertion shortcuts ----

  /**
   * Asserts the EXPLAIN plan contains evidence of back-ref optimization:
   * either {@code "intersection:"} (EdgeRidLookup) or
   * {@code "BACK-REF HASH JOIN"} (BackRefHashJoinStep).
   *
   * <p>This is an intentionally-permissive legacy helper — the caller
   * accepts whichever optimization family the planner currently selects.
   * Prefer the narrower {@link #assertPlanUsesBackRefHashJoin} or
   * {@link #assertPlanUsesEdgeRidLookup} when the test is meant to guard
   * a specific optimization path; those fail if the plan silently switches
   * family on a regression.
   */
  protected void assertPlanHasIntersection(String query, String reason) {
    String plan = explainPlan(query);
    assertTrue(reason + ":\n" + plan,
        plan.contains("intersection:") || plan.contains("BACK-REF HASH JOIN"));
  }

  /** Named-parameter variant of {@link #assertPlanHasIntersection}. */
  protected void assertPlanHasIntersection(
      String query, Map<String, Object> params, String reason) {
    String plan = explainPlan(query, params);
    assertTrue(reason + ":\n" + plan,
        plan.contains("intersection:") || plan.contains("BACK-REF HASH JOIN"));
  }

  /**
   * Asserts the EXPLAIN plan uses the BackRefHashJoin optimization
   * ({@code "BACK-REF HASH JOIN"} marker). Use this for queries where the
   * planner is expected to select Pattern A/B/D and the assertion must fail
   * if the plan silently falls back to the EdgeRidLookup family.
   */
  protected void assertPlanUsesBackRefHashJoin(String query, String reason) {
    String plan = explainPlan(query);
    assertTrue(reason + ":\n" + plan, plan.contains("BACK-REF HASH JOIN"));
  }

  /**
   * Asserts the EXPLAIN plan uses the legacy EdgeRidLookup intersection
   * ({@code "intersection:"} marker) — for queries whose shape is known
   * to fall outside the Pattern A/B/D semi-join criteria and must still
   * benefit from the index-intersection pre-filter.
   */
  protected void assertPlanUsesEdgeRidLookup(String query, String reason) {
    String plan = explainPlan(query);
    assertTrue(reason + ":\n" + plan, plan.contains("intersection:"));
  }

  /** Asserts the EXPLAIN plan contains {@code "intersection: index"}. */
  protected void assertPlanHasIndexIntersection(
      String query, String reason) {
    String plan = explainPlan(query);
    assertTrue(reason + ":\n" + plan,
        plan.contains("intersection: index"));
  }

  /**
   * Asserts the EXPLAIN plan does NOT contain any back-ref optimization:
   * neither {@code "intersection:"} nor {@code "BACK-REF HASH JOIN"}.
   */
  protected void assertPlanHasNoIntersection(String query, String reason) {
    String plan = explainPlan(query);
    assertFalse(reason + ":\n" + plan,
        plan.contains("intersection:") || plan.contains("BACK-REF HASH JOIN"));
  }

  /** Named-parameter variant of {@link #assertPlanUsesBackRefHashJoin}. */
  protected void assertPlanUsesBackRefHashJoin(
      String query, Map<String, Object> params, String reason) {
    String plan = explainPlan(query, params);
    assertTrue(reason + ":\n" + plan, plan.contains("BACK-REF HASH JOIN"));
  }

  /** Named-parameter variant of {@link #assertPlanUsesEdgeRidLookup}. */
  protected void assertPlanUsesEdgeRidLookup(
      String query, Map<String, Object> params, String reason) {
    String plan = explainPlan(query, params);
    assertTrue(reason + ":\n" + plan, plan.contains("intersection:"));
  }

  /** Named-parameter variant of {@link #assertPlanHasIndexIntersection}. */
  protected void assertPlanHasIndexIntersection(
      String query, Map<String, Object> params, String reason) {
    String plan = explainPlan(query, params);
    assertTrue(reason + ":\n" + plan,
        plan.contains("intersection: index"));
  }

  // ---- Result-collection helpers ----

  /** Collects a single string property from all result rows into a set. */
  protected Set<String> collectProperty(String query, String property) {
    return session.query(query).toList().stream()
        .map(r -> (String) r.getProperty(property))
        .collect(Collectors.toSet());
  }

  /**
   * Collects a single string property from a pre-fetched result list
   * into a set.
   */
  protected Set<String> collectProperty(
      List<Result> results, String property) {
    Set<String> set = new HashSet<>();
    for (var r : results) {
      set.add(r.getProperty(property));
    }
    return set;
  }
}
