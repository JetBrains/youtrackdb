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
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
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
    if (left instanceof Number leftNum && right1 instanceof Number right1Num) {
      var conv = PropertyTypeInternal.castComparableNumber(leftNum, right1Num);
      leftResult = ((Comparable) conv[0]).compareTo(conv[1]);
    } else {
      leftResult = ((Comparable<Object>) left).compareTo(right1c);
    }
    final int rightResult;
    if (left instanceof Number leftNum && right2 instanceof Number right2Num) {
      var conv = PropertyTypeInternal.castComparableNumber(leftNum, right2Num);
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
  public RID getBeginRidRange(DatabaseSessionEmbedded session, final Object iLeft,
      final Object iRight) {
    validate(iRight);

    if (iLeft instanceof SQLFilterItemField field
        && EntityHelper.ATTRIBUTE_RID.equals(field.getRoot(session))) {
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
  public RID getEndRidRange(DatabaseSessionEmbedded session, final Object iLeft,
      final Object iRight) {
    validate(iRight);

    validate(iRight);

    if (iLeft instanceof SQLFilterItemField field
        && EntityHelper.ATTRIBUTE_RID.equals(field.getRoot(session))) {
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
