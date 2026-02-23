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
package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.math.BigDecimal;
import java.math.RoundingMode;
import javax.annotation.Nullable;

/**
 * Compute the average value for a field. Uses the context to save the last average number. When
 * different Number class are used, take the class with most precision.
 */
public class SQLFunctionAverage extends SQLFunctionMathAbstract {

  public static final String NAME = "avg";

  private Number sum;
  private int total = 0;

  public SQLFunctionAverage() {
    super(NAME, 1, -1);
  }

  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    if (iParams.length == 1) {
      if (iParams[0] instanceof Number) {
        sum((Number) iParams[0]);
      } else if (MultiValue.isMultiValue(iParams[0])) {
        for (var n : MultiValue.getMultiValueIterable(iParams[0])) {
          sum((Number) n);
        }
      }

    } else {
      sum = null;
      for (var i = 0; i < iParams.length; ++i) {
        sum((Number) iParams[i]);
      }
    }

    return getResult();
  }

  protected void sum(Number value) {
    if (value != null) {
      total++;
      if (sum == null)
      // FIRST TIME
      {
        sum = value;
      } else {
        sum = PropertyTypeInternal.increment(sum, value);
      }
    }
  }

  public String getSyntax(DatabaseSessionEmbedded session) {
    return "avg(<field> [,<field>*])";
  }

  @Override
  public Object getResult() {
    return computeAverage(sum, total);
  }

  @Override
  public boolean aggregateResults() {
    return configuredParameters.length == 1;
  }

  @Nullable
  private Object computeAverage(Number iSum, int iTotal) {
    if (iSum instanceof Integer) {
      return iSum.intValue() / iTotal;
    } else if (iSum instanceof Long) {
      return iSum.longValue() / iTotal;
    } else if (iSum instanceof Float) {
      return iSum.floatValue() / iTotal;
    } else if (iSum instanceof Double) {
      return iSum.doubleValue() / iTotal;
    } else if (iSum instanceof BigDecimal bd) {
      return bd.divide(new BigDecimal(iTotal), RoundingMode.HALF_UP);
    }

    return null;
  }
}
