package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlUnifiedMatchStep;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

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
    this.originalStatement = Objects.requireNonNull(originalStatement);
  }

  @SuppressWarnings("unused")
  public @Nullable String getOriginalStatement() {
    return originalStatement;
  }

  public @Nullable List<GqlMatchVisitor.NodePattern> getPatterns() {
    return patterns;
  }

  @Override
  public @Nullable GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx) {
    return createExecutionPlan(Objects.requireNonNull(ctx), true);
  }

  /// Create an execution plan, optionally using cache.
  ///
  /// @param ctx      execution context
  /// @param useCache whether to use execution plan cache
  /// @return the execution plan
  public @Nullable GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx, boolean useCache) {
    var session = Objects.requireNonNull(ctx).session();

    // Try to get from cache if enabled
    if (useCache && originalStatement != null) {
      var cachedPlan = GqlExecutionPlanCache.get(originalStatement, ctx,
          Objects.requireNonNull(session));
      if (cachedPlan != null) {
        return cachedPlan;
      }
    }

    var planningStart = System.nanoTime();

    // Use unified YQL MATCH planner and engine
    var plan = new GqlExecutionPlan();
    plan.chain(new GqlUnifiedMatchStep(Objects.requireNonNull(patterns)));

    // Cache the plan if eligible
    if (useCache
        && originalStatement != null
        && GqlExecutionPlan.canBeCached()
        && GqlExecutionPlanCache.getLastInvalidation(Objects.requireNonNull(session))
        < planningStart) {
      GqlExecutionPlanCache.put(Objects.requireNonNull(originalStatement),
          Objects.requireNonNull(plan), session);
    }

    return plan;
  }
}
