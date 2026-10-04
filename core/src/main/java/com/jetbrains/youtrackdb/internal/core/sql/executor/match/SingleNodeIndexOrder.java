package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNotNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Translates single-node MATCH order requests and consumes the chosen root SELECT's order report.
 * SELECT owns index eligibility. This helper keeps alias and RID tie-break checks in MATCH.
 *
 * <p>Edge-hop index order stays in {@link IndexOrderedPlanner}. This helper covers the edge-free
 * shape {@code MATCH {class: C, as: a} RETURN a ORDER BY a.prop}, including the Gremlin translation
 * of {@code g.V().hasLabel(C).order().by(prop)}.
 */
final class SingleNodeIndexOrder {

  private static final String RECORD_ID_ATTRIBUTE = "@rid";

  private SingleNodeIndexOrder() {
  }

  /** A translated order request whose result comes from the root SELECT's actual scan. */
  static final class Candidate {

    private final String alias;
    private final SQLOrderBy selectOrderBy;
    private final boolean ridTieBreak;
    private final boolean matchOrderPreserved;
    private final boolean ridEligible;
    private boolean orderFullyCovered;
    private boolean ridTieBreakAccepted;

    Candidate(String alias, SQLOrderBy selectOrderBy, boolean ridTieBreak,
        boolean matchOrderPreserved, boolean ridEligible) {
      this.alias = alias;
      this.selectOrderBy = selectOrderBy;
      this.ridTieBreak = ridTieBreak;
      this.matchOrderPreserved = matchOrderPreserved;
      this.ridEligible = ridEligible;
    }

    boolean hasRidTieBreak() {
      return ridTieBreak;
    }

    String alias() {
      return alias;
    }

    SQLOrderBy selectOrderBy() {
      return selectOrderBy;
    }

    boolean orderFullyCovered() {
      return orderFullyCovered;
    }

    boolean ridTieBreakAccepted() {
      return ridTieBreakAccepted;
    }

    void consume(SelectExecutionPlan.OrderReport report) {
      // RID ordering is a committed-content fact, not permission to drop the runtime sort step.
      orderFullyCovered = matchOrderPreserved && !ridTieBreak && report.fullOrderCovered();
      ridTieBreakAccepted = matchOrderPreserved && ridEligible
          && report.fullOrderCovered() && report.ridOrderWithinEqualKeys();
    }
  }

  /**
   * Returns a request when all property order items resolve to the isolated node, optionally
   * followed by a RID tie-break. No index lookup occurs here.
   */
  @Nullable static Candidate detect(
      @Nullable Pattern pattern,
      @Nullable SQLOrderBy orderBy,
      @Nonnull Map<String, String> aliasClasses,
      @Nonnull Map<String, SQLWhereClause> aliasFilters,
      @Nullable List<SQLExpression> returnItems,
      @Nullable List<SQLIdentifier> returnAliases,
      boolean returnDistinct,
      boolean returnElements,
      boolean returnPaths,
      boolean returnPatterns,
      boolean returnPathElements,
      boolean matchOrderPreserved,
      @Nonnull CommandContext context) {
    if (pattern == null || orderBy == null || orderBy.getItems() == null
        || orderBy.getItems().isEmpty()) {
      return null;
    }
    // Edge-free only: any hop belongs to IndexOrderedPlanner (or the normal MATCH path).
    if (pattern.getNumOfEdges() != 0 || pattern.getAliasToNode().size() != 1) {
      return null;
    }
    var alias = pattern.getAliasToNode().keySet().iterator().next();
    var className = aliasClasses.get(alias);
    if (className == null) {
      return null;
    }

    var items = orderBy.getItems();
    var last = items.getLast();
    var ridTieBreak = last.getModifier() != null
        && RECORD_ID_ATTRIBUTE.equalsIgnoreCase(
            last.getModifier().getSimpleSuffixRecordAttributeName());
    var propertyCount = items.size() - (ridTieBreak ? 1 : 0);
    if (propertyCount == 0) {
      return null;
    }
    var translated = new ArrayList<SQLOrderByItem>(propertyCount);
    for (var item : items.subList(0, propertyCount)) {
      var resolved = resolveOrderByToAliasProperty(item, alias, returnItems, returnAliases);
      if (resolved == null || !alias.equals(resolved[0])
          || item.getRecordAttr() != null || item.getRid() != null) {
        return null;
      }
      // Copy the comparator metadata as well as the printed SQL order. SELECT checks eligibility.
      var selectItem = item.copy();
      selectItem.setAlias(resolved[1]);
      selectItem.setModifier(null);
      translated.add(selectItem);
    }
    var selectOrderBy = ProjectionExpressionFactories.orderBy(translated);
    var ridEligible = ridTieBreak && acceptsRidTieBreak(
        last, alias, className, selectOrderBy, aliasFilters.get(alias), returnItems, returnAliases,
        returnDistinct, returnElements, returnPaths, returnPatterns, returnPathElements, context);
    return new Candidate(alias, selectOrderBy, ridTieBreak, matchOrderPreserved, ridEligible);
  }

