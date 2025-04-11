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
package com.jetbrains.youtrack.db.internal.core.sql.functions.misc;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.functions.math.SQLFunctionMathAbstract;

/**
 * Count the record that contains a field. Use * to indicate the record instead of the field. Uses
 * the context to save the counter number. When different Number class are used, take the class with
 * most precision.
 */
public class SQLFunctionCount extends SQLFunctionMathAbstract {

  public static final String NAME = "count";

  private long total = 0;

  public SQLFunctionCount() {
    super(NAME, 1, 1);
  }

  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    if (iParams.length == 0 || iParams[0] != null) {
      total++;
    }

    return total;
  }

  public boolean aggregateResults() {
    return true;
  }

  public String getSyntax(DatabaseSession session) {
    return "count(<field>|*)";
  }

  @Override
  public Object getResult() {
    return total;
  }

  @Override
  public void setResult(final Object iResult) {
    total = ((Number) iResult).longValue();
  }
}
