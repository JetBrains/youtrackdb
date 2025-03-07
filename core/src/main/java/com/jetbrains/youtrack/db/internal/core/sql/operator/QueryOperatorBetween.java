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

import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.record.impl.DocumentHelper;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * BETWEEN operator.
 */
public class QueryOperatorBetween extends QueryOperatorEqualityNotNulls {

  private boolean leftInclusive = true;
  private boolean rightInclusive = true;

  public QueryOperatorBetween() {
    super("BETWEEN", 5, false, 3);
  }

  public boolean isLeftInclusive() {
    return leftInclusive;
  }

  public void setLeftInclusive(boolean leftInclusive) {
    this.leftInclusive = leftInclusive;
  }

  public boolean isRightInclusive() {
    return rightInclusive;
  }

  public void setRightInclusive(boolean rightInclusive) {
    this.rightInclusive = rightInclusive;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Identifiable iRecord,
      final SQLFilterCondition condition,
      final Object left,
      final Object right,
      CommandContext iContext) {
    validate(right);

    final Iterator<?> valueIterator = MultiValue.getMultiValueIterator(right);

    var database = iContext.getDatabase();
    Object right1 = valueIterator.next();
    valueIterator.next();
    Object right2 = valueIterator.next();
    final Object right1c = PropertyType.convert(database, right1, left.getClass());
    if (right1c == null) {
      return false;
    }

    final Object right2c = PropertyType.convert(database, right2, left.getClass());
    if (right2c == null) {
      return false;
    }

    final int leftResult;
    if (left instanceof Number && right1 instanceof Number) {
      Number[] conv = PropertyType.castComparableNumber((Number) left, (Number) right1);
      leftResult = ((Comparable) conv[0]).compareTo(conv[1]);
    } else {
      leftResult = ((Comparable<Object>) left).compareTo(right1c);
    }
    final int rightResult;
    if (left instanceof Number && right2 instanceof Number) {
      Number[] conv = PropertyType.castComparableNumber((Number) left, (Number) right2);
      rightResult = ((Comparable) conv[0]).compareTo(conv[1]);
    } else {
      rightResult = ((Comparable<Object>) left).compareTo(right2c);
    }

    return (leftInclusive ? leftResult >= 0 : leftResult > 0)
        && (rightInclusive ? rightResult <= 0 : rightResult < 0);
  }

  private void validate(Object iRight) {
    if (!MultiValue.isMultiValue(iRight.getClass())) {
      throw new IllegalArgumentException(
          "Found '" + iRight + "' while was expected: " + getSyntax());
    }

    if (MultiValue.getSize(iRight) != 3) {
      throw new IllegalArgumentException(
          "Found '" + MultiValue.toString(iRight) + "' while was expected: " + getSyntax());
    }
  }

  @Override
  public String getSyntax() {
    return "<left> " + keyword + " <minRange> AND <maxRange>";
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.INDEX_METHOD;
  }

  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    final IndexDefinition indexDefinition = index.getDefinition();

    var database = iContext.getDatabase();
    Stream<RawPair<Object, RID>> stream;
    final IndexInternal internalIndex = index.getInternal();
    if (!internalIndex.canBeUsedInEqualityOperators() || !internalIndex.hasRangeQuerySupport()) {
      return null;
    }

    if (indexDefinition.getParamCount() == 1) {
      final Object[] betweenKeys = (Object[]) keyParams.get(0);

      final Object keyOne =
          indexDefinition.createValue(
              database, Collections.singletonList(SQLHelper.getValue(betweenKeys[0])));
      final Object keyTwo =
          indexDefinition.createValue(
              database, Collections.singletonList(SQLHelper.getValue(betweenKeys[2])));

      if (keyOne == null || keyTwo == null) {
        return null;
      }

      stream =
          index
              .getInternal()
              .streamEntriesBetween(database, keyOne, leftInclusive, keyTwo, rightInclusive,
                  ascSortOrder);
    } else {
      final CompositeIndexDefinition compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      final Object[] betweenKeys = (Object[]) keyParams.get(keyParams.size() - 1);

      final Object betweenKeyOne = SQLHelper.getValue(betweenKeys[0]);

      if (betweenKeyOne == null) {
        return null;
      }

      final Object betweenKeyTwo = SQLHelper.getValue(betweenKeys[2]);

      if (betweenKeyTwo == null) {
        return null;
      }

      final List<Object> betweenKeyOneParams = new ArrayList<>(keyParams.size());
      betweenKeyOneParams.addAll(keyParams.subList(0, keyParams.size() - 1));
      betweenKeyOneParams.add(betweenKeyOne);

      final List<Object> betweenKeyTwoParams = new ArrayList<>(keyParams.size());
      betweenKeyTwoParams.addAll(keyParams.subList(0, keyParams.size() - 1));
      betweenKeyTwoParams.add(betweenKeyTwo);

      final Object keyOne =
          compositeIndexDefinition.createSingleValue(database, betweenKeyOneParams);

      if (keyOne == null) {
        return null;
      }

      final Object keyTwo =
          compositeIndexDefinition.createSingleValue(database, betweenKeyTwoParams);

      if (keyTwo == null) {
        return null;
      }

      stream =
          index
              .getInternal()
              .streamEntriesBetween(database, keyOne, leftInclusive, keyTwo, rightInclusive,
                  ascSortOrder);
    }

    updateProfiler(iContext, index, keyParams);
    return stream;
  }

  @Override
  public RID getBeginRidRange(DatabaseSession session, final Object iLeft,
      final Object iRight) {
    validate(iRight);

    if (iLeft instanceof SQLFilterItemField
        && DocumentHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      final Iterator<?> valueIterator = MultiValue.getMultiValueIterator(iRight);

      final Object right1 = valueIterator.next();
      if (right1 != null) {
        return (RID) right1;
      }

      valueIterator.next();

      return (RID) valueIterator.next();
    }

    return null;
  }

  @Override
  public RID getEndRidRange(DatabaseSession session, final Object iLeft, final Object iRight) {
    validate(iRight);

    validate(iRight);

    if (iLeft instanceof SQLFilterItemField
        && DocumentHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      final Iterator<?> valueIterator = MultiValue.getMultiValueIterator(iRight);

      final Object right1 = valueIterator.next();

      valueIterator.next();

      final Object right2 = valueIterator.next();

      if (right2 == null) {
        return (RID) right1;
      }

      return (RID) right2;
    }

    return null;
  }
}
