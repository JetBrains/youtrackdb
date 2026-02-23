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
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemParameter;
import javax.annotation.Nullable;

/**
 * MINOR EQUALS operator.
 */
public class QueryOperatorMinorEquals extends QueryOperatorEqualityNotNulls {

  public QueryOperatorMinorEquals() {
    super("<=", 5, false);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    final var right = PropertyTypeInternal.convert(iContext.getDatabaseSession(), iRight,
        iLeft.getClass());
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

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSessionEmbedded session, Object iLeft, Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSessionEmbedded session, final Object iLeft,
      final Object iRight) {
    if (iLeft instanceof SQLFilterItemField field
        && EntityHelper.ATTRIBUTE_RID.equals(field.getRoot(session))) {
      if (iRight instanceof RID rid) {
        return rid;
      } else {
        if (iRight instanceof SQLFilterItemParameter param
            && param.getValue(null, null, null) instanceof RID rid) {
          return rid;
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
    return
        serializer.getComparator().compare(iContext.getDatabaseSession(), iFirstField, iSecondField)
            <= 0;
  }

  @Override
  public boolean isSupportingBinaryEvaluate() {
    return true;
  }
}
