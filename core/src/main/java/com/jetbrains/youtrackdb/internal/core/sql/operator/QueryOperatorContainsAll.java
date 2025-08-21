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

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * CONTAINS ALL operator.
 */
public class QueryOperatorContainsAll extends QueryOperatorEqualityNotNulls {

  public QueryOperatorContainsAll() {
    super("CONTAINSALL", 5, false);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    final SQLFilterCondition condition;

    var database = iContext.getDatabaseSession();
    if (iCondition.getLeft() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getLeft();
    } else if (iCondition.getRight() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getRight();
    } else {
      condition = null;
    }

    if (iLeft.getClass().isArray()) {
      if (iRight.getClass().isArray()) {
        // ARRAY VS ARRAY
        var matches = 0;
        for (final var l : (Object[]) iLeft) {
          for (final var r : (Object[]) iRight) {
            if (QueryOperatorEquals.equals(database, l, r)) {
              ++matches;
              break;
            }
          }
        }
        return matches == ((Object[]) iRight).length;
      } else if (iRight instanceof Collection<?>) {
        // ARRAY VS ARRAY
        var matches = 0;
        for (final var l : (Object[]) iLeft) {
          for (final var r : (Collection<?>) iRight) {
            if (QueryOperatorEquals.equals(database, l, r)) {
              ++matches;
              break;
            }
          }
        }
        return matches == ((Collection<?>) iRight).size();
      }

    } else if (iLeft instanceof Collection<?>) {

      final var collection = (Collection<EntityImpl>) iLeft;

      if (condition != null) {
        // CHECK AGAINST A CONDITION
        for (final var o : collection) {
          if (condition.evaluate(o, null, iContext) == Boolean.FALSE) {
            return false;
          }
        }
      } else {
        // CHECK AGAINST A SINGLE VALUE
        for (final Object o : collection) {
          if (!QueryOperatorEquals.equals(database, iRight, o)) {
            return false;
          }
        }
      }
    } else if (iRight instanceof Collection<?>) {

      // CHECK AGAINST A CONDITION
      final var collection = (Collection<EntityImpl>) iRight;

      if (condition != null) {
        for (final var o : collection) {
          if (condition.evaluate(o, null, iContext) == Boolean.FALSE) {
            return false;
          }
        }
      } else {
        // CHECK AGAINST A SINGLE VALUE
        for (final Object o : collection) {
          if (!QueryOperatorEquals.equals(database, iLeft, o)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
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
