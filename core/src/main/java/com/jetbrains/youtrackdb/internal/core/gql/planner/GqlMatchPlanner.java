package com.jetbrains.youtrackdb.internal.core.gql.planner;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlCrossJoinClassStep;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlFetchFromClassStep;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchStatement;

/// Planner for GQL MATCH statements.
///
/// Converts a GqlMatchStatement into an execution plan with appropriate steps.
///
/// `MATCH (a:Person)` → returns Map<String, Object> with {"a": vertex}
///
/// Default values:
/// - Missing type → "V" (all vertices)
/// - Missing alias → default alias ("$cN" where N is a number)
public class GqlMatchPlanner {

  /// Default type when not specified (base vertex class = all vertices).
  private static final String DEFAULT_TYPE = "V";

  private final GqlMatchStatement statement;
  private int anonymousCounter = 0;

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

    for (var i = 0; i < patterns.size(); i++) {
      var pattern = patterns.get(i);
      var alias = effectiveAlias(pattern.alias());
      var type = effectiveType(pattern.label());

      if (i == 0) {
        plan.chain(new GqlFetchFromClassStep(alias, type, true));
      } else {
        plan.chain(new GqlCrossJoinClassStep(alias, type, true));
      }
    }

    return plan;
  }

  /// Returns an effective vertex type (class name), defaulting to "V" (all vertices) if not provided.
  private static String effectiveType(String type) {
    return (type == null || type.isBlank()) ? DEFAULT_TYPE : type;
  }

  /// Returns effective alias, defaulting to "$c{anonymousCounter}" if not provided.
  private String effectiveAlias(String alias) {
    return (alias == null || alias.isBlank()) ? generateDefaultAlias() : alias;
  }

  private String generateDefaultAlias() {
    return "$c" + this.anonymousCounter++;
  }
}
