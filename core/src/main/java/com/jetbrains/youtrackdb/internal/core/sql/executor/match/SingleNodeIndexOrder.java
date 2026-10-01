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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Plan-time rewrite of edge-free MATCH {@code ORDER BY} onto the synthetic root SELECT, so fetch
 * selection matches {@code SELECT FROM Class WHERE … ORDER BY props} ({@code
 * SelectExecutionPlanner}: rid → filter index → sort-only VALUES → class).
 *
 * <p><b>Wide inject, narrow elision.</b> Bare property keys (one or many) on the single alias are
 * rewritten onto the root SELECT whenever they reduce cleanly — residual WHERE travels with the
 * SELECT and does not block injection. An optional trailing {@code @rid} is kept for MATCH elision
 * only. {@link #orderFullyCovered()} is conservative (RID / null-group / return shape): MATCH drops
 * its {@code OrderByStep} only when that flag is true <em>and</em> MATCH grain allows it (no
 * UNWIND/GROUP BY; SKIP/LIMIT pushed onto the same synthetic SELECT). Otherwise MATCH keeps
 * {@code OrderByStep} while the root may still stream from VALUES.
 *
 * <p>Edge-hop index order stays in {@link IndexOrderedPlanner}.
 */
final class SingleNodeIndexOrder {

  private static final String RECORD_ID_ATTRIBUTE = "@rid";

  private SingleNodeIndexOrder() {
  }

  /**
   * When present, the synthetic root SELECT carries {@link #selectOrderBy()} so the SELECT planner
   * owns fetch + property order. {@link #orderFullyCovered()} means property/RID coverage is
   * sound; the MATCH planner may still keep {@code OrderByStep} when SKIP/LIMIT cannot share the
   * root SELECT grain.
   */
  record Candidate(
      @Nonnull String alias,
      @Nonnull SQLOrderBy selectOrderBy,
      boolean orderFullyCovered) {
  }

  /**
   * Returns a candidate when the pattern is one isolated node and ORDER BY rewrites to bare
   * properties on that node (optional trailing RID). Residual WHERE does not block injection —
   * fetch stays with the SELECT planner. {@link #orderFullyCovered()} gates MATCH elision only.
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
    var propertyNames = new ArrayList<String>();
    var selectItems = new ArrayList<SQLOrderByItem>();
    Boolean propertyDirectionAsc = null;
    for (var i = 0; i < items.size(); i++) {
      var item = items.get(i);
      if (item.getCollate() != null
          || !IndexOrderedPlanner.isDefaultCollate(item.getDeclaredCollate())) {
        return null;
      }
      var resolved = resolveOrderByToAliasProperty(
          item, alias, returnItems, returnAliases);
      if (resolved != null && alias.equals(resolved[0])) {
        var propertyName = resolved[1];
        var orderAsc = SQLOrderByItem.ASC.equals(item.getType()) || item.getType() == null;
        if (propertyDirectionAsc == null) {
          propertyDirectionAsc = orderAsc;
        } else if (propertyDirectionAsc != orderAsc) {
          // Mixed ASC/DESC among property keys cannot be served by one index VALUES scan; a
          // trailing @rid would then claim coverage incorrectly. Refuse the whole candidate when
          // a later pass would need RID elision — handled below once ridTrailing is known.
          propertyDirectionAsc = null; // mark mixed; see ridTrailing handling
        }
        var selectItem =
            ProjectionExpressionFactories.orderByProjectionAlias(propertyName, orderAsc);
        selectItem.setNullOrdering(item.getNullOrdering());
        selectItem.setDeclaredCollate(item.getDeclaredCollate());
        // Preserve Gremlin comparator selection: translator-built MATCH ORDER BY items use
        // GremlinOrderComparator for schema-less mixed types. Fresh projection-alias items would
        // otherwise fall through to DefaultComparator and ClassCast (or silent ties) on the
        // synthetic root SELECT after inject/elision.
        selectItem.setGremlinToMatchTranslatorProduced(
            item.isGremlinToMatchTranslatorProduced());
        propertyNames.add(propertyName);
        selectItems.add(selectItem);
        continue;
      }
      // Non-property key: only a trailing @rid is allowed (MATCH elision; not injected into SELECT).
      if (i != items.size() - 1 || selectItems.isEmpty()) {
        return null;
      }
      break;
    }
    if (selectItems.isEmpty()) {
      return null;
    }
    var ridTrailing = propertyNames.size() < items.size();
    if (ridTrailing && propertyNames.size() != items.size() - 1) {
      return null;
    }
    var mixedPropertyDirections = propertyDirectionAsc == null && selectItems.size() > 1;
    var selectOrderBy = ProjectionExpressionFactories.orderBy(selectItems);

    if (!ridTrailing) {
      // SELECT applies the bare property keys (index stream or its own OrderByStep).
      return new Candidate(alias, selectOrderBy, true);
    }
    // Mixed property directions: index VALUES is uni-directional, so @rid coverage is impossible.
    // Without an exact-width index, injecting ORDER BY would force an unbounded root SELECT sort
    // while MATCH still keeps OrderBy+LIMIT — refuse and leave MATCH as the sole sorter.
    if (mixedPropertyDirections) {
      return null;
    }

    // Trailing @rid: inject property ORDER BY for the SELECT fetch queue; elide MATCH OrderByStep
    // only when the RID secondary is index-native and the filter cannot divert the root onto a
    // different index (which would drop RID order from the stream).
    var primaryAsc = SQLOrderByItem.ASC.equals(items.getFirst().getType())
        || items.getFirst().getType() == null;
    if (!isRecordIdItemOf(items.getLast(), alias, primaryAsc)) {
      return null;
    }

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

    Index matchedIndex = findExactWidthOrderIndex(clazz.getIndexesInternal(), propertyNames);
    // No index to stream primary order: MATCH must own the (bounded) sort. Injecting ORDER BY
    // into the root SELECT would sort the whole class before MATCH's LIMIT heap.
    if (matchedIndex == null) {
      return null;
    }
    var aliasFilter = aliasFilters.get(alias);
    var ridAccepted =
        filterCannotStealOrderIndex(aliasFilter, propertyNames)
            && acceptsRidTieBreak(
                matchedIndex,
                propertyNames,
                primaryAsc,
                aliasFilter,
                alias,
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
   * Exact-width index whose fields are {@code propertyNames} in order. Used for RID elision only;
   * fetch selection still belongs to the SELECT planner (which also prefers exact-width).
   */
  @Nullable private static Index findExactWidthOrderIndex(
      Iterable<Index> indexes, List<String> propertyNames) {
    for (var idx : indexes) {
      var definition = idx.getDefinition();
      if (definition == null || definition.isNullValuesIgnored()) {
        continue;
      }
      if (IndexOrderedPlanner.isMultiValueDefinition(definition)
          || !IndexOrderedPlanner.isDefaultCollate(definition.getCollate())) {
        continue;
      }
      var fields = definition.getProperties();
      if (fields.size() == propertyNames.size() && fields.equals(propertyNames)) {
        return idx;
      }
    }
    return null;
  }

  /**
   * True when the alias filter cannot divert the root SELECT onto a different index than the order
   * keys. Used only for RID elision. A null filter is safe. Presence-only checks on the ordered
   * properties leave the sort-only VALUES path open.
   */
  private static boolean filterCannotStealOrderIndex(
      @Nullable SQLWhereClause filter, List<String> propertyNames) {
    if (filter == null) {
      return true;
    }
    return isOnlyPresenceFilterOn(
        filter.getBaseExpression(), new HashSet<>(propertyNames));
  }

  /**
   * Whole expression is only {@code IS NOT NULL} / {@code IS DEFINED} on one of {@code
   * propertyNames}, possibly wrapped in non-negating NOT / single-OR / AND of the same.
   */
  private static boolean isOnlyPresenceFilterOn(
      @Nullable SQLBooleanExpression expr, Set<String> propertyNames) {
    if (expr instanceof SQLIsNotNullCondition notNull) {
      var field = extractSimpleFieldName(notNull.getExpression());
      return field != null && propertyNames.contains(field);
    }
    if (expr instanceof SQLIsDefinedCondition defined) {
      var field = extractSimpleFieldName(defined.getExpression());
      return field != null && propertyNames.contains(field);
    }
    if (expr instanceof SQLAndBlock andBlock) {
      var subs = andBlock.getSubBlocks();
      if (subs.isEmpty()) {
        return false;
      }
      for (var sub : subs) {
        if (!isOnlyPresenceFilterOn(sub, propertyNames)) {
          return false;
        }
      }
      return true;
    }
    if (expr instanceof SQLNotBlock notBlock && !notBlock.isNegate()) {
      return isOnlyPresenceFilterOn(notBlock.getSub(), propertyNames);
    }
    if (expr instanceof SQLOrBlock orBlock && orBlock.getSubBlocks().size() == 1) {
      return isOnlyPresenceFilterOn(orBlock.getSubBlocks().getFirst(), propertyNames);
    }
    return false;
  }

  private static boolean acceptsRidTieBreak(
      Index matchedIndex,
      List<String> propertyNames,
      boolean orderAsc,
      @Nullable SQLWhereClause aliasFilter,
      String alias,
      @Nullable List<SQLExpression> returnItems,
      @Nullable List<SQLIdentifier> returnAliases,
      boolean returnDistinct,
      boolean returnElements,
      boolean returnPaths,
      boolean returnPatterns,
      boolean returnPathElements) {
    if (!orderAsc && !nullKeysExcluded(matchedIndex, propertyNames.getFirst(), aliasFilter)) {
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
    return matchedIndex.getDefinition().getProperties().equals(propertyNames);
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
    // RETURN can rebind the pattern alias (RETURN s.child AS s). ORDER BY s.score must not be
    // treated as the pattern node's score in that case — refuse SingleNode inject.
    if (returnAliases != null && returnItems != null && patternAlias.equals(orderAlias)) {
      for (int i = 0; i < returnAliases.size(); i++) {
        var retAlias = returnAliases.get(i);
        if (retAlias == null || !patternAlias.equals(retAlias.getStringValue())) {
          continue;
        }
        if (!isBareAliasProjection(returnItems.get(i), patternAlias)) {
          return null;
        }
      }
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
