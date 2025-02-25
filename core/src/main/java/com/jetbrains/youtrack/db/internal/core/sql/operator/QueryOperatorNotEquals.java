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
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;

/**
 * NOT EQUALS operator.
 */
public class QueryOperatorNotEquals extends QueryOperatorEqualityNotNulls {
  public QueryOperatorNotEquals() {
    super("<>", 5, false);
  }

  @Override
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    return !QueryOperatorEquals.equals(iContext.getDatabaseSession(), iLeft, iRight);
  }

  @Override
  public boolean isSupportingBinaryEvaluate() {
    return true;
  }

  @Override
  public boolean evaluate(
      final BinaryField iFirstField,
      final BinaryField iSecondField,
      CommandContext iContext,
      final EntitySerializer serializer) {
    return !serializer.getComparator()
        .isEqual(iContext.getDatabaseSession(), iFirstField, iSecondField);
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.NO_INDEX;
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
