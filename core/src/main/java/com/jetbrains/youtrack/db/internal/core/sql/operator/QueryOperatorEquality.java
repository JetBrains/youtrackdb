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

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.query.QueryRuntimeValueMulti;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemFieldAll;

/**
 * Base equality operator. It's an abstract class able to compare the equality between two values.
 */
public abstract class QueryOperatorEquality extends QueryOperator {

  protected QueryOperatorEquality(
      final String iKeyword, final int iPrecedence, final boolean iLogical) {
    super(iKeyword, iPrecedence, false);
  }

  protected QueryOperatorEquality(
      final String iKeyword,
      final int iPrecedence,
      final boolean iLogical,
      final int iExpectedRightWords) {
    super(iKeyword, iPrecedence, false, iExpectedRightWords);
  }

  protected QueryOperatorEquality(
      final String iKeyword,
      final int iPrecedence,
      final boolean iLogical,
      final int iExpectedRightWords,
      final boolean iExpectsParameters) {
    super(iKeyword, iPrecedence, iLogical, iExpectedRightWords, iExpectsParameters);
  }

  protected abstract boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext);

  public boolean evaluate(
      final BinaryField iFirstField,
      final BinaryField iSecondField,
      final CommandContext iContext,
      final EntitySerializer serializer) {
    final var left = serializer.deserializeValue(iContext.getDatabaseSession(), iFirstField.bytes,
        iFirstField.type, null);
    final var right = serializer.deserializeValue(iContext.getDatabaseSession(), iSecondField.bytes,
        iFirstField.type, null);

    return evaluateExpression(null, null, left, right, iContext);
  }

  @Override
  public Object evaluateRecord(
      final Result iRecord,
      EntityImpl iCurrentResult,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext,
      final EntitySerializer serializer) {

    if (iLeft instanceof BinaryField && iRight instanceof BinaryField)
    // BINARY COMPARISON
    {
      return evaluate((BinaryField) iLeft, (BinaryField) iRight, iContext, serializer);
    } else if (iLeft instanceof QueryRuntimeValueMulti left) {
      // LEFT = MULTI

      if (left.getValues().length == 0) {
        return false;
      }

      if (left.getDefinition().getRoot(iContext.getDatabaseSession())
          .startsWith(SQLFilterItemFieldAll.NAME)) {
        // ALL VALUES
        for (var i = 0; i < left.getValues().length; ++i) {
          var v = left.getValues()[i];
          var r = iRight;

          final var collate = left.getCollate(i);
          if (collate != null) {
            v = collate.transform(v);
            r = collate.transform(iRight);
          }

          if (v == null || !evaluateExpression(iRecord, iCondition, v, r, iContext)) {
            return false;
          }
        }
        return true;
      } else {
        // ANY VALUES
        for (var i = 0; i < left.getValues().length; ++i) {
          var v = left.getValues()[i];
          var r = iRight;

          final var collate = left.getCollate(i);
          if (collate != null) {
            v = collate.transform(v);
            r = collate.transform(iRight);
          }

          if (v != null && evaluateExpression(iRecord, iCondition, v, r, iContext)) {
            return true;
          }
        }
        return false;
      }

    } else if (iRight instanceof QueryRuntimeValueMulti right) {
      // RIGHT = MULTI

      if (right.getValues().length == 0) {
        return false;
      }

      if (right.getDefinition().getRoot(iContext.getDatabaseSession())
          .startsWith(SQLFilterItemFieldAll.NAME)) {
        // ALL VALUES
        for (var i = 0; i < right.getValues().length; ++i) {
          var v = right.getValues()[i];
          var l = iLeft;

          final var collate = right.getCollate(i);
          if (collate != null) {
            v = collate.transform(v);
            l = collate.transform(iLeft);
          }

          if (v == null || !evaluateExpression(iRecord, iCondition, l, v, iContext)) {
            return false;
          }
        }
        return true;
      } else {
        // ANY VALUES
        for (var i = 0; i < right.getValues().length; ++i) {
          var v = right.getValues()[i];
          var l = iLeft;

          final var collate = right.getCollate(i);
          if (collate != null) {
            v = collate.transform(v);
            l = collate.transform(iLeft);
          }

          if (v != null && evaluateExpression(iRecord, iCondition, l, v, iContext)) {
            return true;
          }
        }
        return false;
      }
    } else {
      // SINGLE SIMPLE ITEM
      return evaluateExpression(iRecord, iCondition, iLeft, iRight, iContext);
    }
  }
}
