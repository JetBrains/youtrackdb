package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Date;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.LinkedHashMap;
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

    // Convert YQL IR (SQLMatchFilter) to Pattern + PatternNode for execution planning
    var pattern = new Pattern();
    var aliasClasses = new LinkedHashMap<String, String>();
    var aliasFilters = new LinkedHashMap<String, SQLWhereClause>();
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

      // If the filter has inline property conditions, add them to aliasFilters
      var inlineFilter = filter.getFilter();
      if (inlineFilter != null) {
        aliasFilters.put(alias, inlineFilter);
      }
    }

    var commandContext = new BasicCommandContext(ctx.session());
    var planner = new MatchExecutionPlanner(pattern, aliasClasses, aliasFilters);
    var sqlPlan = planner.createExecutionPlan(commandContext, false, false);

    return GqlExecutionPlan.forSqlMatchPlan(sqlPlan);
  }

  /// Converts inline property filters (e.g. `{firstName: 'Karl', age: 30}`) into a
  /// `SQLWhereClause` with AND-combined equality conditions.
  /// The resulting clause is: `WHERE firstName = 'Karl' AND age = 30`.
  static SQLWhereClause buildWhereClause(Map<String, Object> properties) {
    var whereClause = new SQLWhereClause(-1);
    var andBlock = new SQLAndBlock(-1);

    for (var entry : properties.entrySet()) {
      var condition = new SQLBinaryCondition(-1);
      condition.setLeft(new SQLExpression(new SQLIdentifier(entry.getKey())));
      condition.setOperator(SQLEqualsOperator.INSTANCE);
      condition.setRight(toLiteral(entry.getValue()));
      andBlock.getSubBlocks().add(condition);
    }

    whereClause.setBaseExpression(andBlock);
    return whereClause;
  }

  /// Converts a parsed Java literal value into an `SQLExpression` that the SQL engine
  /// can evaluate. Each type maps to a dedicated SQL AST field that survives
  /// `SQLExpression.copy()` (called by `SelectExecutionPlanner` during plan creation):
  /// - String → `SQLBaseExpression` via `setMathExpression` (encoded/decoded, not an identifier)
  /// - RecordIdInternal (LINK) → `SQLRid` via `setRid`
  /// - Number (Long, Double, BigDecimal) → `SQLBaseExpression(SQLInteger)` via `setMathExpression`
  /// - Boolean → `setBooleanValue`
  /// - Date, List, Set, Map, byte[] → `setLiteralValue` (opaque value preserved through copy)
  private static SQLExpression toLiteral(Object value) {
    var expr = new SQLExpression(-1);
    if (value instanceof String s) {
      expr.setMathExpression(new SQLBaseExpression(s));
      return expr;
    }
    if (value instanceof RecordIdInternal rid) {
      var sqlRid = new SQLRid(-1);
      var collection = new SQLInteger(-1);
      collection.setValue(rid.getCollectionId());
      var position = new SQLInteger(-1);
      position.setValue(rid.getCollectionPosition());
      sqlRid.setCollection(collection);
      sqlRid.setPosition(position);
      sqlRid.setLegacy(true);
      expr.setRid(sqlRid);
      return expr;
    }
    if (value instanceof Number n) {
      var integer = new SQLInteger(-1);
      integer.setValue(n);
      expr.setMathExpression(new SQLBaseExpression(integer));
      return expr;
    }
    if (value instanceof Boolean b) {
      expr.setBooleanValue(b);
      return expr;
    }
    if (value instanceof Date || value instanceof List<?>
        || value instanceof java.util.Set<?> || value instanceof Map<?, ?>
        || value instanceof byte[]) {
      expr.setLiteralValue(value);
      return expr;
    }
    throw new IllegalArgumentException("Unsupported property value type: " + value.getClass());
  }

  private static String effectiveAlias(@Nullable String alias, int anonymousCounter) {
    return (alias != null && !alias.isBlank()) ? alias : ("$c" + anonymousCounter);
  }

  private static String effectiveType(@Nullable String label) {
    return (label == null || label.isBlank()) ? DEFAULT_TYPE : label;
  }
}
