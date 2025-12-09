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
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import java.util.Map;
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
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }
}
