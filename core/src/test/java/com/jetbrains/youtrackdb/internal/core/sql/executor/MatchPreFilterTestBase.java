package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared helpers for MATCH pre-filter optimization tests. Provides
 * {@code explainPlan()} overloads and {@code collectProperty()} used
 * by both {@link MatchPreFilterComprehensiveTest} and
 * {@link MatchPreFilterSchemaVariationsTest}.
 */
public abstract class MatchPreFilterTestBase extends DbTestBase {

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

  /** Collects a single string property from all result rows into a set. */
  protected Set<String> collectProperty(String query, String property) {
    return session.query(query).toList().stream()
        .map(r -> (String) r.getProperty(property))
        .collect(Collectors.toSet());
  }
}
