package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIndexIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

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
   * MATCH-side bare class count: a single-node pattern with no filters, or with only an exact
   * {@code @class = 'ClassName'} filter (Gremlin non-polymorphic {@code hasLabel}). Resolves the
   * class on the runtime session's immutable snapshot and chains {@link CountFromClassStep} when
   * no security policy on the class requires per-record filtering. The step counts by class name at
   * execution time, so a cached MATCH plan is never tied to a schema object captured at plan-build
   * time.
   */
  public static boolean tryMatchCountFromClass(
      SelectExecutionPlan result,
      String className,
      String resultAlias,
      boolean polymorphic,
      CommandContext ctx,
      boolean profilingEnabled) {
    if (className == null || className.isBlank() || resultAlias == null) {
      return false;
    }
    var session = ctx.getDatabaseSession();
    var targetClass =
        (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
            .getClassInternal(className);
    if (targetClass == null) {
      return false;
    }
    if (securityPoliciesExistForClass(targetClass, ctx)) {
      return false;
    }
    result.chain(
        new CountFromClassStep(className, polymorphic, resultAlias, ctx, profilingEnabled));
    return true;
  }

  /**
   * Whether {@code where} is exactly {@code @class = 'expectedClass'} with no other predicates.
   * That filter is how Gremlin non-polymorphic {@code hasLabel(L)} narrows a MATCH node whose
   * {@code class:} is already {@code L}; folding it into {@code countClass(L, false)} preserves
   * leaf-exact semantics without a scan. Accepts both Gremlin-built AST ({@code
   * MatchWhereBuilder.classEquals}) and SQL-parsed MATCH {@code where: (@class = 'L')} shapes.
   */
  public static boolean isExactClassEqualsOnly(SQLWhereClause where, String expectedClass) {
    if (where == null || expectedClass == null || expectedClass.isBlank()) {
      return false;
    }
    var bin = unwrapSingleEquals(where.getBaseExpression());
    if (bin == null) {
      return false;
    }
    // toString is stable enough across builder vs parser for @class and string literals; structural
    // field walks diverge (record-attribute vs identifier, quote style).
    if (!"@class".equals(bin.getLeft().toString().trim())) {
      return false;
    }
    return expectedClass.equals(stripQuotes(bin.getRight().toString().trim()));
  }

  /**
   * Peels a single equality out of a bare binary condition, or a one-conjunct {@link SQLAndBlock} /
   * {@link SQLOrBlock} / non-negating {@link SQLNotBlock} (the shapes the SQL parser wraps {@code
   * WHERE} / MATCH {@code where: (…)} in).
   */
  private static SQLBinaryCondition unwrapSingleEquals(SQLBooleanExpression expr) {
    if (expr instanceof SQLBinaryCondition bin) {
      return bin.getOperator() instanceof SQLEqualsOperator ? bin : null;
    }
    if (expr instanceof SQLNotBlock notBlock) {
      // Parser wraps every atom in NotBlock; only peel when NOT was not applied.
      if (notBlock.isNegate()) {
        return null;
      }
      return unwrapSingleEquals(notBlock.getSub());
    }
    if (expr instanceof SQLAndBlock andBlock) {
      var subs = andBlock.getSubBlocks();
      if (subs != null && subs.size() == 1) {
        return unwrapSingleEquals(subs.getFirst());
      }
    }
    if (expr instanceof SQLOrBlock orBlock) {
      var subs = orBlock.getSubBlocks();
      if (subs != null && subs.size() == 1) {
        return unwrapSingleEquals(subs.getFirst());
      }
    }
    return null;
  }

  private static String stripQuotes(String literal) {
    if (literal == null || literal.length() < 2) {
      return literal;
    }
    var first = literal.charAt(0);
    var last = literal.charAt(literal.length() - 1);
    if ((first == '\'' || first == '"') && first == last) {
      return literal.substring(1, literal.length() - 1);
    }
    return literal;
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

  /**
   * Whether a class-level READ security policy applies to {@code targetClass} for this session. A
   * hardwired count reads the record count straight from class metadata and applies no per-record
   * filtering, so when a policy exists the count short-circuit must decline and let the generic
   * per-record path enforce row-level security. (Rationale retained from the original
   * {@code SelectExecutionPlanner} implementation when this helper was extracted.)
   */
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
