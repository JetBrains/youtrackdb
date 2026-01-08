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

package com.jetbrains.youtrackdb.internal.common.log;

import com.jetbrains.youtrackdb.internal.common.parser.SystemVariableResolver;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import org.slf4j.event.Level;

/**
 * Centralized Log Manager. All the logging must be done using this class to have a centralized
 * configuration and avoid hard-coding. It uses SLF4J as the logging facade. Logging methods are
 * accepting messages formatted as in {@link String#format(String, Object...)} It is strongly
 * recommended to use specialized logging methods from {@link SL4JLogManager} class instead of
 * generic {@link SL4JLogManager#log(Object, Level, String, Throwable, Object...)} methods from this
 * of {@link SL4JLogManager} class.
 *
 * <p>There are additional methods to manage JUL runtime configuration. That is used for logging
 * messages in server and console.
 *
 * @see SL4JLogManager
 */
public class LogManager extends SL4JLogManager {
  private static final String ENV_INSTALL_CUSTOM_FORMATTER = "youtrackdb.installCustomFormatter";
  private static final LogManager instance = new LogManager();

  protected LogManager() {
  }

  public static LogManager instance() {
    return instance;
  }


  public static void installCustomFormatter() {
    final var installCustomFormatter =
        Boolean.parseBoolean(
            SystemVariableResolver.resolveSystemVariables(
                "${" + ENV_INSTALL_CUSTOM_FORMATTER + "}", "true"));

    if (!installCustomFormatter) {
      return;
    }

    try {
      //check if we are running in the server mode.
      var ytdbHome = System.getenv("YOUTRACKDB_HOME");
      if (ytdbHome != null) {
        var logManager = java.util.logging.LogManager.getLogManager();
        var logConfig = Paths.get(ytdbHome).resolve("conf")
            .resolve("youtrackdb-server-log.properties");
        if (Files.exists(logConfig)) {
          logManager.readConfiguration(Files.newInputStream(logConfig));
        } else {
          var classPathConfig = LogManager.class.getClassLoader().getResourceAsStream(
              "com/jetbrains/youtrackdb/internal/server/conf/youtrackdb-server-log.properties");
          if (classPathConfig != null) {
            logManager.readConfiguration(classPathConfig);
          }
        }
      }

      // ASSURE TO HAVE THE YouTrackDB LOG FORMATTER TO THE CONSOLE EVEN IF NO CONFIGURATION FILE IS
      // TAKEN
      final var log = Logger.getLogger("");

      if (log.getHandlers().length == 0) {
        // SET DEFAULT LOG FORMATTER
        final Handler h = new ConsoleHandler();
        h.setFormatter(new AnsiLogFormatter());
        log.addHandler(h);
      } else {
        for (var h : log.getHandlers()) {
          if (h instanceof ConsoleHandler
              && !h.getFormatter().getClass().equals(AnsiLogFormatter.class)) {
            h.setFormatter(new AnsiLogFormatter());
          }
        }
      }
    } catch (Exception e) {
      System.err.println(
          "Error while installing custom formatter. Logging could be disabled. Cause: " + e);
    }
  }

  public static void flush() {
    for (var h : Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).getHandlers()) {
      h.flush();
    }
  }

}
