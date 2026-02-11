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
package com.jetbrains.youtrackdb.internal.core.metadata.function;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction;
import javax.annotation.Nullable;

/**
 * Dynamic function factory bound to the database's functions
 */
public class DatabaseFunction implements SQLFunction {

  private final Function f;

  public DatabaseFunction(final Function f) {
    this.f = f;
  }

  @Override
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iFuncParams,
      final CommandContext iContext) {
    return f.executeInContext(iContext, iFuncParams);
  }

  @Override
  public boolean aggregateResults() {
    return false;
  }

  @Override
  public boolean filterResult() {
    return false;
  }

  @Override
  public String getName(DatabaseSessionEmbedded session) {
    return f.getName();
  }

  @Override
  public int getMinParams() {
    return 0;
  }

  @Override
  public int getMaxParams(DatabaseSessionEmbedded session) {
    return f.getParameters() != null ? f.getParameters().size() : 0;
  }

  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    final var buffer = new StringBuilder(512);
    buffer.append(f.getName());
    buffer.append('(');
    final var params = f.getParameters();
    for (var p = 0; p < params.size(); ++p) {
      if (p > 0) {
        buffer.append(',');
      }
      buffer.append(params.get(p));
    }
    buffer.append(')');
    return buffer.toString();
  }

  @Nullable
  @Override
  public Object getResult() {
    return null;
  }

  @Override
  public void setResult(final Object iResult) {
  }

  @Override
  public void config(final Object[] configuredParameters) {
  }
}
