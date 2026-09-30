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
 * Plan-time rewrite of edge-free MATCH {@code ORDER BY} onto the synthetic root SELECT, so fetch
 * selection matches {@code SELECT FROM Class WHERE … ORDER BY prop} ({@code
 * SelectExecutionPlanner}: rid → filter index → sort-only VALUES → class).
 *
 * <p>Injection does not depend on a private WHERE allow-list: any residual filter travels with the
 * SELECT and the planner picks the scan. {@link #orderFullyCovered()} stays conservative (RID /
 * null-group / return shape) and only then drops MATCH's {@code OrderByStep}.
 *
 * <p>Edge-hop index order stays in {@link IndexOrderedPlanner}.
 */
final class SingleNodeIndexOrder {

  private static final String RECORD_ID_ATTRIBUTE = "@rid";

  private SingleNodeIndexOrder() {
  }

  /**
   * When present, the synthetic root SELECT carries {@link #selectOrderBy()} so the SELECT planner
   * owns fetch + primary order. {@link #orderFullyCovered()} is true when MATCH must not append a
   * second {@code OrderByStep}.
   */
  record Candidate(
      @Nonnull String alias,
      @Nonnull SQLOrderBy selectOrderBy,
      boolean orderFullyCovered) {
  }

  /**
   * Returns a candidate when the pattern is one isolated node and ORDER BY rewrites to a bare
   * property on that node (optional RID secondary). Index / WHERE admission is left to the SELECT
   * planner; elision uses {@link #orderFullyCovered()}.
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
    // Multi-key ORDER BY beyond primary + optional @rid is not rewritten onto the root SELECT.
    if (items.size() > 2) {
      return null;
    }
    var primary = items.getFirst();
    if (primary.getCollate() != null
        || !IndexOrderedPlanner.isDefaultCollate(primary.getDeclaredCollate())) {
      return null;
    }
    var resolved = resolveOrderByToAliasProperty(primary, returnItems, returnAliases);
    if (resolved == null || !alias.equals(resolved[0])) {
      return null;
    }
    var propertyName = resolved[1];
    var orderAsc = SQLOrderByItem.ASC.equals(primary.getType())
        || primary.getType() == null;

    var selectItem = ProjectionExpressionFactories.orderByProjectionAlias(propertyName, orderAsc);
    selectItem.setNullOrdering(primary.getNullOrdering());
    selectItem.setDeclaredCollate(primary.getDeclaredCollate());
    var selectOrderBy = ProjectionExpressionFactories.orderBy(List.of(selectItem));

    if (items.size() == 1) {
      // SELECT applies the bare primary key (index stream or its own OrderByStep). MATCH must not
      // sort again.
      return new Candidate(alias, selectOrderBy, true);
    }

    // size == 2: inject primary ORDER BY for the SELECT fetch queue; elide MATCH OrderByStep only
    // when the secondary is an index-native RID tie-break and the filter cannot divert the root
    // onto a different index (which would drop RID order from the stream).
    var session = (DatabaseSessionEmbedded) context.getDatabaseSession();
    if (session == null) {
      return new Candidate(alias, selectOrderBy, false);
    }
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    if (schema == null) {
      return new Candidate(alias, selectOrderBy, false);
    }
    var clazz = schema.getClassInternal(className);
    if (clazz == null) {
      return new Candidate(alias, selectOrderBy, false);
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

    var aliasFilter = aliasFilters.get(alias);
    var ridAccepted =
        matchedIndex != null
            && filterCannotStealOrderIndex(aliasFilter, propertyName)
            && acceptsRidTieBreak(
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
    return new Candidate(alias, selectOrderBy, ridAccepted);
  }

  /**
   * True when the alias filter cannot divert the root SELECT onto a different index than {@code
   * propertyName}. Used only for RID elision: a stolen filter index would order ties by that
   * index's key, not by {@code @rid}. A null filter is safe. Presence-only checks on the ordered
   * property ({@code IS NOT NULL} / {@code IS DEFINED}) leave the sort-only VALUES path open.
   */
  private static boolean filterCannotStealOrderIndex(
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
      @Nullable List<SQLExpression> returnItems,
      @Nullable List<SQLIdentifier> returnAliases) {
    var orderAlias = orderItem.getAlias();
    if (orderAlias == null) {
      return null;
    }
    var modifier = orderItem.getModifier();
    if (modifier != null) {
      var propertyName = modifier.getSimpleSuffixPropertyName();
      if (propertyName != null) {
        return new String[] {orderAlias, propertyName};
      }
      return null;
    }
    if (returnAliases != null && returnItems != null) {
      for (int i = 0; i < returnAliases.size(); i++) {
        var retAlias = returnAliases.get(i);
        if (retAlias != null && retAlias.getStringValue().equals(orderAlias)) {
          return resolveSimpleDotExpression(returnItems.get(i));
        }
      }
    }
    return null;
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
