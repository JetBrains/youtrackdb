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
import java.math.MathContext;
import javax.annotation.Nullable;

/**
 * The arithmetic mean of a field, always divided in floating-point arithmetic.
 *
 * <p>{@code mean} differs from {@link SQLFunctionAverage avg} in exactly one respect, and the
 * difference is the reason it exists: {@code avg} divides in the input's own arithmetic, so
 * {@code avg(age)} over the integers 29, 27, 32 and 35 returns 30, while {@code mean(age)} returns
 * 30.75. Both are defensible readings of "average" over an integer column — {@code avg} is the one
 * YouTrackDB has always had and changing it would move results under existing queries — so the
 * floating-point reading gets its own name rather than a flag on the old one.
 *
 * <p>{@link BigDecimal} input keeps exact arithmetic and divides under {@link
 * MathContext#DECIMAL128}; every other numeric type is summed with the same {@link
 * PropertyTypeInternal#increment} promotion {@code avg} and {@code sum} use, then divided as a
 * {@code double}. Null contributors are skipped, and a mean over no contributor at all is
 * {@code null} rather than zero.
 */
public class SQLFunctionMean extends SQLFunctionMathAbstract {

  public static final String NAME = "mean";

  private Number sum;
  private int total = 0;

  public SQLFunctionMean() {
    super(NAME, 1, -1);
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    if (iParams.length == 1) {
      if (MultiValue.isMultiValue(iParams[0])) {
        for (var n : MultiValue.getMultiValueIterable(iParams[0])) {
          sum(n);
        }
      } else {
        sum(iParams[0]);
      }
    } else {
      // Multi-argument form is a row-wise mean of its arguments, not an aggregate, so the running
      // total restarts on every call the same way avg's does.
      sum = null;
      total = 0;
      for (var param : iParams) {
        sum(param);
      }
    }
    return getResult();
  }

  /**
   * Adds one contributor. Null and non-numeric values are skipped rather than counted, so the
   * divisor stays the number of values that actually contributed and a string column reaching
   * {@code mean(name)} leaves the aggregate empty instead of throwing a {@code ClassCastException}
   * out of the projection.
   */
  private void sum(@Nullable Object value) {
    if (!(value instanceof Number number)) {
      return;
    }
    total++;
    sum = sum == null ? number : PropertyTypeInternal.increment(sum, number);
  }

  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    return "mean(<field> [,<field>*])";
  }

  @Override
  public Object getResult() {
    return computeMean(sum, total);
  }

  /**
   * Divides {@code iSum} by {@code iTotal} in floating-point arithmetic. Returns {@code null} for a
   * null sum or an empty contributor set — an aggregate over nothing has no value, and returning
   * zero would be indistinguishable from a genuine zero mean.
   */
  @Nullable public static Object computeMean(@Nullable Number iSum, int iTotal) {
    if (iSum == null || iTotal == 0) {
      return null;
    }
    if (iSum instanceof BigDecimal bd) {
      return bd.divide(new BigDecimal(iTotal), MathContext.DECIMAL128);
    }
    return iSum.doubleValue() / iTotal;
  }
}
