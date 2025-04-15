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
package com.jetbrains.youtrack.db.internal.server.handler;

import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.ScriptInterceptor;
import com.jetbrains.youtrack.db.internal.core.command.script.CommandExecutorScript;
import com.jetbrains.youtrack.db.internal.core.command.script.CommandScript;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginAbstract;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Allow the execution of server-side scripting. This could be a security hole in your configuration
 * if users have access to the database and can execute any kind of code.
 */
public class ServerSideScriptInterpreter extends ServerPluginAbstract {

  protected Set<String> allowedLanguages = new HashSet<String>();

  protected ScriptInterceptor interceptor;
  private YouTrackDBServer server;

  @Override
  public void config(final YouTrackDBServer iServer, ServerParameterConfiguration[] iParams) {

    this.server = iServer;
    for (var param : iParams) {
      if (param.name.equalsIgnoreCase("enabled")) {
        if (Boolean.parseBoolean(param.value))
        // ENABLE IT
        {
          enabled = true;
        }
      } else if (param.name.equalsIgnoreCase("allowedLanguages")) {
        allowedLanguages =
            new HashSet<>(Arrays.asList(param.value.toLowerCase(Locale.ENGLISH).split(",")));
      } else if (param.name.equalsIgnoreCase("allowedPackages")) {
        YouTrackDBInternal.extract(iServer.getContext())
            .getScriptManager()
            .addAllowedPackages(new HashSet<>(Arrays.asList(param.value.split(","))));
      }
    }
  }

  @Override
  public String getName() {
    return "script-interpreter";
  }

  @Override
  public void startup() {

    if (!enabled) {
      return;
    }

    YouTrackDBInternal.extract(server.getContext())
        .getScriptManager()
        .getCommandManager()
        .registerExecutor(
            CommandScript.class,
            CommandExecutorScript.class,
            iArgument -> {
              final var language =
                  ((CommandScript) iArgument).getLanguage().toLowerCase(Locale.ENGLISH);

              checkLanguage(language);
              return null;
            });

    interceptor =
        (db, language, script, params) -> {
          checkLanguage(language);
        };

    YouTrackDBInternal.extract(server.getContext())
        .getScriptManager()
        .getCommandManager()
        .getScriptExecutors()
        .entrySet()
        .forEach(e -> e.getValue().registerInterceptor(interceptor));
    LogManager.instance()
        .warn(
            this,
            "Authenticated clients can execute any kind of code into the server by using the"
                + " following allowed languages: "
                + allowedLanguages);
  }

  @Override
  public void shutdown() {
    if (!enabled) {
      return;
    }

    if (interceptor != null) {
      YouTrackDBInternal.extract(server.getContext())
          .getScriptManager()
          .getCommandManager()
          .getScriptExecutors()
          .entrySet()
          .forEach(e -> e.getValue().unregisterInterceptor(interceptor));
    }

    YouTrackDBInternal.extract(server.getContext())
        .getScriptManager()
        .getCommandManager()
        .unregisterExecutor(CommandScript.class);
  }

  private void checkLanguage(final String language) {
    if (allowedLanguages.contains(language)) {
      return;
    }

    if ("js".equals(language) && allowedLanguages.contains("javascript")) {
      return;
    }

    throw new SecurityException("Language '" + language + "' is not allowed to be executed");
  }
}
