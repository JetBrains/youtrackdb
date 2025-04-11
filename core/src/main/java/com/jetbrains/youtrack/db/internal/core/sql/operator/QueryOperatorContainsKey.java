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
import com.jetbrains.youtrack.db.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * CONTAINS KEY operator.
 */
public class QueryOperatorContainsKey extends QueryOperatorEqualityNotNulls {

  public QueryOperatorContainsKey() {
    super("CONTAINSKEY", 5, false);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {

    if (iLeft instanceof Map<?, ?>) {

      final var map = (Map<String, ?>) iLeft;
      return map.containsKey(iRight);
    } else if (iRight instanceof Map<?, ?>) {

      final var map = (Map<String, ?>) iRight;
      return map.containsKey(iLeft);
    }
    return false;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.INDEX_METHOD;
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
      if (!((indexDefinition instanceof PropertyMapIndexDefinition)
          && ((PropertyMapIndexDefinition) indexDefinition).getIndexBy()
          == PropertyMapIndexDefinition.INDEX_BY.KEY)) {
        return null;
      }

      final var key =
          ((IndexDefinitionMultiValue) indexDefinition)
              .createSingleValue(transaction, keyParams.get(0));

      if (key == null) {
        return null;
      }

      stream = index.getRids(iContext.getDatabaseSession(), key)
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
          == PropertyMapIndexDefinition.INDEX_BY.KEY)) {
        return null;
      }

      final Object keyOne =
          compositeIndexDefinition.createSingleValue(transaction, keyParams);

      if (keyOne == null) {
        return null;
      }

      if (index.hasRangeQuerySupport()) {
        final Object keyTwo =
            compositeIndexDefinition.createSingleValue(transaction, keyParams);
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
