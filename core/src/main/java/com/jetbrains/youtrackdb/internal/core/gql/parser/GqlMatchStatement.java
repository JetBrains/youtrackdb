package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
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
  private String originalStatement;

  public GqlMatchStatement(List<GqlMatchVisitor.NodePattern> patterns) {
    this.patterns = patterns;
  }

  public void setOriginalStatement(String originalStatement) {
    this.originalStatement = originalStatement;
  }

  @SuppressWarnings("unused")
  public String getOriginalStatement() {
    return originalStatement;
  }

  public List<GqlMatchVisitor.NodePattern> getPatterns() {
    return patterns;
  }

  @Override
  public GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx) {
    return createExecutionPlan(ctx, true);
  }

  /// Create an execution plan, optionally using cache.
  ///
  /// @param ctx      execution context
  /// @param useCache whether to use execution plan cache
  /// @return the execution plan
  public GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx, boolean useCache) {
    var session = ctx.session();

    // Try to get from cache if enabled
    if (useCache && originalStatement != null) {
      var cachedPlan = GqlExecutionPlanCache.get(originalStatement, ctx, session);
      if (cachedPlan != null) {
        return cachedPlan;
      }
    }

    var planningStart = System.nanoTime();

    // Create new execution plan
    var plan = new GqlMatchPlanner(this).createExecutionPlan();

    // Cache the plan if eligible
    if (useCache
        && originalStatement != null
        && GqlExecutionPlan.canBeCached()
        && GqlExecutionPlanCache.getLastInvalidation(session) < planningStart) {
      GqlExecutionPlanCache.put(originalStatement, plan, session);
    }

    return plan;
  }
}
