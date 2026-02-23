package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.sql.executor.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/// Represents a parsed GQL MATCH statement.
///
/// Builds the shared MATCH IR ([Pattern] + alias maps) directly from GQL node patterns
/// and delegates execution to the unified YQL [MatchExecutionPlanner].
public class GqlMatchStatement implements GqlStatement {

  private static final String DEFAULT_TYPE = "V";

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

  @SuppressWarnings("unused")
  public @Nullable List<GqlMatchVisitor.NodePattern> getPatterns() {
    return patterns;
  }

  @Override
  public GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx) {
    return createExecutionPlan(Objects.requireNonNull(ctx), true);
  }

  /// Create an execution plan, optionally using cache.
  public GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx, boolean useCache) {
    var session = Objects.requireNonNull(ctx).session();

    if (useCache && originalStatement != null) {
      var cachedPlan = GqlExecutionPlanCache.get(originalStatement, ctx,
          Objects.requireNonNull(session));
      if (cachedPlan != null) {
        return cachedPlan;
      }
    }

    var planningStart = System.nanoTime();

    var plan = buildPlan(ctx);

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

  private GqlExecutionPlan buildPlan(GqlExecutionContext ctx) {
    if (patterns.isEmpty()) {
      return GqlExecutionPlan.empty();
    }

    var pattern = new Pattern();
    var aliasClasses = new LinkedHashMap<String, String>();
    var anonymousCounter = 0;

    for (var p : patterns) {
      var alias = effectiveAlias(p.alias(), anonymousCounter);
      if (p.alias() == null || p.alias().isBlank()) {
        anonymousCounter++;
      }
      pattern.addNode(alias);
      aliasClasses.put(alias, effectiveType(p.label()));
    }

    var commandContext = new BasicCommandContext(ctx.session());
    var planner = new MatchExecutionPlanner(pattern, aliasClasses, null, null);
    var sqlPlan = planner.createExecutionPlan(commandContext, false);

    return GqlExecutionPlan.forSqlMatchPlan(sqlPlan);
  }

  private static String effectiveAlias(@Nullable String alias, int anonymousCounter) {
    return (alias != null && !alias.isBlank()) ? alias : ("$c" + anonymousCounter);
  }

  private static String effectiveType(@Nullable String label) {
    return (label == null || label.isBlank()) ? DEFAULT_TYPE : label;
  }
}