  private static boolean acceptsRidTieBreak(
      SQLOrderByItem ridItem,
      String alias,
      String className,
      SQLOrderBy propertyOrder,
      @Nullable SQLWhereClause aliasFilter,
      @Nullable List<SQLExpression> returnItems,
      @Nullable List<SQLIdentifier> returnAliases,
      boolean returnDistinct,
      boolean returnElements,
      boolean returnPaths,
      boolean returnPatterns,
      boolean returnPathElements,
      CommandContext context) {
    var orderAsc = !SQLOrderByItem.DESC.equals(propertyOrder.getItems().getFirst().getType());
    if (!propertyOrder.ordersSameDirection() || !isRecordIdItemOf(ridItem, alias, orderAsc)) {
      return false;
    }
    if (!orderAsc && !nullKeysExcluded(className, propertyOrder, aliasFilter, context)) {
      return false;
    }
    if (returnDistinct
        || returnElements
        || returnPaths
        || returnPatterns
        || returnPathElements
        || !projectsOnlyAlias(alias, returnItems, returnAliases)) {
      return false;
    }
    return true;
  }

  private static boolean nullKeysExcluded(
      String className, SQLOrderBy order, @Nullable SQLWhereClause filter, CommandContext context) {
    var session = (DatabaseSessionEmbedded) context.getDatabaseSession();
    var clazz = session.getMetadata().getImmutableSchemaSnapshot().getClassInternal(className);
    // DESC composite orders retain the conservative per-key null-exclusion rule too.
    for (var propertyName : order.getProperties()) {
      var property = clazz.getProperty(propertyName);
      if ((property == null || !property.isNotNull())
          && (filter == null || !requiresNotNull(filter.getBaseExpression(), propertyName))) {
        return false;
      }
    }
    return true;
  }

  private static boolean requiresNotNull(
      @Nullable SQLBooleanExpression expr, String propertyName) {
    if (expr instanceof SQLIsNotNullCondition notNull) {
      return propertyName.equals(extractSimpleFieldName(notNull.getExpression()));
    }
    if (expr instanceof SQLBinaryCondition binary) {
      // Equality and range operators reject null operands. Not-equal is not such a proof.
      return (binary.getOperator() instanceof SQLEqualsOperator
          || binary.getOperator().isRangeOperator())
          && propertyName.equals(extractSimpleFieldName(binary.getLeft()))
          && !"null".equalsIgnoreCase(binary.getRight().toString());
    }
    if (expr instanceof SQLAndBlock andBlock) {
      for (var sub : andBlock.getSubBlocks()) {
        if (requiresNotNull(sub, propertyName)) {
          return true;
        }
      }
      return false;
    }
    if (expr instanceof SQLNotBlock notBlock && !notBlock.isNegate()) {
      return requiresNotNull(notBlock.getSub(), propertyName);
    }
    if (expr instanceof SQLOrBlock orBlock && orBlock.getSubBlocks().size() == 1) {
      return requiresNotNull(orBlock.getSubBlocks().getFirst(), propertyName);
    }
    return false;
  }

  private static boolean isRecordIdItemOf(SQLOrderByItem item, String alias, boolean orderAsc) {
    if (!alias.equals(item.getAlias())
        || item.getRecordAttr() != null
        || item.getRid() != null
        || item.getCollate() != null) {
      return false;
    }
    var modifier = item.getModifier();
    if (modifier == null
        || !RECORD_ID_ATTRIBUTE.equalsIgnoreCase(modifier.getSimpleSuffixRecordAttributeName())) {
      return false;
    }
    return orderAsc == !SQLOrderByItem.DESC.equals(item.getType());
  }

