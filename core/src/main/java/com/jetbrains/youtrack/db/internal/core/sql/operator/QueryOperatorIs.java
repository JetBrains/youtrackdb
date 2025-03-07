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

import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import java.util.List;
import java.util.stream.Stream;

/**
 * IS operator. Different by EQUALS since works also for null. Example "IS null"
 */
public class QueryOperatorIs extends QueryOperatorEquality {

  public QueryOperatorIs() {
    super("IS", 5, false);
  }

  @Override
  protected boolean evaluateExpression(
      final Identifiable iRecord,
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

  protected boolean evaluateDefined(final Identifiable iRecord, final String iFieldName) {
    if (iRecord instanceof EntityImpl) {
      return ((EntityImpl) iRecord).containsField(iFieldName);
    }
    return false;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (iRight == null) {
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
      final Object key;
      if (indexDefinition instanceof IndexDefinitionMultiValue) {
        key =
            ((IndexDefinitionMultiValue) indexDefinition)
                .createSingleValue(iContext.getDatabase(), keyParams.get(0));
      } else {
        key = indexDefinition.createValue(iContext.getDatabase(), keyParams);
      }

      stream = index.getInternal().getRids(iContext.getDatabase(), key)
          .map((rid) -> new RawPair<>(key, rid));
    } else {
      // in case of composite keys several items can be returned in case we perform search
      // using part of composite key stored in index

      final CompositeIndexDefinition compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      final Object keyOne =
          compositeIndexDefinition.createSingleValue(iContext.getDatabase(), keyParams);
      final Object keyTwo =
          compositeIndexDefinition.createSingleValue(iContext.getDatabase(), keyParams);

      if (internalIndex.hasRangeQuerySupport()) {
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
}
