package com.jetbrains.youtrackdb.internal.core.gql.planner;

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

    // For now, support single node pattern: MATCH (a:Label)
    // Future: support multiple patterns, relationships, etc.
    var pattern = patterns.getFirst();
    var rawAlias = pattern.alias();
    var hasAlias = rawAlias != null && !rawAlias.isBlank();
    var alias = hasAlias ? rawAlias : null;
    var label = effectiveLabel(pattern.label());

    var fetchStep = new GqlFetchFromClassStep(alias, label, true, hasAlias);
    plan.chain(fetchStep);

    return plan;
  }

  /// Returns effective label, defaulting to "V" (all vertices) if not provided.
  private static String effectiveLabel(String label) {
    return (label == null || label.isBlank()) ? DEFAULT_LABEL : label;
  }
}