  private static boolean projectsOnlyAlias(
      String alias,
      @Nullable List<SQLExpression> returnItems,
      @Nullable List<SQLIdentifier> returnAliases) {
    if (returnItems == null || returnItems.size() != 1) {
      return false;
    }
    var projected = new StringBuilder();
    returnItems.getFirst().toString(new HashMap<>(), projected);
    if (!alias.contentEquals(projected)) {
      return false;
    }
    if (returnAliases == null || returnAliases.size() != 1) {
      return true;
    }
    var projectionAlias = returnAliases.getFirst();
    return projectionAlias == null || alias.equals(projectionAlias.getStringValue());
  }

  @Nullable private static String[] resolveOrderByToAliasProperty(
      SQLOrderByItem orderItem,
      @Nonnull String patternAlias,
      @Nullable List<SQLExpression> returnItems,
      @Nullable List<SQLIdentifier> returnAliases) {
    var orderAlias = orderItem.getAlias();
    if (orderAlias == null) {
      return null;
    }
    var modifier = orderItem.getModifier();
    // RETURN can rebind the pattern alias (RETURN s.child AS s). ORDER BY s.score would then
    // resolve as the pattern node's score via the modifier branch below — refuse that case.
    // Projection last-wins: only the last RETURN item that binds the alias matters, so
    // RETURN s.k AS s, s AS s ORDER BY s.k still sees bare s and opens the index.
    // Bare ORDER BY s (no modifier) falls through to projection resolution.
    if (modifier != null
        && returnAliases != null
        && returnItems != null
        && patternAlias.equals(orderAlias)) {
      int lastBinding = -1;
      for (int i = 0; i < returnAliases.size(); i++) {
        var retAlias = returnAliases.get(i);
        if (retAlias != null && patternAlias.equals(retAlias.getStringValue())) {
          lastBinding = i;
        }
      }
      if (lastBinding >= 0
          && !isBareAliasProjection(returnItems.get(lastBinding), patternAlias)) {
        return null;
      }
    }
    if (modifier != null) {
      var propertyName = modifier.getSimpleSuffixPropertyName();
      if (propertyName != null) {
        return new String[] {orderAlias, propertyName};
      }
      return null;
    }
    if (returnAliases != null && returnItems != null) {
      // Duplicate RETURN aliases: ORDER BY uses the last expression (same as projection).
      String[] resolved = null;
      for (int i = 0; i < returnAliases.size(); i++) {
        var retAlias = returnAliases.get(i);
        if (retAlias != null && retAlias.getStringValue().equals(orderAlias)) {
          resolved = resolveSimpleDotExpression(returnItems.get(i));
        }
      }
      return resolved;
    }
    return null;
  }

  /**
   * True when {@code expr} is exactly the bare pattern alias (no property path), so {@code RETURN
   * alias} / {@code RETURN alias AS alias} does not rebind the name to another entity.
   */
  private static boolean isBareAliasProjection(SQLExpression expr, String patternAlias) {
    var field = extractSimpleFieldName(expr);
    return patternAlias.equals(field);
  }

  @Nullable private static String[] resolveSimpleDotExpression(SQLExpression expr) {
    var math = expr.getMathExpression();
    if (!(math instanceof SQLBaseExpression baseExpr)) {
      return null;
    }
    var ident = baseExpr.getIdentifier();
    if (ident == null || ident.getSuffix() == null
        || ident.getSuffix().getIdentifier() == null
        || ident.getLevelZero() != null) {
      return null;
    }
    var mod = baseExpr.getModifier();
    if (mod == null) {
      return null;
    }
    var propertyName = mod.getSimpleSuffixPropertyName();
    if (propertyName == null) {
      return null;
    }
    return new String[] {
        ident.getSuffix().getIdentifier().getStringValue(),
        propertyName
    };
  }

  @Nullable private static String extractSimpleFieldName(SQLExpression expr) {
    var math = expr.getMathExpression();
    if (!(math instanceof SQLBaseExpression baseExpr)) {
      return null;
    }
    if (baseExpr.getModifier() != null) {
      return null;
    }
    var ident = baseExpr.getIdentifier();
    if (ident == null) {
      return null;
    }
    var suffix = ident.getSuffix();
    if (suffix == null || suffix.getIdentifier() == null) {
      return null;
    }
    if (ident.getLevelZero() != null) {
      return null;
    }
    return suffix.getIdentifier().getStringValue();
  }
}
