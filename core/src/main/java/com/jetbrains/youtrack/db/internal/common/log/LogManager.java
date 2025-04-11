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

package com.jetbrains.youtrack.db.internal.common.log;

import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.event.Level;

/**
 * Centralized Log Manager. All the logging must be done using this class to have a centralized
 * configuration and avoid hard-coding. It uses SLF4J as the logging facade. Logging methods are
 * accepting messages formatted as in {@link String#format(String, Object...)} It is strongly
 * recommended to use specialized logging methods from {@link SL4JLogManager} class instead of
 * generic {@link SL4JLogManager#log(Object, Level, String, Throwable, Object...)} methods from
 * this of {@link SL4JLogManager} class.
 *
 * <p>There are additional methods to manage JUL runtime configuration. That is used for logging
 * messages in server and console.
 *
 * @see SL4JLogManager
 */
public class LogManager extends SL4JLogManager {

  private static final String ENV_INSTALL_CUSTOM_FORMATTER = "youtrackdb.installCustomFormatter";
  private static final LogManager instance = new LogManager();

  private java.util.logging.Level minimumLevel = java.util.logging.Level.SEVERE;

  private final AtomicBoolean shutdownFlag = new AtomicBoolean();

  protected LogManager() {
  }

  public static LogManager instance() {
    return instance;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void log(
      @Nonnull Object requester,
      @Nonnull Level level,
      @Nonnull String message,
      @Nullable Throwable exception,
      @Nullable Object... additionalArgs) {
    if (shutdownFlag.get()) {
      System.err.println("ERROR: LogManager is shutdown, no logging is possible !!!");
    } else {
      super.log(requester, level, message, exception, additionalArgs);
    }
  }

  public void installCustomFormatter() {
    final var installCustomFormatter =
        Boolean.parseBoolean(
            SystemVariableResolver.resolveSystemVariables(
                "${" + ENV_INSTALL_CUSTOM_FORMATTER + "}", "true"));

    if (!installCustomFormatter) {
      return;
    }

    try {
      // ASSURE TO HAVE THE YouTrackDB LOG FORMATTER TO THE CONSOLE EVEN IF NO CONFIGURATION FILE IS
      // TAKEN
      final var log = Logger.getLogger("");

      setLevelInternal(log.getLevel());

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

  public boolean isLevelEnabled(final java.util.logging.Level level) {
    if (level.equals(java.util.logging.Level.FINER)
        || level.equals(java.util.logging.Level.FINE)
        || level.equals(java.util.logging.Level.FINEST)) {
      return debug;
    } else if (level.equals(java.util.logging.Level.INFO)) {
      return info;
    } else if (level.equals(java.util.logging.Level.WARNING)) {
      return warn;
    } else if (level.equals(java.util.logging.Level.SEVERE)) {
      return error;
    }
    return false;
  }

  public void setConsoleLevel(final String iLevel) {
    setLevel(iLevel, ConsoleHandler.class);
  }

  public void setFileLevel(final String iLevel) {
    setLevel(iLevel, FileHandler.class);
  }

  public java.util.logging.Level setLevel(
      final String iLevel, final Class<? extends Handler> iHandler) {
    final var level =
        iLevel != null
            ? java.util.logging.Level.parse(iLevel.toUpperCase(Locale.ENGLISH))
            : java.util.logging.Level.INFO;
    if (level.intValue() < minimumLevel.intValue()) {
      // UPDATE MINIMUM LEVEL
      minimumLevel = level;
      setLevelInternal(level);
    }

    var log = Logger.getLogger(DEFAULT_LOG);
    while (log != null) {
      for (var h : log.getHandlers()) {
        if (h.getClass().isAssignableFrom(iHandler)) {
          h.setLevel(level);
          break;
        }
      }

      log = log.getParent();
    }

    return level;
  }

  protected void setLevelInternal(final java.util.logging.Level level) {
    if (level == null) {
      return;
    }

    if (level.equals(java.util.logging.Level.FINER)
        || level.equals(java.util.logging.Level.FINE)
        || level.equals(java.util.logging.Level.FINEST)) {
      debug = info = warn = error = true;
    } else if (level.equals(java.util.logging.Level.INFO)) {
      info = warn = error = true;
      debug = false;
    } else if (level.equals(java.util.logging.Level.WARNING)) {
      warn = error = true;
      debug = info = false;
    } else if (level.equals(java.util.logging.Level.SEVERE)) {
      error = true;
      debug = info = warn = false;
    }
  }

  public void flush() {
    for (var h : Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).getHandlers()) {
      h.flush();
    }
  }

  /**
   * Shutdowns this log manager.
   */
  public void shutdown() {
    if (shutdownFlag.compareAndSet(false, true)) {
      try {
        if (java.util.logging.LogManager.getLogManager() instanceof ShutdownLogManager) {
          ((ShutdownLogManager) java.util.logging.LogManager.getLogManager()).shutdown();
        }
      } catch (NoClassDefFoundError ignore) {
        // Om nom nom. Some custom class loaders, like Tomcat's one, cannot load classes while in
        // shutdown hooks, since their
        // runtime is already shutdown. Ignoring the exception, if ShutdownLogManager is not loaded
        // at this point there are no instances
        // of it anyway and we have nothing to shutdown.
      }
    }
  }

  /**
   * @return <code>true</code> if log manager is shutdown by {@link #shutdown()} method and no
   * logging is possible.
   */
  public boolean isShutdown() {
    return shutdownFlag.get();
  }

  public static Level fromJulToSLF4JLevel(java.util.logging.Level level) {
    return switch (level.intValue()) {
      case 300 -> Level.ERROR;
      case 400 -> Level.TRACE;
      case 800 -> Level.WARN;
      case 1000 -> Level.DEBUG;
      default -> Level.INFO;
    };
  }
}
