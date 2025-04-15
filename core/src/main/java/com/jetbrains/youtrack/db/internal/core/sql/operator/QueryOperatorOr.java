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
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import javax.annotation.Nullable;

/**
 * OR operator.
 */
public class QueryOperatorOr extends QueryOperator {

  public QueryOperatorOr() {
    super("OR", 3, false);
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
    return (Boolean) iLeft || (Boolean) iRight;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    if (iLeft == null || iRight == null) {
      return IndexReuseType.NO_INDEX;
    }
    return IndexReuseType.INDEX_UNION;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSession session, final Object iLeft,
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

    if (leftRange == null || rightRange == null) {
      return null;
    } else {
      return leftRange.compareTo(rightRange) <= 0 ? leftRange : rightRange;
    }
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, final Object iLeft, final Object iRight) {
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

    if (leftRange == null || rightRange == null) {
      return null;
    } else {
      return leftRange.compareTo(rightRange) >= 0 ? leftRange : rightRange;
    }
  }

  @Override
  public boolean canShortCircuit(Object l) {
    return Boolean.TRUE.equals(l);
  }
}
