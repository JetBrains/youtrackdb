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
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * IS operator. Different by EQUALS since works also for null. Example "IS null"
 */
public class QueryOperatorIs extends QueryOperatorEquality {

  public QueryOperatorIs() {
    super("IS", 5, false);
  }

  @Override
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      Object iRight,
      CommandContext iContext) {
    if (iCondition.getLeft() instanceof SQLFilterItemField) {
      if (SQLHelper.DEFINED.equals(iCondition.getRight())) {
        return evaluateDefined(iRecord, "" + iCondition.getLeft());
      }

      if (iCondition.getRight() instanceof SQLFilterItemField
          && "not defined".equalsIgnoreCase("" + iCondition.getRight())) {
        return !evaluateDefined(iRecord, "" + iCondition.getLeft());
      }
    }

    if (SQLHelper.NOT_NULL.equals(iRight)) {
      return iLeft != null;
    } else if (SQLHelper.NOT_NULL.equals(iLeft)) {
      return iRight != null;
    } else if (SQLHelper.DEFINED.equals(iLeft)) {
      return evaluateDefined(iRecord, (String) iRight);
    } else if (SQLHelper.DEFINED.equals(iRight)) {
      return evaluateDefined(iRecord, (String) iLeft);
    } else {
      return iLeft == iRight;
    }
  }

  protected static boolean evaluateDefined(final Result iRecord, final String iFieldName) {
    return iRecord.getProperty(iFieldName) != null;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (iRight == null) {
      return IndexReuseType.INDEX_METHOD;
    }

    return IndexReuseType.NO_INDEX;
  }

  @Nullable
  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {

    final var indexDefinition = index.getDefinition();

    Stream<RawPair<Object, RID>> stream;
    if (!index.canBeUsedInEqualityOperators()) {
      return null;
    }

    var transaction = iContext.getDatabaseSession().getActiveTransaction();
    if (indexDefinition.getParamCount() == 1) {
      final Object key;
      if (indexDefinition instanceof IndexDefinitionMultiValue) {
        key =
            ((IndexDefinitionMultiValue) indexDefinition)
                .createSingleValue(transaction, keyParams.get(0));
      } else {
        key = indexDefinition.createValue(transaction, keyParams);
      }

      stream = index.getRids(iContext.getDatabaseSession(), key)
          .map((rid) -> new RawPair<>(key, rid));
    } else {
      // in case of composite keys several items can be returned in case we perform search
      // using part of composite key stored in index

      final var compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      final Object keyOne =
          compositeIndexDefinition.createSingleValue(transaction, keyParams);
      final Object keyTwo =
          compositeIndexDefinition.createSingleValue(transaction, keyParams);

      if (index.hasRangeQuerySupport()) {
        stream = index.streamEntriesBetween(iContext.getDatabaseSession(), keyOne, true, keyTwo,
            true,
            ascSortOrder);
      } else {
        if (indexDefinition.getParamCount() == keyParams.size()) {
          stream = index.getRids(iContext.getDatabaseSession(), keyOne)
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
