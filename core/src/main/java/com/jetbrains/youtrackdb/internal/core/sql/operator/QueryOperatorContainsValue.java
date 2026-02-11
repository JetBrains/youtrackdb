/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.operator;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * CONTAINS KEY operator.
 */
public class QueryOperatorContainsValue extends QueryOperatorEqualityNotNulls {

  public QueryOperatorContainsValue() {
    super("CONTAINSVALUE", 5, false);
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (!(iRight instanceof SQLFilterCondition) && !(iLeft instanceof SQLFilterCondition)) {
      return IndexReuseType.INDEX_METHOD;
    }

    return IndexReuseType.NO_INDEX;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSessionEmbedded session, Object iLeft, Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSessionEmbedded session, Object iLeft, Object iRight) {
    return null;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      Object iRight,
      CommandContext iContext) {
    final SQLFilterCondition condition;
    if (iCondition.getLeft() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getLeft();
    } else if (iCondition.getRight() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getRight();
    } else {
      condition = null;
    }

    var session = iContext.getDatabaseSession();
    PropertyTypeInternal type = null;
    if (iCondition.getLeft() instanceof SQLFilterItemField
        && ((SQLFilterItemField) iCondition.getLeft()).isFieldChain()
        && ((SQLFilterItemField) iCondition.getLeft()).getFieldChain().getItemCount() == 1) {
      var fieldName =
          ((SQLFilterItemField) iCondition.getLeft()).getFieldChain().getItemName(0);
      if (fieldName != null) {
        if (iRecord.isEntity()) {
          SchemaImmutableClass result;
          var entity = iRecord.asEntity();
          result = ((EntityImpl) entity).getImmutableSchemaClass(session);
          var property =
              result
                  .getProperty(fieldName);
          if (property != null && PropertyTypeInternal.convertFromPublicType(property.getType())
              .isMultiValue()) {
            type = PropertyTypeInternal.convertFromPublicType(property.getLinkedType());
          }
        }
      }
    }

    if (type != null) {
      iRight = type.convert(iRight, null, null, iContext.getDatabaseSession());
    }

    if (iLeft instanceof Map<?, ?>) {
      final var map = (Map<String, ?>) iLeft;

      if (condition != null) {
        // CHECK AGAINST A CONDITION
        for (var o : map.values()) {
          if ((Boolean) condition.evaluate((EntityImpl) o, null, iContext)) {
            return true;
          }
        }
      } else {
        for (var val : map.values()) {
          var convertedRight = iRight;
          if (val instanceof EntityImpl && iRight instanceof Map) {
            val = ((EntityImpl) val).toMap();
          }
          if (val instanceof Map && iRight instanceof EntityImpl) {
            convertedRight = ((EntityImpl) iRight).toMap();
          }
          if (QueryOperatorEquals.equals(iContext.getDatabaseSession(), val, convertedRight)) {
            return true;
          }
        }
        return false;
      }

    } else if (iRight instanceof Map<?, ?>) {
      final var map = (Map<String, ?>) iRight;

      if (condition != null)
      // CHECK AGAINST A CONDITION
      {
        for (var o : map.values()) {
          if ((Boolean) condition.evaluate((EntityImpl) o, null, iContext)) {
            return true;
          } else {
            return map.containsValue(iLeft);
          }
        }
      }
    }
    return false;
  }
}
