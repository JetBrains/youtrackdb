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

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.operator.IndexReuseType;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator;
import java.math.BigDecimal;
import java.util.Date;
import javax.annotation.Nullable;

/**
 * MULTIPLY "*" operator.
 */
public class QueryOperatorMultiply extends QueryOperator {

  public QueryOperatorMultiply() {
    super("*", 10, false);
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
    if (iRight == null || iLeft == null) {
      return null;
    }

    if (iLeft instanceof Date) {
      iLeft = ((Date) iLeft).getTime();
    }
    if (iRight instanceof Date) {
      iRight = ((Date) iRight).getTime();
    }

    if (iLeft instanceof Number l && iRight instanceof Number r) {
      var maxPrecisionClass = getMaxPrecisionClass(l, r);
      if (Integer.class.equals(maxPrecisionClass)) {
        return tryDownscaleToInt(l.longValue() * r.longValue());
      } else if (Long.class.equals(maxPrecisionClass)) {
        return l.longValue() * r.longValue();
      } else if (Short.class.equals(maxPrecisionClass)) {
        return l.shortValue() * r.shortValue();
      } else if (Float.class.equals(maxPrecisionClass)) {
        return l.floatValue() * r.floatValue();
      } else if (Double.class.equals(maxPrecisionClass)) {
        return l.doubleValue() * r.doubleValue();
      } else if (BigDecimal.class.equals(maxPrecisionClass)) {
        return (toBigDecimal(l)).multiply(toBigDecimal(r));
      }
    }

    return null;
  }

  @Nullable
  public static BigDecimal toBigDecimal(Number number) {
    if (number instanceof BigDecimal) {
      return (BigDecimal) number;
    }
    if (number instanceof Double) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    if (number instanceof Float) {
      return BigDecimal.valueOf(number.floatValue());
    }
    if (number instanceof Long) {
      return new BigDecimal(number.longValue());
    }
    if (number instanceof Integer) {
      return new BigDecimal(number.intValue());
    }
    if (number instanceof Short) {
      return new BigDecimal(number.intValue());
    }

    return null;
  }

  @Nullable
  public static Class getMaxPrecisionClass(Number l, Number r) {
    var lClass = l.getClass();
    var rClass = r.getClass();
    if (lClass.equals(BigDecimal.class) || rClass.equals(BigDecimal.class)) {
      return BigDecimal.class;
    }
    if (lClass.equals(Double.class) || rClass.equals(Double.class)) {
      return Double.class;
    }
    if (lClass.equals(Float.class) || rClass.equals(Float.class)) {
      return Float.class;
    }
    if (lClass.equals(Long.class) || rClass.equals(Long.class)) {
      return Long.class;
    }
    if (lClass.equals(Integer.class) || rClass.equals(Integer.class)) {
      return Integer.class;
    }
    if (lClass.equals(Short.class) || rClass.equals(Short.class)) {
      return Short.class;
    }

    return null;
  }

  public static Object tryDownscaleToInt(long value) {
    if (value < Integer.MAX_VALUE && value > Integer.MIN_VALUE) {
      return (int) value;
    }
    return value;
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
