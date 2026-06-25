package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchLiteralBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import java.util.Map;
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

    // Drive the shared MATCH IR builder so the topology + alias maps land in a single
    // immutable record consumed by the planner. effectiveAlias / effectiveType keep
    // the GQL-specific defaults ($c<N> for missing aliases, "V" for missing labels).
    var builder = new MatchPatternBuilder();
    var anonymousCounter = 0;
    for (var filter : matchFilters) {
      var rawAlias = filter.getAlias();
      var alias = effectiveAlias(rawAlias, anonymousCounter);
      if (rawAlias == null || rawAlias.isBlank()) {
        anonymousCounter++;
      }
      builder.addNode(
          alias, effectiveType(filter.getClassName(null)), filter.getFilter(), /*optional=*/ false);
    }
    var ir = builder.build();

    var commandContext = new BasicCommandContext(ctx.session());
    var planner = new MatchExecutionPlanner(ir.pattern(), ir.aliasClasses(), ir.aliasFilters());
    var sqlPlan = planner.createExecutionPlan(commandContext, false, false);

    return GqlExecutionPlan.forSqlMatchPlan(sqlPlan);
  }

  /// Converts inline property filters (e.g. `{firstName: 'Karl', age: 30}`) into a
  /// `SQLWhereClause` with AND-combined equality conditions.
  /// The resulting clause is: `WHERE firstName = 'Karl' AND age = 30`.
  ///
  /// The output always wraps the conditions in an [SQLAndBlock] — including the
  /// single-property case — so the plan tree shape is independent of the input map
  /// size. [MatchWhereBuilder.and] would unwrap a single operand for parser-parity,
  /// which would shift the plan tree shape; this method preserves the historical
  /// shape that GQL tests and the visitor have depended on.
  static SQLWhereClause buildWhereClause(Map<String, Object> properties) {
    var whereBuilder = new MatchWhereBuilder();
    var andBlock = new SQLAndBlock(-1);
    for (var entry : properties.entrySet()) {
      andBlock
          .getSubBlocks()
          .add(whereBuilder.eq(entry.getKey(), MatchLiteralBuilder.toLiteral(entry.getValue())));
    }
    return whereBuilder.wrap(andBlock);
  }

  private static String effectiveAlias(@Nullable String alias, int anonymousCounter) {
    return (alias != null && !alias.isBlank()) ? alias : ("$c" + anonymousCounter);
  }

  private static String effectiveType(@Nullable String label) {
    return (label == null || label.isBlank()) ? DEFAULT_TYPE : label;
  }
}
