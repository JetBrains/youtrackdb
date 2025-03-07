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
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.record.impl.DocumentHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemParameter;
import java.util.List;
import java.util.stream.Stream;

/**
 * MINOR EQUALS operator.
 */
public class QueryOperatorMinorEquals extends QueryOperatorEqualityNotNulls {

  private boolean binaryEvaluate = true;

  public QueryOperatorMinorEquals() {
    super("<=", 5, false);
    DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
    if (db != null) {
      binaryEvaluate = db.getSerializer().getSupportBinaryEvaluate();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Identifiable iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    final Object right = PropertyType.convert(iContext.getDatabase(), iRight, iLeft.getClass());
    if (right == null) {
      return false;
    }
    return ((Comparable<Object>) iLeft).compareTo(right) <= 0;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (iRight == null || iLeft == null) {
      return IndexReuseType.NO_INDEX;
    }
    return IndexReuseType.INDEX_METHOD;
  }

  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    final IndexDefinition indexDefinition = index.getDefinition();

    final IndexInternal internalIndex = index.getInternal();
    Stream<RawPair<Object, RID>> stream;
    if (!internalIndex.canBeUsedInEqualityOperators() || !internalIndex.hasRangeQuerySupport()) {
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

      if (key == null) {
        return null;
      }

      stream = index.getInternal()
          .streamEntriesMinor(iContext.getDatabase(), key, true, ascSortOrder);
    } else {
      // if we have situation like "field1 = 1 AND field2 <= 2"
      // then we fetch collection which left included boundary is the smallest composite key in the
      // index that contains key with value field1=1 and which right not included boundary
      // is the biggest composite key in the index that contains key with value field1=1 and
      // field2=2.

      final CompositeIndexDefinition compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      final Object keyOne =
          compositeIndexDefinition.createSingleValue(
              iContext.getDatabase(), keyParams.subList(0, keyParams.size() - 1));

      if (keyOne == null) {
        return null;
      }

      final Object keyTwo =
          compositeIndexDefinition.createSingleValue(iContext.getDatabase(), keyParams);

      if (keyTwo == null) {
        return null;
      }

      stream = index.getInternal()
          .streamEntriesBetween(iContext.getDatabase(), keyOne, true, keyTwo, true, ascSortOrder);
    }

    updateProfiler(iContext, index, keyParams);
    return stream;
  }

  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Override
  public RID getEndRidRange(DatabaseSession session, final Object iLeft, final Object iRight) {
    if (iLeft instanceof SQLFilterItemField
        && DocumentHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      if (iRight instanceof RID) {
        return (RID) iRight;
      } else {
        if (iRight instanceof SQLFilterItemParameter
            && ((SQLFilterItemParameter) iRight).getValue(null, null, null) instanceof RID) {
          return (RID) ((SQLFilterItemParameter) iRight).getValue(null, null, null);
        }
      }
    }

    return null;
  }

  @Override
  public boolean evaluate(
      final BinaryField iFirstField,
      final BinaryField iSecondField,
      CommandContext iContext,
      final EntitySerializer serializer) {
    return serializer.getComparator().compare(iFirstField, iSecondField) <= 0;
  }

  @Override
  public boolean isSupportingBinaryEvaluate() {
    return binaryEvaluate;
  }
}
