package com.jetbrains.youtrackdb.internal.core.gql.planner;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlCrossJoinClassStep;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlFetchFromClassStep;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchStatement;

/// Planner for GQL MATCH statements.
///
/// Converts a GqlMatchStatement into an execution plan with appropriate steps.
///
/// Result type depends on whether alias was provided:
/// - With alias `MATCH (a:Person)` → returns Map<String, Object> with {"a": vertex}
/// - Without alias `MATCH (:Person)` → returns just the Vertex directly (alias=null)
///
/// Default values:
/// - Missing label → "V" (all vertices)
public class GqlMatchPlanner {

  /// Default label when not specified (base vertex class = all vertices).
  private static final String DEFAULT_LABEL = "V";

  private final GqlMatchStatement statement;

  public GqlMatchPlanner(GqlMatchStatement statement) {
    this.statement = statement;
  }

  /// Create an execution plan for the MATCH statement.
  public GqlExecutionPlan createExecutionPlan() {
    var plan = new GqlExecutionPlan();
    var patterns = statement.getPatterns();

    if (patterns.isEmpty()) {
      throw new IllegalArgumentException("MATCH query requires at least one node pattern");
    }

    for (int i = 0; i < patterns.size(); i++) {
      var pattern = patterns.get(i);
      var alias = pattern.alias();
      var label = effectiveLabel(pattern.label());
      var hasAlias = alias != null && !alias.isBlank();

      if (i == 0) {
        plan.chain(new GqlFetchFromClassStep(alias, label, true, hasAlias));
      } else {
        plan.chain(new GqlCrossJoinClassStep(alias, label, true));
      }
    }

    return plan;
  }

  /// Returns effective label, defaulting to "V" (all vertices) if not provided.
  private static String effectiveLabel(String label) {
    return (label == null || label.isBlank()) ? DEFAULT_LABEL : label;
  }
}
