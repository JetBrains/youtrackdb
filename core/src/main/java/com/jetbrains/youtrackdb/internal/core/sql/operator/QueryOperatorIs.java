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
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.SQLHelper;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import javax.annotation.Nullable;

/**
 * IS operator. Different by EQUALS since works also for null. Example "IS null"
 */
public class QueryOperatorIs extends QueryOperatorEquality {

  public QueryOperatorIs() {
    super("IS", 5, false);
  }

  @Override
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      Object iRight,
      CommandContext iContext) {
    if (iCondition.getLeft() instanceof SQLFilterItemField) {
      if (SQLHelper.DEFINED.equals(iCondition.getRight())) {
        return evaluateDefined(iRecord, "" + iCondition.getLeft());
      }

      if (iCondition.getRight() instanceof SQLFilterItemField
          && "not defined".equalsIgnoreCase("" + iCondition.getRight())) {
        return !evaluateDefined(iRecord, "" + iCondition.getLeft());
      }
    }

    if (SQLHelper.NOT_NULL.equals(iRight)) {
      return iLeft != null;
    } else if (SQLHelper.NOT_NULL.equals(iLeft)) {
      return iRight != null;
    } else if (SQLHelper.DEFINED.equals(iLeft)) {
      return evaluateDefined(iRecord, (String) iRight);
    } else if (SQLHelper.DEFINED.equals(iRight)) {
      return evaluateDefined(iRecord, (String) iLeft);
    } else {
      return iLeft == iRight;
    }
  }

  protected static boolean evaluateDefined(final Result iRecord, final String iFieldName) {
    return iRecord.getProperty(iFieldName) != null;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (iRight == null) {
      return IndexReuseType.INDEX_METHOD;
    }

    return IndexReuseType.NO_INDEX;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSessionEmbedded session, Object iLeft, Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSessionEmbedded session, Object iLeft, Object iRight) {
    return null;
  }
}
