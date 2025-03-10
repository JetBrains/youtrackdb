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
package com.jetbrains.youtrack.db.internal.core.sql.operator;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    final var indexDefinition = index.getDefinition();

    final var internalIndex = index.getInternal();
    Stream<RawPair<Object, RID>> stream;
    if (!internalIndex.canBeUsedInEqualityOperators()) {
      return null;
    }

    if (indexDefinition.getParamCount() == 1) {
      if (!((indexDefinition instanceof PropertyMapIndexDefinition)
          && ((PropertyMapIndexDefinition) indexDefinition).getIndexBy()
          == PropertyMapIndexDefinition.INDEX_BY.VALUE)) {
        return null;
      }

      final var key =
          ((IndexDefinitionMultiValue) indexDefinition)
              .createSingleValue(iContext.getDatabaseSession(), keyParams.get(0));

      if (key == null) {
        return null;
      }

      stream = index.getInternal().getRids(iContext.getDatabaseSession(), key)
          .map((rid) -> new RawPair<>(key, rid));
    } else {
      // in case of composite keys several items can be returned in case of we perform search
      // using part of composite key stored in index.
      final var compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      if (!((compositeIndexDefinition.getMultiValueDefinition()
          instanceof PropertyMapIndexDefinition)
          && ((PropertyMapIndexDefinition) compositeIndexDefinition.getMultiValueDefinition())
          .getIndexBy()
          == PropertyMapIndexDefinition.INDEX_BY.VALUE)) {
        return null;
      }

      final Object keyOne =
          compositeIndexDefinition.createSingleValue(iContext.getDatabaseSession(), keyParams);

      if (keyOne == null) {
        return null;
      }

      if (internalIndex.hasRangeQuerySupport()) {
        final Object keyTwo =
            compositeIndexDefinition.createSingleValue(iContext.getDatabaseSession(), keyParams);

        stream = index.getInternal()
            .streamEntriesBetween(iContext.getDatabaseSession(), keyOne, true, keyTwo, true,
                ascSortOrder);
      } else {
        if (indexDefinition.getParamCount() == keyParams.size()) {
          stream = index.getInternal().getRids(iContext.getDatabaseSession(), keyOne)
              .map((rid) -> new RawPair<>(keyOne, rid));
        } else {
          return null;
        }
      }
    }

    updateProfiler(iContext, index, keyParams);
    return stream;
  }

  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
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
    PropertyType type = null;
    if (iCondition.getLeft() instanceof SQLFilterItemField
        && ((SQLFilterItemField) iCondition.getLeft()).isFieldChain()
        && ((SQLFilterItemField) iCondition.getLeft()).getFieldChain().getItemCount() == 1) {
      var fieldName =
          ((SQLFilterItemField) iCondition.getLeft()).getFieldChain().getItemName(0);
      if (fieldName != null) {
        if (iRecord.isEntity()) {
          SchemaImmutableClass result = null;
          var entity = iRecord.castToEntity();
          result = ((EntityImpl) entity).getImmutableSchemaClass(session);
          var property =
              result
                  .getProperty(session, fieldName);
          if (property != null && property.getType(session).isMultiValue()) {
            type = property.getLinkedType(session);
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
