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

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Evaluates the absolute value for numeric types. The argument must be a BigDecimal, BigInteger,
 * Integer, Long, Double or a Float, or null. If null is passed in the result will be null.
 * Otherwise the result will be the mathematical absolute value of the argument passed in and will
 * be of the same type that was passed in.
 */
public class SQLFunctionAbsoluteValue extends SQLFunctionMathAbstract {

  public static final String NAME = "abs";
  private Object result;

  public SQLFunctionAbsoluteValue() {
    super(NAME, 1, 1);
  }

  public Object execute(
      Object iThis,
      final Result iRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    var inputValue = iParams[0];

    if (inputValue == null) {
      result = null;
    } else if (inputValue instanceof BigDecimal bd) {
      result = bd.abs();
    } else if (inputValue instanceof BigInteger bi) {
      result = bi.abs();
    } else if (inputValue instanceof Integer i) {
      result = Math.abs(i);
    } else if (inputValue instanceof Long l) {
      result = Math.abs(l);
    } else if (inputValue instanceof Short s) {
      result = (short) Math.abs(s);
    } else if (inputValue instanceof Double d) {
      result = Math.abs(d);
    } else if (inputValue instanceof Float f) {
      result = Math.abs(f);
    } else {
      throw new IllegalArgumentException("Argument to absolute value must be a number.");
    }

    return result;
  }

  public boolean aggregateResults() {
    return false;
  }

  public String getSyntax(DatabaseSessionEmbedded session) {
    return "abs(<number>)";
  }

  @Override
  public Object getResult() {
    return result;
  }
}
