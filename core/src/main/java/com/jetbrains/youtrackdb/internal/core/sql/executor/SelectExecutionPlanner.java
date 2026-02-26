package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.util.PairIntegerObject;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.AggregateProjectionSplit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInputParameter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLetClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLetItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMetadataIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTimeout;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SubQueryCollector;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class SelectExecutionPlanner {

  private QueryPlanningInfo info;
  private final SQLSelectStatement statement;

  public SelectExecutionPlanner(SQLSelectStatement oSelectStatement) {
    this.statement = oSelectStatement;
  }

  private void init(CommandContext ctx) {
    // copying the content, so that it can be manipulated and optimized
    info = new QueryPlanningInfo();
    info.projection =
        this.statement.getProjection() == null ? null : this.statement.getProjection().copy();
    info.projection = translateDistinct(info.projection);
    info.distinct = info.projection != null && info.projection.isDistinct();
    if (info.projection != null) {
      info.projection.setDistinct(false);
    }

    info.target = this.statement.getTarget();
    info.whereClause =
        this.statement.getWhereClause() == null ? null : this.statement.getWhereClause().copy();
    info.whereClause = translateLucene(info.whereClause);
    info.perRecordLetClause =
        this.statement.getLetClause() == null ? null : this.statement.getLetClause().copy();
    info.groupBy = this.statement.getGroupBy() == null ? null : this.statement.getGroupBy().copy();
    info.orderBy = this.statement.getOrderBy() == null ? null : this.statement.getOrderBy().copy();
    info.unwind = this.statement.getUnwind() == null ? null : this.statement.getUnwind().copy();
    info.skip = this.statement.getSkip();
    info.limit = this.statement.getLimit();
    info.timeout = this.statement.getTimeout() == null ? null : this.statement.getTimeout().copy();
    if (info.timeout == null
        &&
        ctx.getDatabaseSession().getConfiguration()
            .getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT)
            > 0) {
      info.timeout = new SQLTimeout(-1);
      info.timeout.setVal(
          ctx.getDatabaseSession()
              .getConfiguration()
              .getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT));
    }
  }

  public InternalExecutionPlan createExecutionPlan(
      CommandContext ctx, boolean enableProfiling, boolean useCache) {
    var session = ctx.getDatabaseSession();
    if (useCache && !enableProfiling && statement.executinPlanCanBeCached(session)) {
      var plan = ExecutionPlanCache.get(statement.getOriginalStatement(), ctx, session);
      if (plan != null) {
        return (InternalExecutionPlan) plan;
      }
    }

    var planningStart = System.currentTimeMillis();

    init(ctx);
    var result = new SelectExecutionPlan(ctx);

    if (info.expand && info.distinct) {
      throw new CommandExecutionException(session,
          "Cannot execute a statement with DISTINCT expand(), please use a subquery");
    }

    optimizeQuery(info, ctx);

    if (handleHardwiredOptimizations(result, ctx, enableProfiling)) {
      return result;
    }

    handleGlobalLet(result, info, ctx, enableProfiling);

    handleFetchFromTarget(result, info, ctx, enableProfiling);

    handleLet(result, info, ctx, enableProfiling);

    handleWhere(result, info, ctx, enableProfiling);

    handleProjectionsBlock(result, info, ctx, enableProfiling);

    if (info.timeout != null) {
      result.chain(new AccumulatingTimeoutStep(info.timeout, ctx, enableProfiling));
    }

    if (useCache
        && !enableProfiling
        && statement.executinPlanCanBeCached(session)
        && result.canBeCached()
        && ExecutionPlanCache.getLastInvalidation(session) < planningStart) {
      ExecutionPlanCache.put(statement.getOriginalStatement(), result, ctx.getDatabaseSession());
    }
    return result;
  }

  public static void handleProjectionsBlock(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean enableProfiling) {
    handleProjectionsBeforeOrderBy(result, info, ctx, enableProfiling);

    if (info.expand || info.unwind != null || info.groupBy != null) {

      handleProjections(result, info, ctx, enableProfiling);
      handleExpand(result, info, ctx, enableProfiling);
      handleUnwind(result, info, ctx, enableProfiling);
      handleOrderBy(result, info, ctx, enableProfiling);
      if (info.skip != null) {
        result.chain(new SkipExecutionStep(info.skip, ctx, enableProfiling));
      }
      if (info.limit != null) {
        result.chain(new LimitExecutionStep(info.limit, ctx, enableProfiling));
      }
    } else {
      handleOrderBy(result, info, ctx, enableProfiling);
      if (info.distinct || info.groupBy != null || info.aggregateProjection != null) {
        handleProjections(result, info, ctx, enableProfiling);
        handleDistinct(result, info, ctx, enableProfiling);
        if (info.skip != null) {
          result.chain(new SkipExecutionStep(info.skip, ctx, enableProfiling));
        }
        if (info.limit != null) {
          result.chain(new LimitExecutionStep(info.limit, ctx, enableProfiling));
        }
      } else {
        if (info.skip != null) {
          result.chain(new SkipExecutionStep(info.skip, ctx, enableProfiling));
        }
        if (info.limit != null) {
          result.chain(new LimitExecutionStep(info.limit, ctx, enableProfiling));
        }
        handleProjections(result, info, ctx, enableProfiling);
      }
    }
  }

  @Nullable
  private static SQLWhereClause translateLucene(SQLWhereClause whereClause) {
    if (whereClause == null) {
      return null;
    }

    if (whereClause.getBaseExpression() != null) {
      whereClause.getBaseExpression().translateLuceneOperator();
    }
    return whereClause;
  }

  /**
   * for backward compatibility, translate "distinct(foo)" to "DISTINCT foo". This method modifies
   * the projection itself.
   *
   * @param projection the projection
   */
  public static SQLProjection translateDistinct(SQLProjection projection) {
    if (projection != null && projection.getItems().size() == 1) {
      if (isDistinct(projection.getItems().getFirst())) {
        projection = projection.copy();
        var item = projection.getItems().getFirst();
        var function =
            ((SQLBaseExpression) item.getExpression().getMathExpression())
                .getIdentifier()
                .getLevelZero()
                .getFunctionCall();
        var exp = function.getParams().getFirst();
        var resultItem = new SQLProjectionItem(-1);
        resultItem.setAlias(item.getAlias());
        resultItem.setExpression(exp.copy());
        var result = new SQLProjection(-1);
        result.setItems(new ArrayList<>());
        result.setDistinct(true);
        result.getItems().add(resultItem);
        return result;
      }
    }
    return projection;
  }

  /**
   * checks if a projection is a distinct(expr). In new executor the distinct() function is not
   * supported, so "distinct(expr)" is translated to "DISTINCT expr"
   *
   * @param item the projection
   */
  private static boolean isDistinct(SQLProjectionItem item) {
    if (item.getExpression() == null) {
      return false;
    }
    if (item.getExpression().getMathExpression() == null) {
      return false;
    }
    if (!(item.getExpression().getMathExpression() instanceof SQLBaseExpression base)) {
      return false;
    }
    if (base.getIdentifier() == null) {
      return false;
    }
    if (base.getModifier() != null) {
      return false;
    }
    if (base.getIdentifier().getLevelZero() == null) {
      return false;
    }
    var function = base.getIdentifier().getLevelZero().getFunctionCall();
    if (function == null) {
      return false;
    }
    return function.getName().getStringValue().equalsIgnoreCase("distinct");
  }

  private boolean handleHardwiredOptimizations(
      SelectExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {
    if (handleHardwiredCountOnClass(result, info, ctx, profilingEnabled)) {
      return true;
    }
    return handleHardwiredCountOnClassUsingIndex(result, info, ctx, profilingEnabled);
  }

  private static boolean handleHardwiredCountOnClass(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var session = ctx.getDatabaseSession();
    var targetClass = info.target == null ? null : info.target.getSchemaClass(session);
    if (targetClass == null) {
      return false;
    }

    if (info.distinct || info.expand) {
      return false;
    }
    if (info.preAggregateProjection != null) {
      return false;
    }
    if (!isCountStar(info)) {
      return false;
    }
    if (!isMinimalQuery(info)) {
      return false;
    }
    if (securityPoliciesExistForClass(targetClass, ctx)) {
      return false;
    }
    result.chain(
        new CountFromClassStep(
            targetClass, info.projection.getAllAliases().iterator().next(), ctx, profilingEnabled));
    return true;
  }

  private static boolean securityPoliciesExistForClass(SchemaClassInternal targetClass,
      CommandContext ctx) {
    if (targetClass == null) {
      return false;
    }

    var session = ctx.getDatabaseSession();
    var security = session.getSharedContext().getSecurity();

    return security.isReadRestrictedBySecurityPolicy(session,
        "database.class." + targetClass.getName());
  }

  private static boolean handleHardwiredCountOnClassUsingIndex(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var session = ctx.getDatabaseSession();
    var targetClass = info.target == null ? null : info.target.getSchemaClass(session);
    if (targetClass == null) {
      return false;
    }
    if (info.distinct || info.expand) {
      return false;
    }
    if (info.preAggregateProjection != null) {
      return false;
    }
    if (!isCountStar(info)) {
      return false;
    }
    if (info.projectionAfterOrderBy != null
        || info.globalLetClause != null
        || info.perRecordLetClause != null
        || info.groupBy != null
        || info.orderBy != null
        || info.unwind != null
        || info.skip != null) {
      return false;
    }

    if (info.flattenedWhereClause == null
        || info.flattenedWhereClause.size() > 1
        || info.flattenedWhereClause.getFirst().getSubBlocks().size() > 1) {
      // for now it only handles a single equality condition, it can be extended
      return false;
    }
    var condition = info.flattenedWhereClause.getFirst().getSubBlocks().getFirst();
    if (!(condition instanceof SQLBinaryCondition binaryCondition)) {
      return false;
    }
    if (!binaryCondition.getLeft().isBaseIdentifier()) {
      return false;
    }
    if (!(binaryCondition.getOperator() instanceof SQLEqualsOperator)) {
      // this can be extended to use range operators too
      return false;
    }
    if (securityPoliciesExistForClass(targetClass, ctx)) {
      return false;
    }

    for (var classIndex : targetClass.getClassIndexesInternal()) {
      var fields = classIndex.getDefinition().getProperties();
      if (fields.size() == 1
          && fields.getFirst()
          .equals(binaryCondition.getLeft().getDefaultAlias().getStringValue())) {
        var expr = binaryCondition.getRight();
        result.chain(
            new CountFromIndexWithKeyStep(
                new SQLIndexIdentifier(classIndex.getName(), SQLIndexIdentifier.Type.INDEX),
                expr,
                info.projection.getAllAliases().iterator().next(),
                ctx,
                profilingEnabled));
        return true;
      }
    }

    return false;
  }

  /**
   * returns true if the query is minimal, ie. no WHERE condition, no SKIP/LIMIT, no UNWIND, no
   * GROUP/ORDER BY, no LET
   */
  private static boolean isMinimalQuery(QueryPlanningInfo info) {
    return info.projectionAfterOrderBy == null
        && info.globalLetClause == null
        && info.perRecordLetClause == null
        && info.whereClause == null
        && info.flattenedWhereClause == null
        && info.groupBy == null
        && info.orderBy == null
        && info.unwind == null
        && info.skip == null;
  }

  private static boolean isCountStar(QueryPlanningInfo info) {
    if (info.aggregateProjection == null
        || info.projection == null
        || info.aggregateProjection.getItems().size() != 1
        || info.projection.getItems().size() != 1) {
      return false;
    }
    var item = info.aggregateProjection.getItems().getFirst();
    return item.getExpression().toString().equalsIgnoreCase("count(*)");
  }

  private static boolean isCountOnly(QueryPlanningInfo info) {
    if (info.aggregateProjection == null
        || info.projection == null
        || info.aggregateProjection.getItems().size() != 1
        || info.projection.getItems().stream()
        .filter(x -> !x.getProjectionAliasAsString().startsWith("_$$$ORDER_BY_ALIAS$$$_"))
        .count()
        != 1) {
      return false;
    }
    var item = info.aggregateProjection.getItems().getFirst();
    var exp = item.getExpression();
    if (exp.getMathExpression() != null
        && exp.getMathExpression() instanceof SQLBaseExpression base) {
      return base.isCount() && base.getModifier() == null;
    }
    return false;
  }

  public static void handleUnwind(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.unwind != null) {
      result.chain(new UnwindStep(info.unwind, ctx, profilingEnabled));
    }
  }

  private static void handleDistinct(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.distinct) {
      result.chain(new DistinctExecutionStep(ctx, profilingEnabled));
    }
  }

  private static void handleProjectionsBeforeOrderBy(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.orderBy != null) {
      handleProjections(result, info, ctx, profilingEnabled);
    }
  }

  private static void handleProjections(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (!info.projectionsCalculated && info.projection != null) {
      if (info.preAggregateProjection != null) {
        result.chain(
            new ProjectionCalculationStep(info.preAggregateProjection, ctx, profilingEnabled));
      }
      if (info.aggregateProjection != null) {
        long aggregationLimit = -1;
        if (info.orderBy == null && info.limit != null) {
          aggregationLimit = info.limit.getValue(ctx);
          if (info.skip != null && info.skip.getValue(ctx) > 0) {
            aggregationLimit += info.skip.getValue(ctx);
          }
        }
        result.chain(
            new AggregateProjectionCalculationStep(
                info.aggregateProjection,
                info.groupBy,
                aggregationLimit,
                ctx,
                info.timeout != null ? info.timeout.getVal().longValue() : -1,
                profilingEnabled));
        if (isCountOnly(info) && info.groupBy == null) {
          result.chain(
              new GuaranteeEmptyCountStep(
                  info.aggregateProjection.getItems().getFirst(), ctx, profilingEnabled));
        }
      }
      result.chain(new ProjectionCalculationStep(info.projection, ctx, profilingEnabled));

      info.projectionsCalculated = true;
    }
  }

  public static void optimizeQuery(QueryPlanningInfo info, CommandContext ctx) {
    splitLet(info, ctx);
    rewriteIndexChainsAsSubqueries(info, ctx);
    extractSubQueries(info);
    if (info.projection != null && info.projection.isExpand()) {
      info.expand = true;
      info.expandAlias = info.projection.getExpandAlias();
      info.projection = info.projection.getExpandContent();
    }

    if (info.whereClause != null) {
      if (info.target == null) {
        info.flattenedWhereClause = info.whereClause.flatten(ctx, null);
      } else {
        info.flattenedWhereClause = info.whereClause.flatten(ctx,
            info.target.getSchemaClass(ctx.getDatabaseSession()));
      }
      // this helps index optimization
      info.flattenedWhereClause = moveFlattenedEqualitiesLeft(info.flattenedWhereClause);
    }

    splitProjectionsForGroupBy(info, ctx);
    addOrderByProjections(info);
  }

  private static void rewriteIndexChainsAsSubqueries(QueryPlanningInfo info, CommandContext ctx) {
    if (ctx == null) {
      return;
    }

    var session = ctx.getDatabaseSession();
    if (session == null) {
      return;
    }

    if (info.whereClause != null
        && info.target != null) {
      var clazz = info.target.getSchemaClass(session);
      if (clazz != null) {
        info.whereClause.getBaseExpression().rewriteIndexChainsAsSubqueries(ctx, clazz);
      }
    }
  }

  /**
   * splits LET clauses in global (executed once) and local (executed once per record)
   */
  private static void splitLet(QueryPlanningInfo info, CommandContext ctx) {
    if (info.perRecordLetClause != null && info.perRecordLetClause.getItems() != null) {
      var iterator = info.perRecordLetClause.getItems().iterator();
      while (iterator.hasNext()) {
        var item = iterator.next();
        if (item.getExpression() != null
            && (item.getExpression().isEarlyCalculated(ctx)
            || isCombinationOfQueries(item.getExpression()))) {
          iterator.remove();
          addGlobalLet(info, item.getVarName(), item.getExpression());
        } else if (item.getQuery() != null && !item.getQuery().refersToParent()) {
          iterator.remove();
          addGlobalLet(info, item.getVarName(), item.getQuery());
        }
      }
    }
  }

  private static final Set<String> COMBINATION_FUNCTIONS =
      Set.of("unionall", "intersect", "difference");

  private static boolean isCombinationOfQueries(SQLExpression expression) {
    if (expression.getMathExpression() instanceof SQLBaseExpression exp) {
      if (exp.getIdentifier() != null
          && exp.getModifier() == null
          && exp.getIdentifier().getLevelZero() != null
          && exp.getIdentifier().getLevelZero().getFunctionCall() != null) {
        var fc = exp.getIdentifier().getLevelZero().getFunctionCall();
        if (COMBINATION_FUNCTIONS.stream()
            .anyMatch(fc.getName().getStringValue()::equalsIgnoreCase)) {
          for (var param : fc.getParams()) {
            if (!param.toString().isEmpty() && param.toString().charAt(0) == '$') {
              return true;
            }
          }
          return true;
        }
      }
    }
    return false;
  }

  /**
   * re-writes a list of flat AND conditions, moving left all the equality operations
   */
  @Nullable
  private static List<SQLAndBlock> moveFlattenedEqualitiesLeft(
      List<SQLAndBlock> flattenedWhereClause) {
    if (flattenedWhereClause == null) {
      return null;
    }

    List<SQLAndBlock> result = new ArrayList<>();
    for (var block : flattenedWhereClause) {
      List<SQLBooleanExpression> equalityExpressions = new ArrayList<>();
      List<SQLBooleanExpression> nonEqualityExpressions = new ArrayList<>();
      var newBlock = block.copy();
      for (var exp : newBlock.getSubBlocks()) {
        if (exp instanceof SQLBinaryCondition binCond) {
          if (binCond.getOperator() instanceof SQLEqualsOperator) {
            equalityExpressions.add(exp);
          } else {
            nonEqualityExpressions.add(exp);
          }
        } else {
          nonEqualityExpressions.add(exp);
        }
      }
      var newAnd = new SQLAndBlock(-1);
      newAnd.getSubBlocks().addAll(equalityExpressions);
      newAnd.getSubBlocks().addAll(nonEqualityExpressions);
      result.add(newAnd);
    }

    return result;
  }

  /**
   * creates additional projections for ORDER BY
   */
  private static void addOrderByProjections(QueryPlanningInfo info) {
    if (info.orderApplied
        || info.expand
        || info.unwind != null
        || info.orderBy == null
        || info.orderBy.getItems().isEmpty()
        || info.projection == null
        || info.projection.getItems() == null
        || (info.projection.getItems().size() == 1 && info.projection.getItems().getFirst()
        .isAll())) {
      return;
    }

    var newOrderBy = info.orderBy == null ? null : info.orderBy.copy();
    var additionalOrderByProjections =
        calculateAdditionalOrderByProjections(info.projection.getAllAliases(), newOrderBy);
    if (!additionalOrderByProjections.isEmpty()) {
      info.orderBy = newOrderBy; // the ORDER BY has changed
    }
    if (!additionalOrderByProjections.isEmpty()) {
      info.projectionAfterOrderBy = new SQLProjection(-1);
      info.projectionAfterOrderBy.setItems(new ArrayList<>());
      for (var alias : info.projection.getAllAliases()) {
        info.projectionAfterOrderBy.getItems().add(projectionFromAlias(new SQLIdentifier(alias)));
      }

      for (var item : additionalOrderByProjections) {
        if (info.preAggregateProjection != null) {
          info.preAggregateProjection.getItems().add(item);
          info.aggregateProjection.getItems().add(projectionFromAlias(item.getAlias()));
          info.projection.getItems().add(projectionFromAlias(item.getAlias()));
        } else {
          info.projection.getItems().add(item);
        }
      }
    }
  }

  /**
   * given a list of aliases (present in the existing projections) calculates a list of additional
   * projections to add to the existing projections to allow ORDER BY calculation. The sorting
   * clause will be modified with new replaced aliases
   *
   * @param allAliases existing aliases in the projection
   * @param orderBy    sorting clause
   * @return a list of additional projections to add to the existing projections to allow ORDER BY
   * calculation (empty if nothing has to be added).
   */
  private static List<SQLProjectionItem> calculateAdditionalOrderByProjections(
      Set<String> allAliases, SQLOrderBy orderBy) {
    List<SQLProjectionItem> result = new ArrayList<>();
    var nextAliasCount = 0;
    if ((orderBy != null && orderBy.getItems() != null) || !orderBy.getItems().isEmpty()) {
      for (var item : orderBy.getItems()) {
        if (!allAliases.contains(item.getAlias())) {
          var newProj = new SQLProjectionItem(-1);
          if (item.getAlias() != null) {
            newProj.setExpression(
                new SQLExpression(new SQLIdentifier(item.getAlias()), item.getModifier()));
          } else if (item.getRecordAttr() != null) {
            var attr = new SQLRecordAttribute(-1);
            attr.setName(item.getRecordAttr());
            newProj.setExpression(new SQLExpression(attr, item.getModifier()));
          } else if (item.getRid() != null) {
            var exp = new SQLExpression(-1);
            exp.setRid(item.getRid().copy());
            newProj.setExpression(exp);
          }
          var newAlias = new SQLIdentifier("_$$$ORDER_BY_ALIAS$$$_" + nextAliasCount++);
          newProj.setAlias(newAlias);
          item.setAlias(newAlias.getStringValue());
          item.setModifier(null);
          result.add(newProj);
        }
      }
    }
    return result;
  }

  /**
   * splits projections in three parts (pre-aggregate, aggregate and final) to efficiently manage
   * aggregations
   */
  private static void splitProjectionsForGroupBy(QueryPlanningInfo info, CommandContext ctx) {
    if (info.projection == null) {
      return;
    }

    var preAggregate = new SQLProjection(-1);
    preAggregate.setItems(new ArrayList<>());
    var aggregate = new SQLProjection(-1);
    aggregate.setItems(new ArrayList<>());
    var postAggregate = new SQLProjection(-1);
    postAggregate.setItems(new ArrayList<>());

    var isSplitted = false;

    var db = ctx.getDatabaseSession();
    // split for aggregate projections
    var result = new AggregateProjectionSplit();
    for (var item : info.projection.getItems()) {
      result.reset();
      if (isAggregate(db, item)) {
        isSplitted = true;
        var post = item.splitForAggregation(result, ctx);
        var postAlias = item.getProjectionAlias();
        postAlias = new SQLIdentifier(postAlias, true);
        post.setAlias(postAlias);
        postAggregate.getItems().add(post);
        aggregate.getItems().addAll(result.getAggregate());
        preAggregate.getItems().addAll(result.getPreAggregate());
      } else {
        preAggregate.getItems().add(item);
        // also push the alias forward in the chain
        var aggItem = new SQLProjectionItem(-1);
        aggItem.setExpression(new SQLExpression(item.getProjectionAlias()));
        aggregate.getItems().add(aggItem);
        postAggregate.getItems().add(aggItem);
      }
    }

    // bind split projections to the execution planner
    if (isSplitted) {
      info.preAggregateProjection = preAggregate;
      if (info.preAggregateProjection.getItems() == null
          || info.preAggregateProjection.getItems().isEmpty()) {
        info.preAggregateProjection = null;
      }
      info.aggregateProjection = aggregate;
      if (info.aggregateProjection.getItems() == null
          || info.aggregateProjection.getItems().isEmpty()) {
        info.aggregateProjection = null;
      }
      info.projection = postAggregate;

      addGroupByExpressionsToProjections(db, info);
    }
  }

  private static boolean isAggregate(DatabaseSessionEmbedded session, SQLProjectionItem item) {
    return item.isAggregate(session);
  }

  private static SQLProjectionItem projectionFromAlias(SQLIdentifier oIdentifier) {
    var result = new SQLProjectionItem(-1);
    result.setExpression(new SQLExpression(oIdentifier));
    return result;
  }

  /**
   * if GROUP BY is performed on an expression that is not explicitly in the pre-aggregate
   * projections, then that expression has to be put in the pre-aggregate (only here, in subsequent
   * steps it's removed)
   */
  private static void addGroupByExpressionsToProjections(DatabaseSessionEmbedded session,
      QueryPlanningInfo info) {
    if (info.groupBy == null
        || info.groupBy.getItems() == null
        || info.groupBy.getItems().isEmpty()) {
      return;
    }
    var newGroupBy = new SQLGroupBy(-1);
    var i = 0;
    for (var exp : info.groupBy.getItems()) {
      if (exp.isAggregate(session)) {
        throw new CommandExecutionException(session, "Cannot group by an aggregate function");
      }
      var found = false;
      if (info.preAggregateProjection != null) {
        for (var alias : info.preAggregateProjection.getAllAliases()) {
          // if it's a simple identifier and it's the same as one of the projections in the query,
          // then the projection itself is used for GROUP BY without recalculating; in all the other
          // cases, it is evaluated separately
          if (alias.equals(exp.getDefaultAlias().getStringValue()) && exp.isBaseIdentifier()) {
            found = true;
            newGroupBy.getItems().add(exp);
            break;
          }
        }
      }
      if (!found) {
        var newItem = new SQLProjectionItem(-1);
        newItem.setExpression(exp);
        var groupByAlias = new SQLIdentifier("_$$$GROUP_BY_ALIAS$$$_" + i++);
        newItem.setAlias(groupByAlias);
        if (info.preAggregateProjection == null) {
          info.preAggregateProjection = new SQLProjection(-1);
        }
        if (info.preAggregateProjection.getItems() == null) {
          info.preAggregateProjection.setItems(new ArrayList<>());
        }
        info.preAggregateProjection.getItems().add(newItem);
        newGroupBy.getItems().add(new SQLExpression(groupByAlias));
      }

      info.groupBy = newGroupBy;
    }
  }

  /**
   * translates subqueries to LET statements
   */
  private static void extractSubQueries(QueryPlanningInfo info) {
    var collector = new SubQueryCollector();
    if (info.perRecordLetClause != null) {
      info.perRecordLetClause.extractSubQueries(collector);
    }
    var i = 0;
    var j = 0;
    for (var entry : collector.getSubQueries().entrySet()) {
      var alias = entry.getKey();
      var query = entry.getValue();
      if (query.refersToParent()) {
        addRecordLevelLet(info, alias, query, j++);
      } else {
        addGlobalLet(info, alias, query, i++);
      }
    }
    collector.reset();

    if (info.whereClause != null) {
      info.whereClause.extractSubQueries(collector);
    }
    if (info.projection != null) {
      info.projection.extractSubQueries(collector);
    }
    if (info.orderBy != null) {
      info.orderBy.extractSubQueries(collector);
    }
    if (info.groupBy != null) {
      info.groupBy.extractSubQueries(collector);
    }

    for (var entry : collector.getSubQueries().entrySet()) {
      var alias = entry.getKey();
      var query = entry.getValue();
      if (query.refersToParent()) {
        addRecordLevelLet(info, alias, query);
      } else {
        addGlobalLet(info, alias, query);
      }
    }
  }

  private static void addGlobalLet(QueryPlanningInfo info, SQLIdentifier alias, SQLExpression exp) {
    if (info.globalLetClause == null) {
      info.globalLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setExpression(exp);
    info.globalLetClause.addItem(item);
  }

  private static void addGlobalLet(QueryPlanningInfo info, SQLIdentifier alias, SQLStatement stm) {
    if (info.globalLetClause == null) {
      info.globalLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setQuery(stm);
    info.globalLetClause.addItem(item);
  }

  private static void addGlobalLet(
      QueryPlanningInfo info, SQLIdentifier alias, SQLStatement stm, int pos) {
    if (info.globalLetClause == null) {
      info.globalLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setQuery(stm);
    info.globalLetClause.getItems().add(pos, item);
  }

  private static void addRecordLevelLet(QueryPlanningInfo info, SQLIdentifier alias,
      SQLStatement stm) {
    if (info.perRecordLetClause == null) {
      info.perRecordLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setQuery(stm);
    info.perRecordLetClause.addItem(item);
  }

  private static void addRecordLevelLet(
      QueryPlanningInfo info, SQLIdentifier alias, SQLStatement stm, int pos) {
    if (info.perRecordLetClause == null) {
      info.perRecordLetClause = new SQLLetClause(-1);
    }
    var item = new SQLLetItem(-1);
    item.setVarName(alias);
    item.setQuery(stm);
    info.perRecordLetClause.getItems().add(pos, item);
  }

  private void handleFetchFromTarget(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {

    var target = info.target == null ? null : info.target.getItem();
    if (target == null) {
      handleNoTarget(result, ctx, profilingEnabled);
    } else if (target.getIdentifier() != null) {
      var className = target.getIdentifier().getStringValue();
      if (!className.isEmpty() && className.charAt(0) == '$'
          && !ctx.getDatabaseSession()
          .getMetadata()
          .getImmutableSchemaSnapshot()
          .existsClass(className)) {
        handleVariableAsTarget(result, info, ctx, profilingEnabled);
      } else {
        var ridRangeConditions = extractRidRanges(info.flattenedWhereClause, ctx);
        if (!ridRangeConditions.isEmpty()) {
          info.ridRangeConditions = ridRangeConditions;
        }

        handleClassAsTarget(result, info, ctx, profilingEnabled);
      }
    } else if (target.getStatement() != null) {
      handleSubqueryAsTarget(
          result, target.getStatement(), ctx, profilingEnabled);
    } else if (target.getFunctionCall() != null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "function call as target is not supported yet");
    } else if (target.getInputParam() != null) {
      handleInputParamAsTarget(
          result,
          info,
          target.getInputParam(),
          ctx,
          profilingEnabled);
    } else if (target.getInputParams() != null && !target.getInputParams().isEmpty()) {
      List<InternalExecutionPlan> plans = new ArrayList<>();
      for (var param : target.getInputParams()) {
        var subPlan = new SelectExecutionPlan(ctx);
        handleInputParamAsTarget(
            subPlan,
            info,
            param,
            ctx,
            profilingEnabled);
        plans.add(subPlan);
      }
      result.chain(new ParallelExecStep(plans, ctx, profilingEnabled));
    } else if (target.getMetadata() != null) {
      handleMetadataAsTarget(result, target.getMetadata(), ctx, profilingEnabled);
    } else if (target.getRids() != null && !target.getRids().isEmpty()) {
      handleRidsAsTarget(result, target.getRids(), ctx, profilingEnabled);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  private static void handleVariableAsTarget(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    final var targetItem = info.target.getItem();
    if (targetItem.getModifier() != null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Modifiers cannot be used with variables: " + targetItem);
    }
    plan.chain(
        new FetchFromVariableStep(
            targetItem.getIdentifier().getStringValue(), ctx, profilingEnabled));
  }

  private static SQLAndBlock extractRidRanges(List<SQLAndBlock> flattenedWhereClause,
      CommandContext ctx) {
    var result = new SQLAndBlock(-1);

    if (flattenedWhereClause == null || flattenedWhereClause.size() != 1) {
      return result;
    }

    for (var booleanExpression : flattenedWhereClause.getFirst().getSubBlocks()) {
      if (isRidRange(booleanExpression, ctx)) {
        result.getSubBlocks().add(booleanExpression.copy());
      }
    }

    return result;
  }

  private static boolean isRidRange(SQLBooleanExpression booleanExpression, CommandContext ctx) {
    if (booleanExpression instanceof SQLBinaryCondition cond) {
      var operator = cond.getOperator();
      if (operator.isRangeOperator() && cond.getLeft().toString().equalsIgnoreCase("@rid")) {
        Object obj;
        if (cond.getRight().getRid() != null) {
          obj = cond.getRight().getRid().toRecordId((Result) null, ctx);
        } else {
          obj = cond.getRight().execute((Result) null, ctx);
        }
        return obj instanceof Identifiable;
      }
    }
    return false;
  }

  private void handleInputParamAsTarget(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      SQLInputParameter inputParam,
      CommandContext ctx,
      boolean profilingEnabled) {
    var session = ctx.getDatabaseSession();
    var paramValue = inputParam.getValue(ctx.getInputParameters());
    switch (paramValue) {
      case null -> result.chain(new EmptyStep(ctx, profilingEnabled)); // nothing to return
      case SchemaClass schemaClass -> {
        var from = new SQLFromClause(-1);
        var item = new SQLFromItem(-1);
        from.setItem(item);
        item.setIdentifier(new SQLIdentifier(schemaClass.getName()));
        handleClassAsTarget(result, from, info, ctx, profilingEnabled);
      }
      case String s -> {
        // strings are treated as classes
        var from = new SQLFromClause(-1);
        var item = new SQLFromItem(-1);
        from.setItem(item);
        item.setIdentifier(new SQLIdentifier(s));
        handleClassAsTarget(result, from, info, ctx, profilingEnabled);
      }
      case Identifiable identifiable -> {
        var orid = identifiable.getIdentity();

        var rid = new SQLRid(-1);
        var collection = new SQLInteger(-1);
        collection.setValue(orid.getCollectionId());
        var position = new SQLInteger(-1);
        position.setValue(orid.getCollectionPosition());
        rid.setLegacy(true);
        rid.setCollection(collection);
        rid.setPosition(position);
        handleRidsAsTarget(result, Collections.singletonList(rid), ctx, profilingEnabled);
      }
      case Iterable<?> iterable -> {
        // try list of RIDs
        List<SQLRid> rids = new ArrayList<>();
        for (var x : iterable) {
          if (!(x instanceof Identifiable id)) {
            throw new CommandExecutionException(session,
                "Cannot use colleciton as target: " + paramValue);
          }
          var orid = id.getIdentity();

          var rid = new SQLRid(-1);
          var collection = new SQLInteger(-1);
          collection.setValue(orid.getCollectionId());
          var position = new SQLInteger(-1);
          position.setValue(orid.getCollectionPosition());
          rid.setCollection(collection);
          rid.setPosition(position);
          rids.add(rid);
        }
        if (!rids.isEmpty()) {
          handleRidsAsTarget(result, rids, ctx, profilingEnabled);
        } else {
          result.chain(new EmptyStep(ctx, profilingEnabled)); // nothing to return
        }
      }
      default -> throw new CommandExecutionException(session, "Invalid target: " + paramValue);
    }
  }

  private static void handleNoTarget(
      SelectExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {
    result.chain(new EmptyDataGeneratorStep(1, ctx, profilingEnabled));
  }


  private static void handleMetadataAsTarget(
      SelectExecutionPlan plan,
      SQLMetadataIdentifier metadata,
      CommandContext ctx,
      boolean profilingEnabled) {
    var db = ctx.getDatabaseSession();
    String schemaRecordIdAsString;
    if (metadata.getName().equalsIgnoreCase("SCHEMA")) {
      schemaRecordIdAsString = db.getStorage().getSchemaRecordId();
      var schemaRid = RecordIdInternal.fromString(schemaRecordIdAsString, false);
      plan.chain(new FetchFromRidsStep(Collections.singleton(schemaRid), ctx, profilingEnabled));
    } else if (metadata.getName().equalsIgnoreCase("INDEXES")) {
      plan.chain(new FetchFromIndexManagerStep(ctx, profilingEnabled));
    } else if (metadata.getName().equalsIgnoreCase("STORAGE")) {
      plan.chain(new FetchFromStorageMetadataStep(ctx, profilingEnabled));
    } else if (metadata.getName().equalsIgnoreCase("DATABASE")) {
      plan.chain(new FetchFromDatabaseMetadataStep(ctx, profilingEnabled));
    } else {
      throw new UnsupportedOperationException("Invalid metadata: " + metadata.getName());
    }
  }

  private static void handleRidsAsTarget(
      SelectExecutionPlan plan, List<SQLRid> rids, CommandContext ctx, boolean profilingEnabled) {
    List<RecordIdInternal> actualRids = new ArrayList<>();
    for (var rid : rids) {
      actualRids.add(rid.toRecordId((Result) null, ctx));
    }
    plan.chain(new FetchFromRidsStep(actualRids, ctx, profilingEnabled));
  }

  private static void handleExpand(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.expand) {
      result.chain(new ExpandStep(ctx, profilingEnabled, info.expandAlias));
    }
  }

  private void handleGlobalLet(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.globalLetClause != null) {
      var items = info.globalLetClause.getItems();
      items = sortLet(items, this.statement.getLetClause());
      List<String> scriptVars = new ArrayList<>();
      for (var item : items) {
        if (item.getExpression() != null) {
          result.chain(
              new GlobalLetExpressionStep(
                  item.getVarName(), item.getExpression(), ctx, profilingEnabled));
        } else {
          result.chain(
              new GlobalLetQueryStep(
                  item.getVarName(), item.getQuery(), ctx, profilingEnabled, scriptVars));
        }
        scriptVars.add(item.getVarName().getStringValue());
        info.globalLetPresent = true;
      }
    }
  }

  private void handleLet(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    // this could be invoked multiple times
    // so it can be optimized
    // checking whether the execution plan already contains some LET steps
    // and in case skip
    if (info.perRecordLetClause != null) {
      var items = info.perRecordLetClause.getItems();
      items = sortLet(items, this.statement.getLetClause());

      for (var item : items) {
        if (item.getExpression() != null) {
          plan.chain(
              new LetExpressionStep(
                  item.getVarName(), item.getExpression(), ctx, profilingEnabled));
        } else {
          plan.chain(new LetQueryStep(item.getVarName(), item.getQuery(), ctx, profilingEnabled));
        }
      }
    }
  }

  private static List<SQLLetItem> sortLet(List<SQLLetItem> items, SQLLetClause letClause) {
    if (letClause == null) {
      return items;
    }
    List<SQLLetItem> i = new ArrayList<>(items);
    var result = new ArrayList<SQLLetItem>();
    for (var item : letClause.getItems()) {
      var var = item.getVarName().getStringValue();
      var iterator = i.iterator();
      while (iterator.hasNext()) {
        var x = iterator.next();
        if (x.getVarName().getStringValue().equals(var)) {
          iterator.remove();
          result.add(x);
          break;
        }
      }
    }
    result.addAll(i);
    return result;
  }

  private void handleWhere(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (info.whereClause != null) {
      plan.chain(
          new FilterStep(
              info.whereClause,
              ctx,
              this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
              profilingEnabled));
    }
  }

  public static void handleOrderBy(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var session = ctx.getDatabaseSession();
    var skipSize = info.skip == null ? 0 : info.skip.getValue(ctx);
    if (skipSize < 0) {
      throw new CommandExecutionException(session, "Cannot execute a query with a negative SKIP");
    }
    var limitSize = info.limit == null ? -1 : info.limit.getValue(ctx);
    Integer maxResults = null;
    if (limitSize >= 0) {
      maxResults = skipSize + limitSize;
    }
    if (info.expand || info.unwind != null) {
      maxResults = null;
    }

    if (!info.orderApplied
        && info.orderBy != null
        && info.orderBy.getItems() != null
        && !info.orderBy.getItems().isEmpty()) {

      if (info.target != null) {
        var targetClass = info.target.getSchemaClass(session);
        if (targetClass != null) {
          info.orderBy
              .getItems()
              .forEach(
                  item -> {
                    var possibleEdgeProperty =
                        targetClass.getProperty("out_" + item.getAlias());
                    if (possibleEdgeProperty != null
                        && possibleEdgeProperty.getType() == PropertyType.LINKBAG) {
                      item.setEdge(true);
                    }
                  });
        }
      }
      plan.chain(
          new OrderByStep(
              info.orderBy,
              maxResults,
              ctx,
              info.timeout != null ? info.timeout.getVal().longValue() : -1,
              profilingEnabled));
      if (info.projectionAfterOrderBy != null) {
        plan.chain(
            new ProjectionCalculationStep(info.projectionAfterOrderBy, ctx, profilingEnabled));
      }
    }
  }

  /**
   * @param plan the execution plan where to add the fetch step
   */
  private void handleClassAsTarget(
      SelectExecutionPlan plan,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    handleClassAsTarget(plan, info.target, info, ctx, profilingEnabled);
  }

  private void handleClassAsTarget(
      SelectExecutionPlan plan,
      SQLFromClause from,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var identifier = from.getItem().getIdentifier();
    if (handleClassAsTargetWithIndexedFunction(
        plan, identifier, info, ctx, profilingEnabled)) {
      plan.chain(new FilterByClassStep(identifier, ctx, profilingEnabled));
      return;
    }

    if (handleClassAsTargetWithIndex(
        plan, identifier, info, ctx, profilingEnabled)) {
      plan.chain(new FilterByClassStep(identifier, ctx, profilingEnabled));
      return;
    }

    if (info.orderBy != null
        && handleClassWithIndexForSortOnly(
        plan, identifier, info, ctx, profilingEnabled)) {
      plan.chain(new FilterByClassStep(identifier, ctx, profilingEnabled));
      return;
    }

    Boolean orderByRidAsc = null; // null: no order. true: asc, false:desc
    if (isOrderByRidAsc(info)) {
      orderByRidAsc = true;
    } else if (isOrderByRidDesc(info)) {
      orderByRidAsc = false;
    }
    var className = identifier.getStringValue();
    Schema schema = getSchemaFromContext(ctx);

    AbstractExecutionStep fetcher;
    if (schema.getClass(className) != null) {
      fetcher =
          new FetchFromClassExecutionStep(
              className, null, info, ctx, orderByRidAsc, profilingEnabled);
    } else {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class or View not present in the schema: " + className);
    }

    if (orderByRidAsc != null) {
      info.orderApplied = true;
    }
    plan.chain(fetcher);
  }

  private static IntArrayList classCollectionsFiltered(
      DatabaseSessionEmbedded db, SchemaClass clazz, Set<String> filterCollections) {
    var ids = clazz.getPolymorphicCollectionIds();
    var filtered = new IntArrayList();
    for (var id : ids) {
      if (filterCollections.contains(db.getCollectionNameById(id))) {
        filtered.add(id);
      }
    }
    return filtered;
  }

  private boolean handleClassAsTargetWithIndexedFunction(
      SelectExecutionPlan plan,
      SQLIdentifier queryTarget,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (queryTarget == null) {
      return false;
    }
    var schema = getSchemaFromContext(ctx);
    var clazz = schema.getClassInternal(queryTarget.getStringValue());
    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class not found: " + queryTarget);
    }
    if (info.flattenedWhereClause == null || info.flattenedWhereClause.isEmpty()) {
      return false;
    }

    List<InternalExecutionPlan> resultSubPlans = new ArrayList<>();

    var indexedFunctionsFound = false;

    for (var block : info.flattenedWhereClause) {
      var indexedFunctionConditions =
          block.getIndexedFunctionConditions(clazz, ctx.getDatabaseSession());

      indexedFunctionConditions =
          filterIndexedFunctionsWithoutIndex(indexedFunctionConditions, info.target, ctx);

      if (indexedFunctionConditions == null || indexedFunctionConditions.isEmpty()) {
        var bestIndex = findBestIndexFor(ctx,
            clazz.getIndexesInternal(),
            block, clazz);
        if (bestIndex != null) {

          var step = new FetchFromIndexStep(bestIndex, true, ctx, profilingEnabled);

          var subPlan = new SelectExecutionPlan(ctx);
          subPlan.chain(step);
          IntArrayList filterCollectionIds;

          filterCollectionIds = IntArrayList.of(clazz.getPolymorphicCollectionIds());
          subPlan.chain(new GetValueFromIndexEntryStep(ctx, filterCollectionIds, profilingEnabled));
          if (bestIndex.requiresDistinctStep()) {
            subPlan.chain(new DistinctExecutionStep(ctx, profilingEnabled));
          }
          if (!block.getSubBlocks().isEmpty()) {
            if ((info.perRecordLetClause != null && refersToLet(block.getSubBlocks()))) {
              handleLet(subPlan, info, ctx, profilingEnabled);
            }
            subPlan.chain(
                new FilterStep(
                    createWhereFrom(block),
                    ctx,
                    this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                    profilingEnabled));
          }
          resultSubPlans.add(subPlan);
        } else {
          FetchFromClassExecutionStep step;
          step =
              new FetchFromClassExecutionStep(
                  clazz.getName(), null, ctx, true, profilingEnabled);

          var subPlan = new SelectExecutionPlan(ctx);
          subPlan.chain(step);
          if (!block.getSubBlocks().isEmpty()) {
            if ((info.perRecordLetClause != null && refersToLet(block.getSubBlocks()))) {
              handleLet(subPlan, info, ctx, profilingEnabled);
            }
            subPlan.chain(
                new FilterStep(
                    createWhereFrom(block),
                    ctx,
                    this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                    profilingEnabled));
          }
          resultSubPlans.add(subPlan);
        }
      } else {
        SQLBinaryCondition blockCandidateFunction = null;
        for (var cond : indexedFunctionConditions) {
          if (!cond.allowsIndexedFunctionExecutionOnTarget(info.target, ctx)) {
            if (!cond.canExecuteIndexedFunctionWithoutIndex(info.target, ctx)) {
              throw new CommandExecutionException(ctx.getDatabaseSession(),
                  "Cannot execute " + block + " on " + queryTarget);
            }
          }
          if (blockCandidateFunction == null) {
            blockCandidateFunction = cond;
          } else {
            var thisAllowsNoIndex =
                cond.canExecuteIndexedFunctionWithoutIndex(info.target, ctx);
            var prevAllowsNoIndex =
                blockCandidateFunction.canExecuteIndexedFunctionWithoutIndex(info.target, ctx);
            if (!thisAllowsNoIndex && !prevAllowsNoIndex) {
              // none of the functions allow execution without index, so cannot choose one
              throw new CommandExecutionException(ctx.getDatabaseSession(),
                  "Cannot choose indexed function between "
                      + cond
                      + " and "
                      + blockCandidateFunction
                      + ". Both require indexed execution");
            } else if (thisAllowsNoIndex && prevAllowsNoIndex) {
              // both can be calculated without index, choose the best one for index execution
              var thisEstimate = cond.estimateIndexed(info.target, ctx);
              var lastEstimate = blockCandidateFunction.estimateIndexed(info.target, ctx);
              if (thisEstimate > -1 && thisEstimate < lastEstimate) {
                blockCandidateFunction = cond;
              }
            } else if (prevAllowsNoIndex) {
              // choose current condition, because the other one can be calculated without index
              blockCandidateFunction = cond;
            }
          }
        }

        var step =
            new FetchFromIndexedFunctionStep(
                blockCandidateFunction, info.target, ctx, profilingEnabled);
        if (!blockCandidateFunction.executeIndexedFunctionAfterIndexSearch(info.target, ctx)) {
          block = block.copy();
          block.getSubBlocks().remove(blockCandidateFunction);
        }
        if (info.flattenedWhereClause.size() == 1) {
          plan.chain(step);
          if (!block.getSubBlocks().isEmpty()) {
            if ((info.perRecordLetClause != null && refersToLet(block.getSubBlocks()))) {
              handleLet(plan, info, ctx, profilingEnabled);
            }
            plan.chain(
                new FilterStep(
                    createWhereFrom(block),
                    ctx,
                    this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                    profilingEnabled));
          }
        } else {
          var subPlan = new SelectExecutionPlan(ctx);
          subPlan.chain(step);
          if (!block.getSubBlocks().isEmpty()) {
            subPlan.chain(
                new FilterStep(
                    createWhereFrom(block),
                    ctx,
                    this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                    profilingEnabled));
          }
          resultSubPlans.add(subPlan);
        }
        indexedFunctionsFound = true;
      }
    }

    if (indexedFunctionsFound) {
      if (resultSubPlans.size()
          > 1) { // if resultSubPlans.size() == 1 the step was already chained (see above)
        plan.chain(new ParallelExecStep(resultSubPlans, ctx, profilingEnabled));
        plan.chain(new DistinctExecutionStep(ctx, profilingEnabled));
      }
      // WHERE condition already applied
      info.whereClause = null;
      info.flattenedWhereClause = null;
      return true;
    } else {
      return false;
    }
  }

  private static boolean refersToLet(List<SQLBooleanExpression> subBlocks) {
    if (subBlocks == null) {
      return false;
    }
    for (var exp : subBlocks) {
      if (!exp.toString().isEmpty() && exp.toString().charAt(0) == '$') {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static List<SQLBinaryCondition> filterIndexedFunctionsWithoutIndex(
      List<SQLBinaryCondition> indexedFunctionConditions,
      SQLFromClause fromClause,
      CommandContext ctx) {
    if (indexedFunctionConditions == null) {
      return null;
    }
    List<SQLBinaryCondition> result = new ArrayList<>();
    for (var cond : indexedFunctionConditions) {
      if (cond.allowsIndexedFunctionExecutionOnTarget(fromClause, ctx)) {
        result.add(cond);
      } else if (!cond.canExecuteIndexedFunctionWithoutIndex(fromClause, ctx)) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Cannot evaluate " + cond + ": no index defined");
      }
    }
    return result;
  }

  /**
   * tries to use an index for sorting only. Also adds the fetch step to the execution plan
   *
   * @param plan current execution plan
   * @param info the query planning information
   * @param ctx  the current context
   * @return true if it succeeded to use an index to sort, false otherwise.
   */
  private boolean handleClassWithIndexForSortOnly(
      SelectExecutionPlan plan,
      SQLIdentifier queryTarget,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var schema = getSchemaFromContext(ctx);
    var clazz = schema.getClassInternal(queryTarget.getStringValue());
    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class not found: " + queryTarget);
    }

    for (var idx :
        clazz.getIndexesInternal().stream()
            .filter(i -> i.getDefinition() != null)
            .toList()) {
      var indexFields = idx.getDefinition().getProperties();
      if (indexFields.size() < info.orderBy.getItems().size()) {
        continue;
      }
      var indexFound = true;
      String orderType = null;
      for (var i = 0; i < info.orderBy.getItems().size(); i++) {
        var orderItem = info.orderBy.getItems().get(i);
        if (orderItem.getCollate() != null) {
          return false;
        }
        var indexField = indexFields.get(i);
        if (i == 0) {
          orderType = orderItem.getType();
        } else {
          if (orderType == null || !orderType.equals(orderItem.getType())) {
            indexFound = false;
            break; // ASC/DESC interleaved, cannot be used with index.
          }
        }
        if (!(indexField.equals(orderItem.getAlias())
            || isInOriginalProjection(indexField, orderItem.getAlias()))) {
          indexFound = false;
          break;
        }
      }
      if (indexFound && orderType != null) {
        plan.chain(
            new FetchFromIndexValuesStep(
                new IndexSearchDescriptor(idx),
                orderType.equals(SQLOrderByItem.ASC),
                ctx,
                profilingEnabled));
        IntArrayList filterCollectionIds;
        filterCollectionIds = IntArrayList.of(clazz.getPolymorphicCollectionIds());
        plan.chain(new GetValueFromIndexEntryStep(ctx, filterCollectionIds, profilingEnabled));
        info.orderApplied = true;
        return true;
      }
    }
    return false;
  }

  private boolean isInOriginalProjection(String indexField, String alias) {
    if (info.projection == null) {
      return false;
    }
    if (info.projection.getItems() == null) {
      return false;
    }
    return info.projection.getItems().stream()
        .filter(proj -> proj.getExpression().toString().equals(indexField))
        .filter(proj -> proj.getAlias() != null)
        .anyMatch(proj -> proj.getAlias().getStringValue().equals(alias));
  }

  private boolean handleClassAsTargetWithIndex(
      SelectExecutionPlan plan,
      SQLIdentifier targetClass,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {

    var result =
        handleClassAsTargetWithIndex(
            targetClass.getStringValue(), null, info, ctx,
            profilingEnabled, true);

    if (result != null) {
      result.forEach(plan::chain);
      info.whereClause = null;
      info.flattenedWhereClause = null;
      return true;
    }

    var schema = getSchemaFromContext(ctx);
    var clazz = schema.getClassInternal(targetClass.getStringValue());

    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class not found: " + targetClass);
    }

    var session = ctx.getDatabaseSession();
    if (clazz.approximateCount(session, false) != 0 || clazz.getSubclasses().isEmpty()
        || isDiamondHierarchy(clazz)) {
      return false;
    }
    // try subclasses

    var subclasses = clazz.getSubclasses();

    List<InternalExecutionPlan> subclassPlans = new ArrayList<>();
    for (var subClass : subclasses) {
      var subSteps =
          handleClassAsTargetWithIndexRecursive(
              subClass.getName(), null, info, ctx, profilingEnabled);
      if (subSteps == null || subSteps.isEmpty()) {
        return false;
      }
      var subPlan = new SelectExecutionPlan(ctx);
      subSteps.forEach(subPlan::chain);
      subclassPlans.add(subPlan);
    }
    if (!subclassPlans.isEmpty()) {
      plan.chain(new ParallelExecStep(subclassPlans, ctx, profilingEnabled));
      return true;
    }
    return false;
  }

  /**
   * checks if a class is the top of a diamond hierarchy
   */
  private static boolean isDiamondHierarchy(SchemaClass clazz) {
    Set<SchemaClass> traversed = new HashSet<>();
    List<SchemaClass> stack = new ArrayList<>();
    stack.add(clazz);
    while (!stack.isEmpty()) {
      var current = stack.removeFirst();
      traversed.add(current);
      for (var sub : current.getSubclasses()) {
        if (traversed.contains(sub)) {
          return true;
        }
        stack.add(sub);
        traversed.add(sub);
      }
    }
    return false;
  }

  @Nullable
  private List<ExecutionStepInternal> handleClassAsTargetWithIndexRecursive(
      String targetClass,
      Set<String> filterCollections,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    var result =
        handleClassAsTargetWithIndex(targetClass, filterCollections, info, ctx, profilingEnabled,
            false);
    var session = ctx.getDatabaseSession();
    if (result == null) {
      result = new ArrayList<>();
      var clazz = getSchemaFromContext(ctx).getClassInternal(targetClass);
      if (clazz == null) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Cannot find class " + targetClass);
      }
      if (clazz.approximateCount(session, false) != 0
          || clazz.getSubclasses().isEmpty()
          || isDiamondHierarchy(clazz)) {
        return null;
      }

      var subclasses = clazz.getSubclasses();

      List<InternalExecutionPlan> subclassPlans = new ArrayList<>();
      for (var subClass : subclasses) {
        var subSteps =
            handleClassAsTargetWithIndexRecursive(
                subClass.getName(), filterCollections, info, ctx, profilingEnabled);
        if (subSteps == null || subSteps.isEmpty()) {
          return null;
        }
        var subPlan = new SelectExecutionPlan(ctx);
        subSteps.forEach(subPlan::chain);
        subclassPlans.add(subPlan);
      }
      if (!subclassPlans.isEmpty()) {
        result.add(new ParallelExecStep(subclassPlans, ctx, profilingEnabled));
      }
    }
    return result.isEmpty() ? null : result;
  }

  @Nullable
  private List<ExecutionStepInternal> handleClassAsTargetWithIndex(
      String targetClass,
      Set<String> filterCollections,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled,
      boolean isHierarchyRoot
  ) {
    if (info.flattenedWhereClause == null || info.flattenedWhereClause.isEmpty()) {
      return null;
    }

    var clazz = getSchemaFromContext(ctx).getClassInternal(targetClass);
    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot find class " + targetClass);
    }

    var indexes = clazz.getIndexesInternal();

    final SchemaClass c = clazz;
    var indexSearchDescriptors =
        info.flattenedWhereClause.stream()
            .map(x -> findBestIndexFor(ctx, indexes, x, c))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    if (indexSearchDescriptors.size() != info.flattenedWhereClause.size()) {
      return null; // some blocks could not be managed with an index
    }

    var optimumIndexSearchDescriptors =
        commonFactor(indexSearchDescriptors);

    return executionStepFromIndexes(
        filterCollections,
        clazz,
        info,
        ctx,
        profilingEnabled,
        optimumIndexSearchDescriptors,
        isHierarchyRoot
    );
  }

  private List<ExecutionStepInternal> executionStepFromIndexes(
      Set<String> filterCollections,
      SchemaClass clazz,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled,
      List<IndexSearchDescriptor> optimumIndexSearchDescriptors,
      boolean isHierarchyRoot
  ) {
    List<ExecutionStepInternal> result;
    if (optimumIndexSearchDescriptors.size() == 1) {
      var desc = optimumIndexSearchDescriptors.getFirst();
      result = new ArrayList<>();
      var orderAsc = getOrderDirection(info);
      result.add(
          new FetchFromIndexStep(desc, !Boolean.FALSE.equals(orderAsc), ctx, profilingEnabled));
      IntArrayList filterCollectionIds;
      if (filterCollections != null) {
        filterCollectionIds = classCollectionsFiltered(ctx.getDatabaseSession(), clazz,
            filterCollections);
      } else {
        filterCollectionIds = IntArrayList.of(clazz.getPolymorphicCollectionIds());
      }
      result.add(new GetValueFromIndexEntryStep(ctx, filterCollectionIds, profilingEnabled));
      if (desc.requiresDistinctStep()) {
        result.add(new DistinctExecutionStep(ctx, profilingEnabled));
      }
      // at the moment, we allow this optimization only for root classes in the hierarchy.
      // I.e. For B and C that are subclasses of A, `select from A where aField > 10` will
      // apply this optimization only if `aField` is indexed in the root class A.
      if (isHierarchyRoot
          && orderAsc != null
          && info.orderBy != null
          && fullySorted(info.orderBy, desc)) {
        info.orderApplied = true;
      }
      if (desc.getRemainingCondition() != null && !desc.getRemainingCondition().isEmpty()) {
        if ((info.perRecordLetClause != null
            && refersToLet(Collections.singletonList(desc.getRemainingCondition())))) {
          var stubPlan = new SelectExecutionPlan(ctx);
          handleLet(stubPlan, info, ctx, profilingEnabled);
          for (var step : stubPlan.getSteps()) {
            result.add((ExecutionStepInternal) step);
          }
        }
        result.add(
            new FilterStep(
                createWhereFrom(desc.getRemainingCondition()),
                ctx,
                this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                profilingEnabled));
      }
    } else {
      result = new ArrayList<>();
      result.add(
          createParallelIndexFetch(
              optimumIndexSearchDescriptors, filterCollections, ctx, profilingEnabled));
      if (optimumIndexSearchDescriptors.size() > 1) {
        result.add(new DistinctExecutionStep(ctx, profilingEnabled));
      }
    }
    return result;
  }

  private static SchemaInternal getSchemaFromContext(CommandContext ctx) {
    return ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
  }

  private static boolean fullySorted(SQLOrderBy orderBy, IndexSearchDescriptor desc) {
    if (orderBy.ordersWithCollate() || !orderBy.ordersSameDirection()) {
      return false;
    }
    return desc.fullySorted(orderBy.getProperties());
  }

  /**
   * returns TRUE if all the order clauses are ASC, FALSE if all are DESC, null otherwise
   *
   * @return TRUE if all the order clauses are ASC, FALSE if all are DESC, null otherwise
   */
  @Nullable
  private static Boolean getOrderDirection(QueryPlanningInfo info) {
    if (info.orderBy == null) {
      return null;
    }
    String result = null;
    for (var item : info.orderBy.getItems()) {
      if (result == null) {
        result = item.getType() == null ? SQLOrderByItem.ASC : item.getType();
      } else {
        var newType = item.getType() == null ? SQLOrderByItem.ASC : item.getType();
        if (!newType.equals(result)) {
          return null;
        }
      }
    }
    return result == null || result.equals(SQLOrderByItem.ASC);
  }

  private ExecutionStepInternal createParallelIndexFetch(
      List<IndexSearchDescriptor> indexSearchDescriptors,
      Set<String> filterCollections,
      CommandContext ctx,
      boolean profilingEnabled) {
    List<InternalExecutionPlan> subPlans = new ArrayList<>();
    for (var desc : indexSearchDescriptors) {
      var subPlan = new SelectExecutionPlan(ctx);
      subPlan.chain(new FetchFromIndexStep(desc, true, ctx, profilingEnabled));
      IntArrayList filterCollectionIds = null;
      if (filterCollections != null) {
        filterCollectionIds = IntArrayList.of(
            ctx.getDatabaseSession().getCollectionsIds(filterCollections));
      }
      subPlan.chain(new GetValueFromIndexEntryStep(ctx, filterCollectionIds, profilingEnabled));
      if (desc.requiresDistinctStep()) {
        subPlan.chain(new DistinctExecutionStep(ctx, profilingEnabled));
      }
      if (desc.getRemainingCondition() != null && !desc.getRemainingCondition().isEmpty()) {
        subPlan.chain(
            new FilterStep(
                createWhereFrom(desc.getRemainingCondition()),
                ctx,
                this.info.timeout != null ? this.info.timeout.getVal().longValue() : -1,
                profilingEnabled));
      }
      subPlans.add(subPlan);
    }
    return new ParallelExecStep(subPlans, ctx, profilingEnabled);
  }

  private static SQLWhereClause createWhereFrom(SQLBooleanExpression remainingCondition) {
    var result = new SQLWhereClause(-1);
    result.setBaseExpression(remainingCondition);
    return result;
  }

  /**
   * given a flat AND block and a set of indexes, returns the best index to be used to process it,
   * with the complete description on how to use it
   */
  @Nullable
  private static IndexSearchDescriptor findBestIndexFor(
      CommandContext ctx, Set<Index> indexes, SQLAndBlock block, SchemaClass clazz) {
    // get all valid index descriptors
    var descriptors =
        indexes.stream()
            .filter(Index::canBeUsedInEqualityOperators)
            .map(index -> buildIndexSearchDescriptor(ctx, index, block, clazz))
            .filter(Objects::nonNull)
            .filter(x -> x.getKeyCondition() != null)
            .filter(x -> x.blockCount() > 0)
            .collect(Collectors.toList());

    var fullTextIndexDescriptors =
        indexes.stream()
            .filter(idx -> idx.getType().equalsIgnoreCase("FULLTEXT"))
            .filter(idx -> !idx.getAlgorithm().equalsIgnoreCase("LUCENE"))
            .map(idx -> buildIndexSearchDescriptorForFulltext(idx, block))
            .filter(Objects::nonNull)
            .filter(x -> x.getKeyCondition() != null)
            .filter(x -> x.blockCount() > 0)
            .toList();

    descriptors.addAll(fullTextIndexDescriptors);

    descriptors = removeGenericIndexes(descriptors, clazz);

    // remove the redundant descriptors (eg. if I have one on [a] and one on [a, b], the first one
    // is redundant, just discard it)
    descriptors = removePrefixIndexes(descriptors);

    // sort by cost
    var sortedDescriptors =
        descriptors.stream().map(x -> new PairIntegerObject<>(x.cost(ctx), x)).sorted().toList();

    // get only the descriptors with the lowest cost
    if (sortedDescriptors.isEmpty()) {
      descriptors = Collections.emptyList();
    } else {
      descriptors =
          sortedDescriptors.stream()
              .filter(x -> x.key == sortedDescriptors.getFirst().key)
              .map(x -> x.value)
              .collect(Collectors.toList());
    }

    // sort remaining by the number of indexed fields
    descriptors =
        descriptors.stream()
            .sorted(Comparator.comparingInt(IndexSearchDescriptor::blockCount))
            .collect(Collectors.toList());

    // get the one that has more indexed fields
    return descriptors.isEmpty() ? null : descriptors.getLast();
  }

  /**
   * If between the index candidates there are for the same property target class index and super
   * class index prefer the target class.
   */
  private static List<IndexSearchDescriptor> removeGenericIndexes(
      List<IndexSearchDescriptor> descriptors, SchemaClass clazz) {
    List<IndexSearchDescriptor> results = new ArrayList<>();
    for (var desc : descriptors) {
      IndexSearchDescriptor matching = null;
      for (var result : results) {
        if (desc.isSameCondition(result)) {
          matching = result;
          break;
        }
      }
      if (matching != null) {
        if (clazz.getName().equals(desc.getIndex().getDefinition().getClassName())) {
          results.remove(matching);
          results.add(desc);
        }
      } else {
        results.add(desc);
      }
    }
    return results;
  }

  private static List<IndexSearchDescriptor> removePrefixIndexes(
      List<IndexSearchDescriptor> descriptors) {
    List<IndexSearchDescriptor> result = new ArrayList<>();
    for (var desc : descriptors) {
      if (result.isEmpty()) {
        result.add(desc);
      } else {
        var prefixes = findPrefixes(desc, result);
        if (prefixes.isEmpty()) {
          if (!isPrefixOfAny(desc, result)) {
            result.add(desc);
          }
        } else {
          result.removeAll(prefixes);
          result.add(desc);
        }
      }
    }
    return result;
  }

  private static boolean isPrefixOfAny(IndexSearchDescriptor desc,
      List<IndexSearchDescriptor> result) {
    for (var item : result) {
      if (desc.isPrefixOf(item)) {
        return true;
      }
    }
    return false;
  }

  /**
   * finds prefix conditions for a given condition, eg. if the condition is on [a,b] and in the list
   * there is another condition on [a] or on [a,b], then that condition is returned.
   */
  private static List<IndexSearchDescriptor> findPrefixes(
      IndexSearchDescriptor desc, List<IndexSearchDescriptor> descriptors) {
    List<IndexSearchDescriptor> result = new ArrayList<>();
    for (var item : descriptors) {
      if (item.isPrefixOf(desc)) {
        result.add(item);
      }
    }
    return result;
  }

  /**
   * given an index and a flat AND block, returns a descriptor on how to process it with an index
   * (index, index key and additional filters to apply after index fetch
   */
  @Nullable
  private static IndexSearchDescriptor buildIndexSearchDescriptor(
      CommandContext ctx, Index index, SQLAndBlock block, SchemaClass clazz) {
    var indexProperties = index.getDefinition().getProperties();

    //copy as we will modify the list of expressions
    var blockCopy = block.copy();

    var indexKeyValue = new SQLAndBlock(-1);

    //used if we need to generate a range query instead of a point query and applied to an end range
    //interval of the key to search in index.
    SQLBinaryCondition additionalRangeCondition = null;

    var booleanExpressions = blockCopy.getSubBlocks();
    var propertyNameBooleanExpressionMap =
        new HashMap<String, List<SQLBooleanExpression>>(booleanExpressions.size());

    //group all boolean expressions by indexed property they test,
    // all SQL expressions should be already flattened at this moment
    // so will use only a single property in the expression
    for (var booleanExpression : booleanExpressions) {
      //skip expressions that do not use properties we will apply them later on post filtering
      var indexPropertyName = booleanExpression.getRelatedIndexPropertyName();
      if (indexPropertyName != null) {
        var list = propertyNameBooleanExpressionMap.computeIfAbsent(indexPropertyName,
            k -> new ArrayList<>());
        list.add(booleanExpression);
      }
    }

    //Flag is used to indicate the situation when applied expressions make usage of indexes
    //impossible to use.
    //One of the most typical is the usage of several range conditions as we can apply only
    //one.
    var invalidConditions = new boolean[1];
    //Range condition should always go after equality condition, so sort all expressions by
    //the type of operator they use.
    //Then we will merge all expressions for the same property name that have more than two
    //conditions as we can apply only single equals and single range condition for each property.
    propertyNameBooleanExpressionMap.forEach((indexPropertyName, expressions) -> {
      if (expressions.size() > 1) {
        //merge all tail expressions as we can support only one range condition per index
        //try to mere first condition with the rest of the conditions too
        var resultingExpressions = new ArrayList<SQLBooleanExpression>(2);

        var firstExpression = expressions.getFirst();
        var expressionToMerge = expressions.get(1);

        var mergedExpression = firstExpression.mergeUsingAnd(expressionToMerge, ctx);
        if (mergedExpression != null) {
          expressionToMerge = mergedExpression;
        } else {
          resultingExpressions.add(firstExpression);
        }

        if (expressions.size() > 2) {
          for (var i = 2; i < expressions.size(); i++) {
            var nextBlockToMerge = expressions.get(i);
            expressionToMerge = expressionToMerge.mergeUsingAnd(nextBlockToMerge, ctx);

            //unable to merge expressions
            if (expressionToMerge == null) {
              invalidConditions[0] = true;
              return;
            }
          }
        }

        resultingExpressions.add(expressionToMerge);

        expressions.clear();
        expressions.addAll(resultingExpressions);
      }
    });

    //there are more than two boolean expressions for the same property, skip the current index
    if (invalidConditions[0]) {
      return null;
    }

    for (var indexProperty : indexProperties) {
      var propertyExpressions = propertyNameBooleanExpressionMap.get(indexProperty);
      if (propertyExpressions == null) {
        break;
      }

      if (propertyExpressions.size() > 2) {
        break;
      }

      var info =
          new IndexSearchInfo(
              indexProperty,
              true,
              isMap(clazz, indexProperty),
              isIndexByKey(index, indexProperty),
              isIndexByValue(index, indexProperty),
              clazz, ctx);

      var firstPropertyExpression = propertyExpressions.getFirst();
      if (firstPropertyExpression.isRangeExpression()) {
        if (!info.allowsRangeQueries()) {
          break;
        }
      }

      if (propertyExpressions.size() == 2) {
        var secondPropertyExpression = propertyExpressions.get(1);
        if (secondPropertyExpression.isIndexAware(info, ctx)) {
          if (secondPropertyExpression.canCreateRangeWith(firstPropertyExpression)) {
            additionalRangeCondition = (SQLBinaryCondition) secondPropertyExpression;
          } else {
            break;
          }
        } else {
          return null;
        }
      }

      if (firstPropertyExpression.isIndexAware(info, ctx)) {
        indexKeyValue.getSubBlocks().add(firstPropertyExpression.copy());
        if (firstPropertyExpression.isRangeExpression()) {
          //we can have only a single range condition per index
          break;
        }
      } else {
        break;
      }
    }

    return new IndexSearchDescriptor(index, indexKeyValue, additionalRangeCondition, blockCopy);
  }

  /**
   * given a full text index and a flat AND block, returns a descriptor on how to process it with an
   * index (index, index key and additional filters to apply after index fetch
   */
  @Nullable
  private static IndexSearchDescriptor buildIndexSearchDescriptorForFulltext(
      Index index, SQLAndBlock block) {
    var indexFields = index.getDefinition().getProperties();
    var found = false;

    var blockCopy = block.copy();
    Iterator<SQLBooleanExpression> blockIterator;

    var indexKeyValue = new SQLAndBlock(-1);

    for (var indexField : indexFields) {
      blockIterator = blockCopy.getSubBlocks().iterator();
      var indexFieldFound = false;
      while (blockIterator.hasNext()) {
        var singleExp = blockIterator.next();
        if (singleExp.isFullTextIndexAware(indexField)) {
          found = true;
          indexFieldFound = true;
          indexKeyValue.getSubBlocks().add(singleExp.copy());
          blockIterator.remove();
          break;
        }
      }
      if (!indexFieldFound) {
        break;
      }
    }

    if (found) {
      return new IndexSearchDescriptor(index, indexKeyValue, null, blockCopy);
    }
    return null;
  }

  private static boolean isIndexByKey(Index index, String field) {
    var def = index.getDefinition();
    for (var o : def.getFieldsToIndex()) {
      if (o.equalsIgnoreCase(field + " by key")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isIndexByValue(Index index, String field) {
    var def = index.getDefinition();
    for (var o : def.getFieldsToIndex()) {
      if (o.equalsIgnoreCase(field + " by value")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMap(SchemaClass clazz,
      String indexField) {
    var prop = clazz.getProperty(indexField);
    if (prop == null) {
      return false;
    }
    return prop.getType() == PropertyType.EMBEDDEDMAP;
  }

  /**
   * aggregates multiple index conditions that refer to the same key search
   */
  private static List<IndexSearchDescriptor> commonFactor(
      List<IndexSearchDescriptor> indexSearchDescriptors) {
    // index, key condition, additional filter (to aggregate in OR)
    Map<Index, Map<IndexCondPair, SQLOrBlock>> aggregation = new HashMap<>();
    for (var item : indexSearchDescriptors) {
      var filtersForIndex = aggregation.computeIfAbsent(item.getIndex(), k -> new HashMap<>());
      var extendedCond =
          new IndexCondPair(item.getKeyCondition(), item.getAdditionalRangeCondition());

      var existingAdditionalConditions = filtersForIndex.get(extendedCond);
      if (existingAdditionalConditions == null) {
        existingAdditionalConditions = new SQLOrBlock(-1);
        filtersForIndex.put(extendedCond, existingAdditionalConditions);
      }
      existingAdditionalConditions.getSubBlocks().add(item.getRemainingCondition());
    }
    List<IndexSearchDescriptor> result = new ArrayList<>();
    for (var item : aggregation.entrySet()) {
      for (var filters : item.getValue().entrySet()) {
        result.add(
            new IndexSearchDescriptor(
                item.getKey(),
                filters.getKey().mainCondition,
                filters.getKey().additionalRange,
                filters.getValue()));
      }
    }
    return result;
  }


  private static void handleSubqueryAsTarget(
      SelectExecutionPlan plan,
      SQLStatement subQuery,
      CommandContext ctx,
      boolean profilingEnabled) {
    var subCtx = new BasicCommandContext();
    subCtx.setDatabaseSession(ctx.getDatabaseSession());
    subCtx.setParent(ctx);
    var subExecutionPlan =
        subQuery.createExecutionPlan(subCtx, profilingEnabled);
    plan.chain(new SubQueryStep(subExecutionPlan, ctx, subCtx, profilingEnabled));
  }

  private static boolean isOrderByRidDesc(QueryPlanningInfo info) {
    if (!hasTargetWithSortedRids(info)) {
      return false;
    }

    if (info.orderBy == null) {
      return false;
    }
    if (info.orderBy.getItems().size() == 1) {
      var item = info.orderBy.getItems().getFirst();
      var recordAttr = item.getRecordAttr();
      return recordAttr != null
          && recordAttr.equalsIgnoreCase("@rid")
          && SQLOrderByItem.DESC.equals(item.getType());
    }
    return false;
  }

  private static boolean isOrderByRidAsc(QueryPlanningInfo info) {
    if (!hasTargetWithSortedRids(info)) {
      return false;
    }

    if (info.orderBy == null) {
      return false;
    }
    if (info.orderBy.getItems().size() == 1) {
      var item = info.orderBy.getItems().getFirst();
      var recordAttr = item.getRecordAttr();
      return recordAttr != null
          && recordAttr.equalsIgnoreCase("@rid")
          && (item.getType() == null || SQLOrderByItem.ASC.equals(item.getType()));
    }
    return false;
  }

  private static boolean hasTargetWithSortedRids(QueryPlanningInfo info) {
    if (info.target == null) {
      return false;
    }
    if (info.target.getItem() == null) {
      return false;
    }

    return info.target.getItem().getIdentifier() != null;
  }
}
