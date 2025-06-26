/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.sql.method.misc;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunction;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionRuntime;

/**
 * Delegates the execution to a function.
 */
public class SQLMethodFunctionDelegate extends AbstractSQLMethod {

  public static final String NAME = "function";
  private final SQLFunctionRuntime func;

  public SQLMethodFunctionDelegate(final SQLFunction f) {
    super(NAME);
    func = new SQLFunctionRuntime(f);
  }

  @Override
  public int getMinParams() {
    final var min = func.getFunction().getMinParams();
    return min == -1 ? -1 : min - 1;
  }

  @Override
  public int getMaxParams(DatabaseSession session) {
    final var max = func.getFunction().getMaxParams(session);
    return max == -1 ? -1 : max - 1;
  }

  @Override
  public Object execute(
      final Object iThis,
      final Result iCurrentRecord,
      final CommandContext iContext,
      final Object ioResult,
      final Object[] iParams) {

    func.setParameters(iContext, iParams, false);

    return func.execute(iThis, iCurrentRecord, ioResult, iContext);
  }

  @Override
  public String toString() {
    return "function " + func;
  }
}
