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
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nullable;

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
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    var session = iContext.getDatabaseSession();
    final SQLFilterCondition condition;
    if (iCondition.getLeft() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getLeft();
    } else if (iCondition.getRight() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getRight();
    } else {
      condition = null;
    }

    if (iLeft instanceof Iterable<?>) {

      final var iterable = (Iterable<Object>) iLeft;
      if (condition != null) {
        // CHECK AGAINST A CONDITION

        for (final var o : iterable) {
          final Result id;
          switch (o) {
            case Identifiable identifiable -> {
              var transaction = session.getActiveTransaction();
              id = transaction.loadEntity(identifiable);
            }
            case Map<?, ?> map -> {
              final var iter = map.values().iterator();
              final var v = iter.hasNext() ? iter.next() : null;
              if (v instanceof Identifiable identifiable) {
                var transaction = session.getActiveTransaction();
                id = transaction.loadEntity(identifiable);
              } else {
                id = new ResultInternal(session, (Map<String, ?>) o);
              }

            }
            case Iterable<?> objects -> {
              final var iter = objects.iterator();
              if (iter.hasNext()) {
                Identifiable identifiable = ((Identifiable) iter.next());
                var transaction = session.getActiveTransaction();
                id = transaction.loadEntity(identifiable);
              } else {
                id = null;
              }
            }
            case null, default -> {
              continue;
            }
          }

          if (condition.evaluate(id, null, iContext) == Boolean.TRUE) {
            return true;
          }
        }
      } else {
        // CHECK AGAINST A SINGLE VALUE
        PropertyTypeInternal type = null;

        if (iCondition.getLeft() instanceof SQLFilterItemField
            && ((SQLFilterItemField) iCondition.getLeft()).isFieldChain()
            && ((SQLFilterItemField) iCondition.getLeft()).getFieldChain().getItemCount() == 1) {
          var fieldName =
              ((SQLFilterItemField) iCondition.getLeft()).getFieldChain().getItemName(0);
          if (fieldName != null) {
            if (iRecord.isEntity()) {
              var entity = iRecord.asEntity();
              var result = ((EntityImpl) entity).getImmutableSchemaClass(session);
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
        for (final var o : iterable) {
          if (QueryOperatorEquals.equals(session, iRight, o, type)) {
            return true;
          }
        }
      }
    } else if (iRight instanceof Iterable<?>) {

      // CHECK AGAINST A CONDITION
      final var iterable = (Iterable<Identifiable>) iRight;

      if (condition != null) {
        for (final var o : iterable) {
          var transaction = session.getActiveTransaction();
          if (condition.evaluate(transaction.loadEntity(o), null, iContext) == Boolean.TRUE) {
            return true;
          }
        }
      } else {
        // CHECK AGAINST A SINGLE VALUE
        for (final Object o : iterable) {
          if (QueryOperatorEquals.equals(session, iLeft, o)) {
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

  @Nullable
  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    var database = iContext.getDatabaseSession();
    final var indexDefinition = index.getDefinition();

    Stream<RawPair<Object, RID>> stream;
    if (!index.canBeUsedInEqualityOperators()) {
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

      stream = index.getRids(database, key).map((rid) -> new RawPair<>(key, rid));
    } else {
      // in case of composite keys several items can be returned in case of we perform search
      // using part of composite key stored in index.

      final var compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      final Object keyOne = compositeIndexDefinition.createSingleValue(database, keyParams);

      if (keyOne == null) {
        return null;
      }

      final Object keyTwo = compositeIndexDefinition.createSingleValue(database, keyParams);
      if (index.hasRangeQuerySupport()) {
        stream = index.streamEntriesBetween(database, keyOne, true, keyTwo, true,
            ascSortOrder);
      } else {
        var indexParamCount = indexDefinition.getParamCount();
        if (indexParamCount == keyParams.size()) {
          stream = index.getRids(database, keyOne)
              .map((rid) -> new RawPair<>(keyOne, rid));
        } else {
          return null;
        }
      }
    }

    updateProfiler(iContext, index, keyParams);
    return stream;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }
}
