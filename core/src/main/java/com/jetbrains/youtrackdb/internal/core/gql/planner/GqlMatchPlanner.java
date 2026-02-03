package com.jetbrains.youtrackdb.internal.core.gql.planner;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlFetchFromClassStep;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchStatement;

/// Planner for GQL MATCH statements.
///
/// Converts a GqlMatchStatement into an execution plan with appropriate steps.
///
/// For `MATCH (a:Person)`:
/// - Creates GqlFetchFromClassStep to iterate over all Person vertices
/// - Binds each vertex to alias "a"
///
/// Applies default values:
/// - Missing alias → "_0", "_1", etc.
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
    var alias = effectiveAlias(pattern.alias(), 0);
    var label = effectiveLabel(pattern.label());

    var fetchStep = new GqlFetchFromClassStep(alias, label, true);
    plan.chain(fetchStep);

    return plan;
  }

  /// Returns effective alias, generating default "_N" if not provided.
  private static String effectiveAlias(String alias, int index) {
    return (alias == null || alias.isBlank()) ? "_" + index : alias;
  }

  /// Returns effective label, defaulting to "V" (all vertices) if not provided.
  private static String effectiveLabel(String label) {
    return (label == null || label.isBlank()) ? DEFAULT_LABEL : label;
  }
}
