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

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.QueryRuntimeValueMulti;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemFieldAny;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * TRAVERSE operator.
 */
public class QueryOperatorTraverse extends QueryOperatorEqualityNotNulls {

  private int startDeepLevel = 0; // FIRST
  private int endDeepLevel = -1; // INFINITE
  private String[] cfgFields;

  public QueryOperatorTraverse() {
    super("TRAVERSE", 5, false, 1, true);
  }

  public QueryOperatorTraverse(
      final int startDeepLevel, final int endDeepLevel, final String[] iFieldList) {
    this();
    this.startDeepLevel = startDeepLevel;
    this.endDeepLevel = endDeepLevel;
    this.cfgFields = iFieldList;
  }

  @Override
  public String getSyntax() {
    return "<left> TRAVERSE[(<begin-deep-level> [,<maximum-deep-level> [,<fields>]] )] ("
        + " <conditions> )";
  }

  @Override
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      final CommandContext iContext) {
    final SQLFilterCondition condition;
    final Object target;

    if (iCondition.getLeft() instanceof SQLFilterCondition) {
      condition = (SQLFilterCondition) iCondition.getLeft();
      target = iRight;
    } else {
      condition = (SQLFilterCondition) iCondition.getRight();
      target = iLeft;
    }

    final Set<RID> evaluatedRecords = new HashSet<RID>();
    return traverse(target, condition, 0, evaluatedRecords, iContext);
  }

  @SuppressWarnings("unchecked")
  private boolean traverse(
      Object iTarget,
      final SQLFilterCondition iCondition,
      final int iLevel,
      final Set<RID> iEvaluatedRecords,
      final CommandContext iContext) {
    if (endDeepLevel > -1 && iLevel > endDeepLevel) {
      return false;
    }

    if (iTarget instanceof Identifiable) {
      if (iEvaluatedRecords.contains(((Identifiable) iTarget).getIdentity()))
      // ALREADY EVALUATED
      {
        return false;
      }

      // TRANSFORM THE RID IN ODOCUMENT
      var transaction = iContext.getDatabaseSession().getActiveTransaction();
      iTarget = transaction.load(((Identifiable) iTarget));
    }

    if (iTarget instanceof EntityImpl target) {

      iEvaluatedRecords.add(target.getIdentity());

      if (iLevel >= startDeepLevel && iCondition.evaluate(target, null, iContext) == Boolean.TRUE) {
        return true;
      }

      // TRAVERSE THE DOCUMENT ITSELF
      if (cfgFields != null) {
        for (final var cfgField : cfgFields) {
          if (cfgField.equalsIgnoreCase(SQLFilterItemFieldAny.FULL_NAME)) {
            // ANY
            for (final var fieldName : target.propertyNames()) {
              if (traverse(
                  target.getProperty(fieldName),
                  iCondition,
                  iLevel + 1,
                  iEvaluatedRecords,
                  iContext)) {
                return true;
              }
            }
          } else if (cfgField.equalsIgnoreCase(SQLFilterItemFieldAny.FULL_NAME)) {
            // ALL
            for (final var fieldName : target.propertyNames()) {
              if (!traverse(
                  target.getProperty(fieldName),
                  iCondition,
                  iLevel + 1,
                  iEvaluatedRecords,
                  iContext)) {
                return false;
              }
            }
            return true;
          } else {
            if (traverse(
                target.getProperty(cfgField), iCondition, iLevel + 1, iEvaluatedRecords,
                iContext)) {
              return true;
            }
          }
        }
      }

    } else if (iTarget instanceof QueryRuntimeValueMulti multi) {

      for (final var o : multi.getValues()) {
        if (traverse(o, iCondition, iLevel + 1, iEvaluatedRecords, iContext) == Boolean.TRUE) {
          return true;
        }
      }
    } else if (iTarget instanceof Map<?, ?>) {

      final var map = (Map<Object, Object>) iTarget;
      for (final var o : map.values()) {
        if (traverse(o, iCondition, iLevel + 1, iEvaluatedRecords, iContext) == Boolean.TRUE) {
          return true;
        }
      }
    } else if (MultiValue.isMultiValue(iTarget)) {
      final var collection = MultiValue.getMultiValueIterable(iTarget);
      for (final var o : collection) {
        if (traverse(o, iCondition, iLevel + 1, iEvaluatedRecords, iContext) == Boolean.TRUE) {
          return true;
        }
      }
    } else if (iTarget instanceof Iterator iterator) {
      while (iterator.hasNext()) {
        if (traverse(iterator.next(), iCondition, iLevel + 1, iEvaluatedRecords, iContext)
            == Boolean.TRUE) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public QueryOperator configure(final List<String> iParams) {
    if (iParams == null) {
      return this;
    }

    final var start = !iParams.isEmpty() ? Integer.parseInt(iParams.get(0)) : startDeepLevel;
    final var end = iParams.size() > 1 ? Integer.parseInt(iParams.get(1)) : endDeepLevel;

    var fields = new String[]{"any()"};
    if (iParams.size() > 2) {
      var f = iParams.get(2);
      if (f.startsWith("'") || f.startsWith("\"")) {
        f = f.substring(1, f.length() - 1);
      }
      fields = f.split(",");
    }

    return new QueryOperatorTraverse(start, end, fields);
  }

  public int getStartDeepLevel() {
    return startDeepLevel;
  }

  public int getEndDeepLevel() {
    return endDeepLevel;
  }

  public String[] getCfgFields() {
    return cfgFields;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.NO_INDEX;
  }

  @Override
  public String toString() {
    return String.format(
        "%s(%d,%d,%s)", keyword, startDeepLevel, endDeepLevel, Arrays.toString(cfgFields));
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
}
