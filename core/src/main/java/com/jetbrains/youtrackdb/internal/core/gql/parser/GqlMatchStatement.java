package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlMatchPlanner;
import java.util.List;

/// Represents a GQL MATCH statement.
///
/// Examples:
/// - `MATCH (a:Person)` → returns Map<String, Object> with {"a": vertex}
/// - `MATCH (:Person)` → returns Vertex directly (no alias binding)
///
/// Contains the parsed node patterns from the MATCH clause.
public class GqlMatchStatement implements GqlStatement {

  private final List<GqlMatchVisitor.NodePattern> patterns;

  public GqlMatchStatement(List<GqlMatchVisitor.NodePattern> patterns) {
    this.patterns = patterns;
  }

  public List<GqlMatchVisitor.NodePattern> getPatterns() {
    return patterns;
  }

  @Override
  public GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx) {
    return new GqlMatchPlanner(this).createExecutionPlan();
  }
}
