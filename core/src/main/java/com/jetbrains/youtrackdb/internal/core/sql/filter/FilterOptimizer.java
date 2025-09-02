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

package com.jetbrains.youtrackdb.internal.core.sql.filter;

import com.jetbrains.youtrackdb.internal.core.sql.IndexSearchResult;
import com.jetbrains.youtrackdb.internal.core.sql.SQLHelper;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorEquals;
import javax.annotation.Nullable;

/**
 *
 */
public class FilterOptimizer {

  public void optimize(SQLFilter filter, IndexSearchResult indexMatch) {
    filter.setRootCondition(optimize(filter.getRootCondition(), indexMatch));
  }

  @Nullable
  private SQLFilterCondition optimize(
      SQLFilterCondition condition, IndexSearchResult indexMatch) {
    if (condition == null) {
      return null;
    }
    var operator = condition.getOperator();
    while (operator == null) {
      if (condition.getRight() == null && condition.getLeft() instanceof SQLFilterCondition) {
        condition = (SQLFilterCondition) condition.getLeft();
        operator = condition.getOperator();
      } else {
        return condition;
      }
    }

    final var reuseType =
        operator.getIndexReuseType(condition.getLeft(), condition.getRight());
    switch (reuseType) {
      case INDEX_METHOD:
        if (isCovered(indexMatch, operator, condition.getLeft(), condition.getRight())
            || isCovered(indexMatch, operator, condition.getRight(), condition.getLeft())) {
          return null;
        }
        return condition;

      case INDEX_INTERSECTION:
        if (condition.getLeft() instanceof SQLFilterCondition) {
          condition.setLeft(optimize((SQLFilterCondition) condition.getLeft(), indexMatch));
        }

        if (condition.getRight() instanceof SQLFilterCondition) {
          condition.setRight(optimize((SQLFilterCondition) condition.getRight(), indexMatch));
        }

        if (condition.getLeft() == null) {
          return (SQLFilterCondition) condition.getRight();
        }
        if (condition.getRight() == null) {
          return (SQLFilterCondition) condition.getLeft();
        }
        return condition;

      case INDEX_OPERATOR:
        if (isCovered(indexMatch, operator, condition.getLeft(), condition.getRight())
            || isCovered(indexMatch, operator, condition.getRight(), condition.getLeft())) {
          return null;
        }
        return condition;
      default:
        return condition;
    }
  }

  private boolean isCovered(
      IndexSearchResult indexMatch,
      QueryOperator operator,
      Object fieldCandidate,
      Object valueCandidate) {
    if (fieldCandidate instanceof SQLFilterItemField field) {
      if (operator instanceof QueryOperatorEquals) {
        for (var e : indexMatch.fieldValuePairs.entrySet()) {
          if (isSameField(field, e.getKey()) && isSameValue(valueCandidate, e.getValue())) {
            return true;
          }
        }
      }

      return operator.equals(indexMatch.lastOperator)
          && isSameField(field, indexMatch.lastField)
          && isSameValue(valueCandidate, indexMatch.lastValue);
    }
    return false;
  }

  private boolean isSameValue(Object valueCandidate, Object lastValue) {
    if (lastValue == null || valueCandidate == null) {
      return lastValue == null && valueCandidate == null;
    }

    return lastValue.equals(valueCandidate)
        || lastValue.equals(SQLHelper.getValue(valueCandidate));
  }

  private boolean isSameField(
      SQLFilterItemField field, SQLFilterItemField.FieldChain fieldChain) {
    return fieldChain.belongsTo(field);
  }

  private boolean isSameField(SQLFilterItemField field, String fieldName) {
    return !field.hasChainOperators() && fieldName.equals(field.name);
  }
}
