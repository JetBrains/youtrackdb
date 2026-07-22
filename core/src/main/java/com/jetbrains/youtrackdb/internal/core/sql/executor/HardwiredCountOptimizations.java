package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;

/**
 * Shared hardwired count short-circuit used by {@link SelectExecutionPlanner} and {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner}. Routes eligible
 * {@code count(*)} shapes to {@link CountFromClassStep} / {@link CountFromIndexWithKeyStep}.
 */
public final class HardwiredCountOptimizations {

  private HardwiredCountOptimizations() {
    // Static helper — no instances.
  }

  /**
   * Tries the class-metadata count then the indexed-equality count. Returns {@code true} when a
   * short-circuit step was chained (the caller should return the plan immediately).
   */
  public static boolean tryApply(
      SelectExecutionPlan result,
      QueryPlanningInfo info,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (handleHardwiredCountOnClass(result, info, ctx, profilingEnabled)) {
      return true;
    }
    return handleHardwiredCountOnClassUsingIndex(result, info, ctx, profilingEnabled);
  }

  /**
   * MATCH-side bare class count: single-node pattern with no filters. Chains {@link
   * CountFromClassStep} when security permits.
   */
  public static boolean tryMatchCountFromClass(
      SelectExecutionPlan result,
      SchemaClassInternal targetClass,
      String resultAlias,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (targetClass == null || resultAlias == null) {
      return false;
    }
    if (securityPoliciesExistForClass(targetClass, ctx)) {
      return false;
    }
    result.chain(new CountFromClassStep(targetClass, resultAlias, ctx, profilingEnabled));
    return true;
  }

  /**
   * Handles {@code SELECT count(*) FROM ClassName} with no WHERE / GROUP BY / ORDER BY / SKIP /
   * LET.
   */
  static boolean handleHardwiredCountOnClass(
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

  /**
   * Handles {@code SELECT count(*) FROM ClassName WHERE field = ?} when a single-field index
   * covers the equality.
   */
  static boolean handleHardwiredCountOnClassUsingIndex(
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

  static boolean securityPoliciesExistForClass(
      SchemaClassInternal targetClass, CommandContext ctx) {
    if (targetClass == null) {
      return false;
    }
    var session = ctx.getDatabaseSession();
    var security = session.getSharedContext().getSecurity();
    return security.isReadRestrictedBySecurityPolicy(
        session, "database.class." + targetClass.getName());
  }

  static boolean isMinimalQuery(QueryPlanningInfo info) {
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

  static boolean isCountStar(QueryPlanningInfo info) {
    if (info.aggregateProjection == null
        || info.projection == null
        || info.aggregateProjection.getItems().size() != 1
        || info.projection.getItems().size() != 1) {
      return false;
    }
    var item = info.aggregateProjection.getItems().getFirst();
    var postItem = info.projection.getItems().getFirst();
    return item.getExpression().toString().equalsIgnoreCase("count(*)")
        && postItem.getExpression().isBaseIdentifier();
  }
}
