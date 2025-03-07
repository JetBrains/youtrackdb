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
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CONTAINS operator.
 */
public class QueryOperatorContains extends QueryOperatorEqualityNotNulls {

  public QueryOperatorContains() {
    super("CONTAINS", 5, false);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Identifiable iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    var database = iContext.getDatabase();
    final SQLFilterCondition condition;
    if (iCondition.getLeft() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getLeft();
    } else if (iCondition.getRight() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getRight();
    } else {
      condition = null;
    }

    if (iLeft instanceof Iterable<?>) {

      final Iterable<Object> iterable = (Iterable<Object>) iLeft;

      if (condition != null) {
        // CHECK AGAINST A CONDITION
        for (final Object o : iterable) {
          final Identifiable id;
          if (o instanceof Identifiable) {
            id = (Identifiable) o;
          } else if (o instanceof Map<?, ?>) {
            final Iterator<Object> iter = ((Map<?, Object>) o).values().iterator();
            final Object v = iter.hasNext() ? iter.next() : null;
            if (v instanceof Identifiable) {
              id = (Identifiable) v;
            } else
            // TRANSFORM THE ENTIRE MAP IN A DOCUMENT. PROBABLY HAS BEEN IMPORTED FROM JSON
            {
              id = new EntityImpl((Map) o);
            }

          } else if (o instanceof Iterable<?>) {
            final Iterator<Identifiable> iter = ((Iterable<Identifiable>) o).iterator();
            id = iter.hasNext() ? iter.next() : null;
          } else {
            continue;
          }

          if (condition.evaluate(id, null, iContext) == Boolean.TRUE) {
            return true;
          }
        }
      } else {
        // CHECK AGAINST A SINGLE VALUE
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
        for (final Object o : iterable) {
          if (QueryOperatorEquals.equals(database, iRight, o, type)) {
            return true;
          }
        }
      }
    } else if (iRight instanceof Iterable<?>) {

      // CHECK AGAINST A CONDITION
      final Iterable<Identifiable> iterable = (Iterable<Identifiable>) iRight;

      if (condition != null) {
        for (final Identifiable o : iterable) {
          if (condition.evaluate(o, null, iContext) == Boolean.TRUE) {
            return true;
          }
        }
      } else {
        // CHECK AGAINST A SINGLE VALUE
        for (final Object o : iterable) {
          if (QueryOperatorEquals.equals(database, iLeft, o)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (!(iLeft instanceof SQLFilterCondition) && !(iRight instanceof SQLFilterCondition)) {
      return IndexReuseType.INDEX_METHOD;
    }

    return IndexReuseType.NO_INDEX;
  }

  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    var database = iContext.getDatabase();
    final IndexDefinition indexDefinition = index.getDefinition();

    Stream<RawPair<Object, RID>> stream;
    final IndexInternal internalIndex = index.getInternal();
    if (!internalIndex.canBeUsedInEqualityOperators()) {
      return null;
    }

    if (indexDefinition.getParamCount() == 1) {
      final Object key;
      if (indexDefinition instanceof IndexDefinitionMultiValue) {
        key =
            ((IndexDefinitionMultiValue) indexDefinition)
                .createSingleValue(database, keyParams.get(0));
      } else {
        key = indexDefinition.createValue(database, keyParams);
      }

      if (key == null) {
        return null;
      }

      stream = index.getInternal().getRids(database, key).map((rid) -> new RawPair<>(key, rid));
    } else {
      // in case of composite keys several items can be returned in case of we perform search
      // using part of composite key stored in index.

      final CompositeIndexDefinition compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      final Object keyOne = compositeIndexDefinition.createSingleValue(database, keyParams);

      if (keyOne == null) {
        return null;
      }

      final Object keyTwo = compositeIndexDefinition.createSingleValue(database, keyParams);
      if (internalIndex.hasRangeQuerySupport()) {
        stream = index.getInternal().streamEntriesBetween(database, keyOne, true, keyTwo, true,
            ascSortOrder);
      } else {
        int indexParamCount = indexDefinition.getParamCount();
        if (indexParamCount == keyParams.size()) {
          stream = index.getInternal().getRids(database, keyOne)
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
}
