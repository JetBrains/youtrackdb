package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.IndexOrderedPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.List;

/** Checks whether indexed property values follow the ORDER BY item's comparator. */
public final class IndexOrderTypeAgreement {

  private IndexOrderTypeAgreement() {
  }

  /**
   * Checks ordered properties in query order. The caller has already checked scan direction,
   * collation, null placement and that the index contains these properties in the required order.
   * A trailing RID item is not included in {@code items} or {@code fields}.
   */
  public static boolean agrees(
      SchemaClass clazz,
      IndexDefinition definition,
      List<SQLOrderByItem> items,
      List<String> fields,
      boolean ridTieBreak) {
    if (clazz == null || definition == null || items == null || items.isEmpty()
        || items.size() != fields.size()) {
      return false;
    }
    var indexFields = definition.getProperties();
    var indexTypes = definition.getTypes();
    for (var i = 0; i < items.size(); i++) {
      var field = fields.get(i);
      var position = indexFields.indexOf(field);
      if (position < 0 || position >= indexTypes.length) {
        return false;
      }
      var property = clazz.getProperty(field);
      if (property == null || property.getType() != indexTypes[position].getPublicPropertyType()) {
        return false;
      }
      var type = indexTypes[position];
      if (type == PropertyTypeInternal.STRING
          && (!IndexOrderedPlanner.isDefaultCollate(property.getCollate())
              || !IndexOrderedPlanner.isDefaultCollate(definition.getCollate()))) {
        return false;
      }
      if (!isAgreementType(type)
          && (items.get(i).isGremlinToMatchTranslatorProduced()
              || ridTieBreak || i != items.size() - 1)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAgreementType(PropertyTypeInternal type) {
    return switch (type) {
      case STRING, BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, DECIMAL, DATETIME, BOOLEAN -> true;
      default -> false;
    };
  }
}
