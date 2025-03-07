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
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
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
    final IndexDefinition indexDefinition = index.getDefinition();

    final IndexInternal internalIndex = index.getInternal();
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

      final Object key =
          ((IndexDefinitionMultiValue) indexDefinition)
              .createSingleValue(iContext.getDatabase(), keyParams.get(0));

      if (key == null) {
        return null;
      }

      stream = index.getInternal().getRids(iContext.getDatabase(), key)
          .map((rid) -> new RawPair<>(key, rid));
    } else {
      // in case of composite keys several items can be returned in case of we perform search
      // using part of composite key stored in index.
      final CompositeIndexDefinition compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      if (!((compositeIndexDefinition.getMultiValueDefinition()
          instanceof PropertyMapIndexDefinition)
          && ((PropertyMapIndexDefinition) compositeIndexDefinition.getMultiValueDefinition())
          .getIndexBy()
          == PropertyMapIndexDefinition.INDEX_BY.VALUE)) {
        return null;
      }

      final Object keyOne =
          compositeIndexDefinition.createSingleValue(iContext.getDatabase(), keyParams);

      if (keyOne == null) {
        return null;
      }

      if (internalIndex.hasRangeQuerySupport()) {
        final Object keyTwo =
            compositeIndexDefinition.createSingleValue(iContext.getDatabase(), keyParams);

        stream = index.getInternal()
            .streamEntriesBetween(iContext.getDatabase(), keyOne, true, keyTwo, true,
                ascSortOrder);
      } else {
        if (indexDefinition.getParamCount() == keyParams.size()) {
          stream = index.getInternal().getRids(iContext.getDatabase(), keyOne)
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
      final Identifiable iRecord,
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

    PropertyType type = null;
    if (iCondition.getLeft() instanceof SQLFilterItemField
        && ((SQLFilterItemField) iCondition.getLeft()).isFieldChain()
        && ((SQLFilterItemField) iCondition.getLeft()).getFieldChain().getItemCount() == 1) {
      String fieldName =
          ((SQLFilterItemField) iCondition.getLeft()).getFieldChain().getItemName(0);
      if (fieldName != null) {
        Object record = iRecord.getRecord();
        if (record instanceof EntityImpl) {
          SchemaProperty property =
              EntityInternalUtils.getImmutableSchemaClass(((EntityImpl) record))
                  .getProperty(fieldName);
          if (property != null && property.getType().isMultiValue()) {
            type = property.getLinkedType();
          }
        }
      }
    }

    if (type != null) {
      iRight = PropertyType.convert(iContext.getDatabase(), iRight, type.getDefaultJavaType());
    }

    if (iLeft instanceof Map<?, ?>) {
      final Map<String, ?> map = (Map<String, ?>) iLeft;

      if (condition != null) {
        // CHECK AGAINST A CONDITION
        for (Object o : map.values()) {
          if ((Boolean) condition.evaluate((EntityImpl) o, null, iContext)) {
            return true;
          }
        }
      } else {
        for (Object val : map.values()) {
          Object convertedRight = iRight;
          if (val instanceof EntityImpl && iRight instanceof Map) {
            val = ((EntityImpl) val).toMap();
          }
          if (val instanceof Map && iRight instanceof EntityImpl) {
            convertedRight = ((EntityImpl) iRight).toMap();
          }
          if (QueryOperatorEquals.equals(iContext.getDatabase(), val, convertedRight)) {
            return true;
          }
        }
        return false;
      }

    } else if (iRight instanceof Map<?, ?>) {
      final Map<String, ?> map = (Map<String, ?>) iRight;

      if (condition != null)
      // CHECK AGAINST A CONDITION
      {
        for (Object o : map.values()) {
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
