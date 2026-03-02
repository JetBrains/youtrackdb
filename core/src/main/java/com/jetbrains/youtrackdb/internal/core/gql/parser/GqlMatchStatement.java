package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.LinkedHashMap;
import java.util.List;
import javax.annotation.Nullable;

/// Represents a parsed GQL MATCH statement.
///
/// Builds the shared MATCH IR ([Pattern] + alias maps) directly from GQL match filters (unified YQL IR)
/// and delegates execution to the unified YQL [MatchExecutionPlanner].
public class GqlMatchStatement implements GqlStatement {

  private static final String DEFAULT_TYPE = "V";

  private final List<SQLMatchFilter> matchFilters;
  private String originalStatement;

  public GqlMatchStatement(List<SQLMatchFilter> matchFilters) {
    this.matchFilters = matchFilters;
  }

  public void setOriginalStatement(String originalStatement) {
    this.originalStatement = originalStatement;
  }

  @SuppressWarnings("unused")
  public @Nullable String getOriginalStatement() {
    return originalStatement;
  }

  @SuppressWarnings("unused")
  public @Nullable List<SQLMatchFilter> getMatchFilters() {
    return matchFilters;
  }

  @Override
  public GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx) {
    return createExecutionPlan(ctx, true);
  }

  /// Create an execution plan, optionally using cache.
  public GqlExecutionPlan createExecutionPlan(GqlExecutionContext ctx, boolean useCache) {
    var session = ctx.session();

    if (useCache && originalStatement != null) {
      var cachedPlan = GqlExecutionPlanCache.get(originalStatement, ctx, session);
      if (cachedPlan != null) {
        return cachedPlan;
      }
    }

    var planningStart = System.nanoTime();

    var plan = buildPlan(ctx);

    if (useCache
        && originalStatement != null
        && GqlExecutionPlan.canBeCached()
        && GqlExecutionPlanCache.getLastInvalidation(session) < planningStart) {
      GqlExecutionPlanCache.put(originalStatement, plan, session);
    }

    return plan;
  }

  private GqlExecutionPlan buildPlan(GqlExecutionContext ctx) {
    if (matchFilters.isEmpty()) {
      return GqlExecutionPlan.empty();
    }

    // Convert YQL IR (SQLMatchFilter) to Pattern + PatternNode for execution planning
    var pattern = new Pattern();
    var aliasClasses = new LinkedHashMap<String, String>();
    var anonymousCounter = 0;

    for (var filter : matchFilters) {
      var rawAlias = filter.getAlias();
      var alias = effectiveAlias(rawAlias, anonymousCounter);
      if (rawAlias == null || rawAlias.isBlank()) {
        anonymousCounter++;
      }
      var node = new PatternNode();
      node.alias = alias;
      pattern.aliasToNode.put(alias, node);

      var className = filter.getClassName(null);
      aliasClasses.put(alias, effectiveType(className));
    }

    var commandContext = new BasicCommandContext(ctx.session());
    var planner = new MatchExecutionPlanner(pattern, aliasClasses);
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
