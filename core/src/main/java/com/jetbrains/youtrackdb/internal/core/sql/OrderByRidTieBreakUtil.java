package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.List;

/**
 * Keeps Gremlin-translated and native Gremlin {@code order()} aligned with YQL execution: append
 * {@code boundaryAlias.@rid ASC} to translated {@code ORDER BY} when no explicit tie-break is
 * present, and apply the same RID tie-break at runtime in {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.OrderByStep} when explicit keys still tie.
 *
 * <p>Native Gremlin gets {@code by(T.id, asc)} from {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy}.
 * Shapes that already spell {@code by("id", asc)} (LDBC IC2/IC8) skip the extra key so the MATCH
 * AST stays on business columns only.
 */
public final class OrderByRidTieBreakUtil {

  private static final String RID_ATTR = "@rid";
  private static final String ID_PROPERTY = "id";

  private OrderByRidTieBreakUtil() {
  }

  /**
   * Compares two rows by {@code orderBy}, then by {@code boundaryAlias.@rid ASC} when all explicit
   * keys tie and the last key is neither {@code @rid} nor {@code id}.
   */
  public static int compare(SQLOrderBy orderBy, Result a, Result b, CommandContext ctx) {
    var cmp = orderBy.compare(a, b, ctx);
    if (cmp != 0) {
      return cmp;
    }
    var items = orderBy.getItems();
    if (items == null || items.isEmpty()) {
      return 0;
    }
    var last = items.getLast();
    if (sortsByRid(last) || sortsByIdProperty(last)) {
      return 0;
    }
    var alias = last.getAlias();
    if (alias == null || alias.isBlank()) {
      return 0;
    }
    return ProjectionExpressionFactories.orderByRecordAttribute(alias, RID_ATTR, true)
        .compare(a, b, ctx);
  }

  /**
   * Appends {@code boundaryAlias.@rid ASC} when {@code items} does not already end on {@code @rid}
   * or {@code id}. Mutates {@code items} in place.
   */
  public static void appendRidTieBreakIfMissing(List<SQLOrderByItem> items, String boundaryAlias) {
    if (items == null || items.isEmpty()) {
      return;
    }
    if (boundaryAlias == null || boundaryAlias.isBlank()) {
      return;
    }
    var last = items.getLast();
    if (sortsByRid(last)) {
      return;
    }
    if (sortsByIdProperty(last) && boundaryAlias.equals(last.getAlias())) {
      return;
    }
    items.add(ProjectionExpressionFactories.orderByRecordAttribute(boundaryAlias, RID_ATTR, true));
  }

  /** Returns {@code true} when the item sorts on the record {@code @rid} attribute. */
  public static boolean sortsByRid(SQLOrderByItem item) {
    if (item.getRecordAttr() != null && RID_ATTR.equalsIgnoreCase(item.getRecordAttr())) {
      return true;
    }
    var modifier = item.getModifier();
    if (modifier == null) {
      return false;
    }
    var suffix = modifier.getSuffix();
    if (suffix == null) {
      return false;
    }
    var recordAttribute = suffix.getRecordAttribute();
    return recordAttribute != null && RID_ATTR.equalsIgnoreCase(recordAttribute.getName());
  }

  /** Returns {@code true} when the item sorts on the {@code id} property (LDBC business key). */
  public static boolean sortsByIdProperty(SQLOrderByItem item) {
    if (item.getRecordAttr() != null) {
      return false;
    }
    var modifier = item.getModifier();
    if (modifier == null) {
      return false;
    }
    return ID_PROPERTY.equals(modifier.getSimpleSuffixPropertyName());
  }
}
