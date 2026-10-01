package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNotNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Plan-time detection of a single-node MATCH root that can stream an indexed property order the
 * same way {@code SELECT FROM Class ORDER BY prop} uses {@code FetchFromIndexValuesStep}.
 *
 * <p>Edge-hop index order stays in {@link IndexOrderedPlanner}. This helper covers the edge-free
 * shape {@code MATCH {class: C, as: a} RETURN a ORDER BY a.prop}, including the Gremlin translation
 * of {@code g.V().hasLabel(C).order().by(prop)}.
 */
final class SingleNodeIndexOrder {

  private static final String RECORD_ID_ATTRIBUTE = "@rid";

  private SingleNodeIndexOrder() {
  }

  /**
   * When present, the synthetic root SELECT should carry {@link #selectOrderBy()} so the SELECT
   * planner can open an ordered index scan.
   *
   * <p>{@link #orderFullyCovered()} is true only for a single ORDER BY key — MATCH drops its
   * OrderByStep. A trailing {@code @rid} never claims coverage: MATCH keeps OrderByStep and
   * {@link #ridTieBreakAccepted()} tells the planner to set {@code indexOrderedUpstream} so
   * OrderByStep can pass through when the transaction is clean (same runtime contract as
   * {@link IndexOrderedEdgeStep}).
   */
  record Candidate(
      @Nonnull String alias,
      @Nonnull SQLOrderBy selectOrderBy,
      boolean orderFullyCovered,
      boolean ridTieBreakAccepted) {
  }

  /**
   * Returns a candidate when the pattern is one isolated node, ORDER BY resolves to one indexed
   * property on that node (optional RID tie-break), and an eligible index exists; otherwise {@code
   * null}.
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
    var primary = items.getFirst();
    if (primary.getCollate() != null
        || !IndexOrderedPlanner.isDefaultCollate(primary.getDeclaredCollate())) {
      return null;
    }
    var resolved = resolveOrderByToAliasProperty(primary, alias, returnItems, returnAliases);
    if (resolved == null || !alias.equals(resolved[0])) {
      return null;
    }
    var propertyName = resolved[1];
    var orderAsc = SQLOrderByItem.ASC.equals(primary.getType())
        || primary.getType() == null;

    var session = (DatabaseSessionEmbedded) context.getDatabaseSession();
    if (session == null) {
      return null;
    }
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    if (schema == null) {
      return null;
    }
    var clazz = schema.getClassInternal(className);
    if (clazz == null) {
      return null;
    }

    Index matchedIndex = null;
    for (var idx : clazz.getIndexesInternal()) {
      var definition = idx.getDefinition();
      if (definition == null || definition.isNullValuesIgnored()) {
        continue;
      }
      if (IndexOrderedPlanner.isMultiValueDefinition(definition)
          || !IndexOrderedPlanner.isDefaultCollate(definition.getCollate())) {
        continue;
      }
      var fields = definition.getProperties();
      if (fields.size() != 1 || !propertyName.equals(fields.getFirst())) {
        continue;
      }
      matchedIndex = idx;
      break;
    }
    if (matchedIndex == null) {
      return null;
    }

    var aliasFilter = aliasFilters.get(alias);
    // A non-null WHERE can be served by a different index than the ORDER BY key. The root SELECT
    // then sorts in memory on the primary key alone, and claiming orderFullyCovered would drop
    // MATCH's OrderByStep (including the RID tie-break). Only admit when the filter cannot steal
    // another index — null, or IS NOT NULL on the ordered property alone.
    if (!filterAllowsCoveredOrder(aliasFilter, propertyName)) {
      return null;
    }

    var selectItem = ProjectionExpressionFactories.orderByProjectionAlias(propertyName, orderAsc);
    selectItem.setNullOrdering(primary.getNullOrdering());
    selectItem.setDeclaredCollate(primary.getDeclaredCollate());
    // Keep GremlinOrderComparator when the MATCH item came from the Gremlin→MATCH translator;
    // a fresh projection-alias item would otherwise use DefaultComparator after elision.
    selectItem.setGremlinToMatchTranslatorProduced(primary.isGremlinToMatchTranslatorProduced());
    var selectOrderBy = ProjectionExpressionFactories.orderBy(List.of(selectItem));

    if (items.size() == 1) {
      return new Candidate(alias, selectOrderBy, true, false);
    }
    if (items.size() != 2) {
      return null;
    }
    var ridAccepted =
        acceptsRidTieBreak(
            items.get(1),
            alias,
            propertyName,
            matchedIndex,
            orderAsc,
            aliasFilter,
            returnItems,
            returnAliases,
            returnDistinct,
            returnElements,
            returnPaths,
            returnPatterns,
            returnPathElements);
    // Always open the primary-key VALUES scan. Never elide MATCH OrderBy for two keys
    // (orderFullyCovered=false): dirty-tx index entries can break @rid order within a key group.
    // When ridAccepted, the planner sets indexOrderedUpstream and MatchFirstStep signals
    // PRE_SORTED only for a clean transaction — OrderByStep then pass-throughs (IndexOrdered
    // contract).
    return new Candidate(alias, selectOrderBy, false, ridAccepted);
  }

  /**
   * True when the alias filter cannot divert the root SELECT onto a different index than {@code
   * propertyName}. A null filter is safe. The whole filter must be only {@code IS NOT NULL} /
   * {@code IS DEFINED} on that property (AND of those alone is fine). Any other conjunct may win
   * {@code handleClassAsTargetWithIndex} over the sort path.
   */
  private static boolean filterAllowsCoveredOrder(
      @Nullable SQLWhereClause filter, String propertyName) {
    if (filter == null) {
      return true;
    }
    return isOnlyPresenceFilterOn(filter.getBaseExpression(), propertyName);
  }

