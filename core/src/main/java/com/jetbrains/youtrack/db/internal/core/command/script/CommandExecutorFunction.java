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
package com.jetbrains.youtrack.db.internal.core.command.script;

import com.jetbrains.youtrack.db.api.exception.CommandScriptException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Executes Script Commands.
 *
 * @see CommandScript
 */
public class CommandExecutorFunction extends CommandExecutorAbstract {


  public CommandExecutorFunction() {
  }

  @SuppressWarnings("unchecked")
  public CommandExecutorFunction parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    return this;
  }

  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    return executeInContext(null, iArgs);
  }

  @Nullable
  public Object executeInContext(final CommandContext iContext, final Map<Object, Object> iArgs) {
    return null;
  }

  public boolean isIdempotent() {
    return false;
  }

  @Override
  protected void throwSyntaxErrorException(String dbName, String iText) {
    throw new CommandScriptException(dbName,
        "Error on execution of the script: " + iText, "", 0);
  }
}
