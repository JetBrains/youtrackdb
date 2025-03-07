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
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.DocumentHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemParameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * EQUALS operator.
 */
public class QueryOperatorEquals extends QueryOperatorEqualityNotNulls {

  private boolean binaryEvaluate = false;

  public QueryOperatorEquals() {
    super("=", 5, false);
    DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
    if (db != null) {
      binaryEvaluate = db.getSerializer().getSupportBinaryEvaluate();
    }
  }

  public static boolean equals(DatabaseSession session, final Object iLeft, final Object iRight,
      PropertyType type) {
    if (type == null) {
      return equals(session, iLeft, iRight);
    }
    Object left = PropertyType.convert(session, iLeft, type.getDefaultJavaType());
    Object right = PropertyType.convert(session, iRight, type.getDefaultJavaType());
    return equals(session, left, right);
  }

  public static boolean equals(@Nullable DatabaseSession session, Object iLeft, Object iRight) {
    if (iLeft == null || iRight == null) {
      return false;
    }

    if (iLeft == iRight) {
      return true;
    }

    // RECORD & RID
    /*from this is only legacy query engine */
    if (iLeft instanceof DBRecord) {
      return comparesValues(iRight, (DBRecord) iLeft, true);
    } else if (iRight instanceof DBRecord) {
      return comparesValues(iLeft, (DBRecord) iRight, true);
    }
    /*till this is only legacy query engine */
    else if (iRight instanceof Result) {
      return comparesValues(iLeft, (Result) iRight, true);
    } else if (iRight instanceof Result) {
      return comparesValues(iLeft, (Result) iRight, true);
    }

    // NUMBERS
    if (iLeft instanceof Number && iRight instanceof Number) {
      Number[] couple = PropertyType.castComparableNumber((Number) iLeft, (Number) iRight);
      return couple[0].equals(couple[1]);
    }

    // ALL OTHER CASES
    try {
      final Object right = PropertyType.convert(session, iRight, iLeft.getClass());

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

  protected static boolean comparesValues(
      final Object iValue, final DBRecord iRecord, final boolean iConsiderIn) {
    // RID && RECORD
    final RID other = iRecord.getIdentity();

    if (!other.isPersistent() && iRecord instanceof EntityImpl) {
      // ODOCUMENT AS RESULT OF SUB-QUERY: GET THE FIRST FIELD IF ANY
      final Set<String> firstFieldName = ((EntityImpl) iRecord).getPropertyNames();
      if (firstFieldName.size() > 0) {
        Object fieldValue = ((EntityImpl) iRecord).getProperty(firstFieldName.iterator().next());
        if (fieldValue != null) {
          if (iConsiderIn && MultiValue.isMultiValue(fieldValue)) {
            for (Object o : MultiValue.getMultiValueIterable(fieldValue)) {
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
    return other.equals(iValue);
  }

  protected static boolean comparesValues(
      final Object iValue, final Result iRecord, final boolean iConsiderIn) {
    if (iRecord.getIdentity().isPresent() && iRecord.getIdentity().get().isPersistent()) {
      return iRecord.getIdentity().get().equals(iValue);
    } else {
      // ODOCUMENT AS RESULT OF SUB-QUERY: GET THE FIRST FIELD IF ANY
      var firstFieldName = iRecord.getPropertyNames();
      if (firstFieldName.size() == 1) {
        Object fieldValue = iRecord.getProperty(firstFieldName.iterator().next());
        if (fieldValue != null) {
          if (iConsiderIn && MultiValue.isMultiValue(fieldValue)) {
            for (Object o : MultiValue.getMultiValueIterable(fieldValue)) {
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

      if (key == null) {
        return null;
      }

      stream = index.getInternal().getRids(iContext.getDatabase(), key)
          .map((rid) -> new RawPair<>(key, rid));
    } else {
      // in case of composite keys several items can be returned in case of we perform search
      // using part of composite key stored in index.

      final CompositeIndexDefinition compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      final Object keyOne =
          compositeIndexDefinition.createSingleValue(iContext.getDatabase(), keyParams);

      if (keyOne == null) {
        return null;
      }

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
  public RID getBeginRidRange(DatabaseSession session, final Object iLeft,
      final Object iRight) {
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

    if (iRight instanceof SQLFilterItemField
        && DocumentHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iRight).getRoot(session))) {
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
  public RID getEndRidRange(DatabaseSession session, final Object iLeft, final Object iRight) {
    return getBeginRidRange(session, iLeft, iRight);
  }

  @Override
  protected boolean evaluateExpression(
      final Identifiable iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    return equals(iContext.getDatabase(), iLeft, iRight);
  }

  @Override
  public boolean evaluate(
      final BinaryField iFirstField,
      final BinaryField iSecondField,
      CommandContext iContext,
      final EntitySerializer serializer) {
    return serializer.getComparator().isEqual(iFirstField, iSecondField);
  }

  @Override
  public boolean isSupportingBinaryEvaluate() {
    return binaryEvaluate;
  }
}
