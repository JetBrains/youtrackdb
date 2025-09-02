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
package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class CommandManager {

  private final Map<Class<? extends CommandRequest>, CallableFunction<Void, CommandRequest>>
      configCallbacks =
      new HashMap<>();
  private final Map<Class<? extends CommandRequest>, Class<? extends CommandExecutor>>
      commandReqExecMap =
      new HashMap<>();
  private final Map<String, ScriptExecutor> scriptExecutors = new HashMap<>();

  public CommandManager() {
    registerScriptExecutor("sql", new SqlScriptExecutor());
    registerScriptExecutor("script", new SqlScriptExecutor());
  }

  public ScriptExecutor getScriptExecutor(String language) {
    if (language == null) {
      throw new IllegalArgumentException("Invalid script languange: null");
    }
    var scriptExecutor = this.scriptExecutors.get(language);
    if (scriptExecutor == null) {
      scriptExecutor = this.scriptExecutors.get(language.toLowerCase(Locale.ENGLISH));
    }
    if (scriptExecutor == null) {
      throw new IllegalArgumentException(
          "Cannot find a script executor requester for language: " + language);
    }

    return scriptExecutor;
  }

  public void registerExecutor(
      final Class<? extends CommandRequest> iRequest,
      final Class<? extends CommandExecutor> iExecutor,
      final CallableFunction<Void, CommandRequest> iConfigCallback) {
    registerExecutor(iRequest, iExecutor);
    configCallbacks.put(iRequest, iConfigCallback);
  }

  public void registerScriptExecutor(String language, ScriptExecutor executor) {
    this.scriptExecutors.put(language, executor);
  }

  public Map<String, ScriptExecutor> getScriptExecutors() {
    return scriptExecutors;
  }

  public void registerExecutor(
      final Class<? extends CommandRequest> iRequest,
      final Class<? extends CommandExecutor> iExecutor) {
    commandReqExecMap.put(iRequest, iExecutor);
  }

  public void unregisterExecutor(final Class<? extends CommandRequest> iRequest) {
    commandReqExecMap.remove(iRequest);
    configCallbacks.remove(iRequest);
  }

  public CommandExecutor getExecutor(CommandRequestInternal iCommand) {
    final var executorClass =
        commandReqExecMap.get(iCommand.getClass());

    if (executorClass == null) {
      throw new CommandExecutorNotFoundException(null,
          "Cannot find a command executor for the command request: " + iCommand);
    }

    try {
      final var exec = executorClass.newInstance();

      final var callback = configCallbacks.get(
          iCommand.getClass());
      if (callback != null) {
        callback.call(iCommand);
      }

      return exec;

    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommandExecutionException((String) null,
              "Cannot create the command executor of class "
                  + executorClass
                  + " for the command request: "
                  + iCommand),
          e, iCommand.getContext().getDatabaseSession().getDatabaseName());
    }
  }

  public void close(String iDatabaseName) {
    for (var executor : scriptExecutors.values()) {
      executor.close(iDatabaseName);
    }
  }

  public void closeAll() {
    for (var executor : scriptExecutors.values()) {
      executor.closeAll();
    }
  }
}
