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

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import javax.annotation.Nullable;

/**
 * NOT operator.
 */
public class QueryOperatorNot extends QueryOperator {

  private QueryOperator next;

  public QueryOperatorNot() {
    super("NOT", 10, true);
    next = null;
  }

  public QueryOperatorNot(final QueryOperator iNext) {
    this();
    next = iNext;
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
    if (next != null) {
      return !(Boolean)
          next.evaluateRecord(iRecord, null, iCondition, iLeft, iRight, iContext, serializer);
    }

    if (iLeft == null) {
      return false;
    }
    return !(Boolean) iLeft;
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.NO_INDEX;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    if (iLeft instanceof SQLFilterCondition) {
      final var beginRange = ((SQLFilterCondition) iLeft).getBeginRidRange(session);
      final var endRange = ((SQLFilterCondition) iLeft).getEndRidRange(session);

      if (beginRange == null && endRange == null) {
        return null;
      } else if (beginRange == null) {
        return endRange;
      } else if (endRange == null) {
        return null;
      } else {
        return null;
      }
    }

    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    if (iLeft instanceof SQLFilterCondition) {
      final var beginRange = ((SQLFilterCondition) iLeft).getBeginRidRange(session);
      final var endRange = ((SQLFilterCondition) iLeft).getEndRidRange(session);

      if (beginRange == null && endRange == null) {
        return null;
      } else if (beginRange == null) {
        return null;
      } else if (endRange == null) {
        return beginRange;
      } else {
        return null;
      }
    }

    return null;
  }

  public QueryOperator getNext() {
    return next;
  }
}
