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
package com.jetbrains.youtrackdb.internal.core.sql.operator.math;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.operator.IndexReuseType;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator;
import java.math.BigDecimal;
import java.util.Date;
import javax.annotation.Nullable;

/**
 * PLUS "+" operator.
 */
public class QueryOperatorPlus extends QueryOperator {

  public QueryOperatorPlus() {
    super("+", 9, false);
  }

  @Nullable
  @Override
  public Object evaluateRecord(
      final Result iRecord,
      EntityImpl iCurrentResult,
      final SQLFilterCondition iCondition,
      Object iLeft,
      Object iRight,
      CommandContext iContext,
      final EntitySerializer serializer) {
    if (iRight == null) {
      return iLeft;
    }
    if (iLeft == null) {
      return iRight;
    }

    if (iLeft instanceof Date) {
      iLeft = ((Date) iLeft).getTime();
    }
    if (iRight instanceof Date) {
      iRight = ((Date) iRight).getTime();
    }

    if (iLeft instanceof String) {
      return iLeft.toString() + iRight;
    } else if (iRight instanceof String) {
      return iLeft.toString() + iRight;
    } else if (iLeft instanceof Number l && iRight instanceof Number r) {
      var maxPrecisionClass = QueryOperatorMultiply.getMaxPrecisionClass(l, r);
      if (Integer.class.equals(maxPrecisionClass)) {
        return QueryOperatorMultiply.tryDownscaleToInt(l.longValue() + r.longValue());
      } else if (Long.class.equals(maxPrecisionClass)) {
        return l.longValue() + r.longValue();
      } else if (Short.class.equals(maxPrecisionClass)) {
        return l.shortValue() + r.shortValue();
      } else if (Float.class.equals(maxPrecisionClass)) {
        return l.floatValue() + r.floatValue();
      } else if (Double.class.equals(maxPrecisionClass)) {
        return l.doubleValue() + r.doubleValue();
      } else if (BigDecimal.class.equals(maxPrecisionClass)) {
        return (QueryOperatorMultiply.toBigDecimal(l)).add(QueryOperatorMultiply.toBigDecimal(r));
      }
    }

    return null;
  }

  @Override
  public IndexReuseType getIndexReuseType(Object iLeft, Object iRight) {
    return IndexReuseType.NO_INDEX;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }
}
