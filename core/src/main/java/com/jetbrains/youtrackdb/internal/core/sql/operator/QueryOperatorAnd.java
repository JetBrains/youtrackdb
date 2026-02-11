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
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import javax.annotation.Nullable;

/**
 * AND operator.
 */
public class QueryOperatorAnd extends QueryOperator {

  public QueryOperatorAnd() {
    super("AND", 4, false);
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
    if (iLeft == null) {
      return false;
    }
    return (Boolean) iLeft && (Boolean) iRight;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (iLeft == null || iRight == null) {
      return IndexReuseType.NO_INDEX;
    }
    return IndexReuseType.INDEX_INTERSECTION;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSessionEmbedded session, final Object iLeft,
      final Object iRight) {
    final RID leftRange;
    final RID rightRange;

    if (iLeft instanceof SQLFilterCondition) {
      leftRange = ((SQLFilterCondition) iLeft).getBeginRidRange(session);
    } else {
      leftRange = null;
    }

    if (iRight instanceof SQLFilterCondition) {
      rightRange = ((SQLFilterCondition) iRight).getBeginRidRange(session);
    } else {
      rightRange = null;
    }

    if (leftRange == null && rightRange == null) {
      return null;
    } else if (leftRange == null) {
      return rightRange;
    } else if (rightRange == null) {
      return leftRange;
    } else {
      return leftRange.compareTo(rightRange) <= 0 ? rightRange : leftRange;
    }
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSessionEmbedded session, final Object iLeft,
      final Object iRight) {
    final RID leftRange;
    final RID rightRange;

    if (iLeft instanceof SQLFilterCondition) {
      leftRange = ((SQLFilterCondition) iLeft).getEndRidRange(session);
    } else {
      leftRange = null;
    }

    if (iRight instanceof SQLFilterCondition) {
      rightRange = ((SQLFilterCondition) iRight).getEndRidRange(session);
    } else {
      rightRange = null;
    }

    if (leftRange == null && rightRange == null) {
      return null;
    } else if (leftRange == null) {
      return rightRange;
    } else if (rightRange == null) {
      return leftRange;
    } else {
      return leftRange.compareTo(rightRange) >= 0 ? rightRange : leftRange;
    }
  }

  @Override
  public boolean canShortCircuit(Object l) {
    return Boolean.FALSE.equals(l);
  }
}
