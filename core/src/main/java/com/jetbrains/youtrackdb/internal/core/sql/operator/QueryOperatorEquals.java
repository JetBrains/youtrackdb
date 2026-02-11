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
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemParameter;
import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * EQUALS operator.
 */
public class QueryOperatorEquals extends QueryOperatorEqualityNotNulls {

  public QueryOperatorEquals() {
    super("=", 5, false);
  }

  public static boolean equals(DatabaseSessionEmbedded session, final Object iLeft,
      final Object iRight,
      PropertyTypeInternal type) {
    if (type == null) {
      return equals(session, iLeft, iRight);
    }
    var left = PropertyTypeInternal.convert(session, iLeft, type.getDefaultJavaType());
    var right = PropertyTypeInternal.convert(session, iRight, type.getDefaultJavaType());
    return equals(session, left, right);
  }

  public static boolean equals(@Nullable DatabaseSessionEmbedded session, Object iLeft,
      Object iRight) {

    if (iLeft instanceof Collection<?> col && !(iRight instanceof Collection<?>)
        && col.size() == 1) {
      iLeft = col.iterator().next();
    } else if (iRight instanceof Collection<?> col && !(iLeft instanceof Collection<?>)
        && col.size() == 1) {
      iRight = col.iterator().next();
    }

    if (iLeft == null || iRight == null) {
      return false;
    }

    if (iLeft == iRight) {
      return true;
    }

    // RECORD & RID
    /*from this is only legacy query engine */
    if (iLeft instanceof DBRecord) {
      return comparesValues(iRight, (DBRecord) iLeft);
    } else if (iRight instanceof DBRecord) {
      return comparesValues(iLeft, (DBRecord) iRight);
    }
    /*till this is only legacy query engine */
    else if (iRight instanceof Result) {
      return comparesValues(iLeft, (Result) iRight, true);
    } else if (iRight instanceof Result) {
      return comparesValues(iLeft, (Result) iRight, true);
    }

    // NUMBERS
    if (iLeft instanceof Number && iRight instanceof Number) {
      var couple = PropertyTypeInternal.castComparableNumber((Number) iLeft, (Number) iRight);
      return couple[0].equals(couple[1]);
    }

    // ALL OTHER CASES
    try {
      final var right = PropertyTypeInternal.convert(session, iRight, iLeft.getClass());

      if (right == null) {
        return false;
      }
      if (iLeft instanceof byte[] && iRight instanceof byte[]) {
        return Arrays.equals((byte[]) iLeft, (byte[]) iRight);
      }
      return iLeft.equals(right);
    } catch (Exception ignore) {
      return false;
    }
  }

  protected static boolean comparesValues(Object iValue, final DBRecord iRecord) {
    if (iValue instanceof Result result) {
      if (result.isIdentifiable()) {
        iValue = result.asIdentifiable();
      } else {
        return false;
      }
    }

    return iRecord.equals(iValue);
  }

  protected static boolean comparesValues(
      final Object iValue, final Result result, final boolean iConsiderIn) {
    if (result.isIdentifiable() && result.getIdentity().isPersistent()) {
      return result.getIdentity().equals(iValue);
    } else {
      // ODOCUMENT AS RESULT OF SUB-QUERY: GET THE FIRST FIELD IF ANY
      var firstFieldName = result.getPropertyNames();
      if (firstFieldName.size() == 1) {
        var fieldValue = result.getProperty(firstFieldName.iterator().next());
        if (fieldValue != null) {
          if (iConsiderIn && MultiValue.isMultiValue(fieldValue)) {
            for (var o : MultiValue.getMultiValueIterable(fieldValue)) {
              if (o != null && o.equals(iValue)) {
                return true;
              }
            }
          }

          return fieldValue.equals(iValue);
        }
      }

      return false;
    }
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (iLeft instanceof Identifiable && iRight instanceof Identifiable) {
      return IndexReuseType.NO_INDEX;
    }
    if (iRight == null || iLeft == null) {
      return IndexReuseType.NO_INDEX;
    }

    return IndexReuseType.INDEX_METHOD;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSessionEmbedded session, final Object iLeft,
      final Object iRight) {
    if (iLeft instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      if (iRight instanceof RID) {
        return (RID) iRight;
      } else {
        if (iRight instanceof SQLFilterItemParameter
            && ((SQLFilterItemParameter) iRight).getValue(null, null, null) instanceof RID) {
          return (RID) ((SQLFilterItemParameter) iRight).getValue(null, null, null);
        }
      }
    }

    if (iRight instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iRight).getRoot(session))) {
      if (iLeft instanceof RID) {
        return (RID) iLeft;
      } else {
        if (iLeft instanceof SQLFilterItemParameter
            && ((SQLFilterItemParameter) iLeft).getValue(null, null, null) instanceof RID) {
          return (RID) ((SQLFilterItemParameter) iLeft).getValue(null, null, null);
        }
      }
    }

    return null;
  }

  @Override
  public RID getEndRidRange(DatabaseSessionEmbedded session, final Object iLeft,
      final Object iRight) {
    return getBeginRidRange(session, iLeft, iRight);
  }

  @Override
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    return equals(iContext.getDatabaseSession(), iLeft, iRight);
  }

  @Override
  public boolean evaluate(
      final BinaryField firstField,
      final BinaryField secondField,
      CommandContext context,
      final EntitySerializer serializer) {
    return serializer.getComparator()
        .isEqual(context.getDatabaseSession(), firstField, secondField);
  }

  @Override
  public boolean isSupportingBinaryEvaluate() {
    return true;
  }
}