  /**
   * Whole expression is only {@code IS NOT NULL} / {@code IS DEFINED} on {@code propertyName},
   * possibly wrapped in non-negating NOT / single-OR / AND of the same.
   */
  private static boolean isOnlyPresenceFilterOn(
      @Nullable SQLBooleanExpression expr, String propertyName) {
    if (expr instanceof SQLIsNotNullCondition notNull) {
      return propertyName.equals(extractSimpleFieldName(notNull.getExpression()));
    }
    if (expr instanceof SQLIsDefinedCondition defined) {
      return propertyName.equals(extractSimpleFieldName(defined.getExpression()));
    }
    if (expr instanceof SQLAndBlock andBlock) {
      var subs = andBlock.getSubBlocks();
      if (subs.isEmpty()) {
        return false;
      }
      for (var sub : subs) {
        if (!isOnlyPresenceFilterOn(sub, propertyName)) {
          return false;
        }
      }
      return true;
    }
    if (expr instanceof SQLNotBlock notBlock && !notBlock.isNegate()) {
      return isOnlyPresenceFilterOn(notBlock.getSub(), propertyName);
    }
    if (expr instanceof SQLOrBlock orBlock && orBlock.getSubBlocks().size() == 1) {
      return isOnlyPresenceFilterOn(orBlock.getSubBlocks().getFirst(), propertyName);
    }
    return false;
  }

  private static boolean acceptsRidTieBreak(
      SQLOrderByItem ridItem,
      String alias,
      String propertyName,
      Index matchedIndex,
      boolean orderAsc,
      @Nullable SQLWhereClause aliasFilter,
      @Nullable List<SQLExpression> returnItems,
      @Nullable List<SQLIdentifier> returnAliases,
      boolean returnDistinct,
      boolean returnElements,
      boolean returnPaths,
      boolean returnPatterns,
      boolean returnPathElements) {
    if (!isRecordIdItemOf(ridItem, alias, orderAsc)) {
      return false;
    }
    if (!orderAsc && !nullKeysExcluded(matchedIndex, propertyName, aliasFilter)) {
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
    return propertyName.equals(matchedIndex.getDefinition().getProperties().getFirst());
  }

  private static boolean nullKeysExcluded(
      Index index, String propertyName, @Nullable SQLWhereClause filter) {
    var definition = index.getDefinition();
    if (definition != null && definition.isNullValuesIgnored()) {
      return true;
    }
    return filter != null && requiresNotNull(filter.getBaseExpression(), propertyName);
  }

  private static boolean requiresNotNull(
      @Nullable SQLBooleanExpression expr, String propertyName) {
    if (expr instanceof SQLIsNotNullCondition notNull) {
      return propertyName.equals(extractSimpleFieldName(notNull.getExpression()));
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
    return orderAsc == SQLOrderByItem.ASC.equals(item.getType());
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
