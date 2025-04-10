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
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;

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
      final Result iRecord,
      final SQLFilterCondition condition,
      final Object left,
      final Object right,
      CommandContext iContext) {
    validate(right);

    final var valueIterator = MultiValue.getMultiValueIterator(right);

    var database = iContext.getDatabaseSession();
    var right1 = valueIterator.next();
    valueIterator.next();
    var right2 = valueIterator.next();
    final var right1c = PropertyTypeInternal.convert(database, right1, left.getClass());
    if (right1c == null) {
      return false;
    }

    final var right2c = PropertyTypeInternal.convert(database, right2, left.getClass());
    if (right2c == null) {
      return false;
    }

    final int leftResult;
    if (left instanceof Number && right1 instanceof Number) {
      var conv = PropertyTypeInternal.castComparableNumber((Number) left, (Number) right1);
      leftResult = ((Comparable) conv[0]).compareTo(conv[1]);
    } else {
      leftResult = ((Comparable<Object>) left).compareTo(right1c);
    }
    final int rightResult;
    if (left instanceof Number && right2 instanceof Number) {
      var conv = PropertyTypeInternal.castComparableNumber((Number) left, (Number) right2);
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

  @Nullable
  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    final var indexDefinition = index.getDefinition();

    var session = iContext.getDatabaseSession();
    Stream<RawPair<Object, RID>> stream;
    if (!index.canBeUsedInEqualityOperators() || !index.hasRangeQuerySupport()) {
      return null;
    }

    var transaction = session.getActiveTransaction();
    if (indexDefinition.getParamCount() == 1) {
      final var betweenKeys = (Object[]) keyParams.get(0);

      final var keyOne =
          indexDefinition.createValue(
              transaction, Collections.singletonList(SQLHelper.getValue(betweenKeys[0])));
      final var keyTwo =
          indexDefinition.createValue(
              transaction, Collections.singletonList(SQLHelper.getValue(betweenKeys[2])));

      if (keyOne == null || keyTwo == null) {
        return null;
      }

      stream =
          index
              .streamEntriesBetween(session, keyOne, leftInclusive, keyTwo, rightInclusive,
                  ascSortOrder);
    } else {
      final var compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      final var betweenKeys = (Object[]) keyParams.get(keyParams.size() - 1);

      final var betweenKeyOne = SQLHelper.getValue(betweenKeys[0]);

      if (betweenKeyOne == null) {
        return null;
      }

      final var betweenKeyTwo = SQLHelper.getValue(betweenKeys[2]);

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
          compositeIndexDefinition.createSingleValue(transaction, betweenKeyOneParams);

      if (keyOne == null) {
        return null;
      }

      final Object keyTwo =
          compositeIndexDefinition.createSingleValue(transaction, betweenKeyTwoParams);

      if (keyTwo == null) {
        return null;
      }

      stream = index.streamEntriesBetween(session, keyOne, leftInclusive, keyTwo, rightInclusive,
          ascSortOrder);
    }

    updateProfiler(iContext, index, keyParams);
    return stream;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSession session, final Object iLeft,
      final Object iRight) {
    validate(iRight);

    if (iLeft instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      final var valueIterator = MultiValue.getMultiValueIterator(iRight);

      final var right1 = valueIterator.next();
      if (right1 != null) {
        return (RID) right1;
      }

      valueIterator.next();

      return (RID) valueIterator.next();
    }

    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, final Object iLeft, final Object iRight) {
    validate(iRight);

    validate(iRight);

    if (iLeft instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      final var valueIterator = MultiValue.getMultiValueIterator(iRight);

      final var right1 = valueIterator.next();

      valueIterator.next();

      final var right2 = valueIterator.next();

      if (right2 == null) {
        return (RID) right1;
      }

      return (RID) right2;
    }

    return null;
  }
}
