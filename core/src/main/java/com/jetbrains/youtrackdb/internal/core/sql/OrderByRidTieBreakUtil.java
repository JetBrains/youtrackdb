package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.List;

/**
 * Appends {@code boundaryAlias.@rid ASC} to a Gremlin-translated {@code ORDER BY} when the last
 * sort key is neither {@code @rid} nor the business {@code id} property. Native Gremlin gets the
 * same rule from {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy}.
 */
public final class OrderByRidTieBreakUtil {

  private static final String RID_ATTR = "@rid";
  private static final String ID_PROPERTY = "id";

  private OrderByRidTieBreakUtil() {
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
