package com.jetbrains.youtrackdb.internal.common.log;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.event.Level;

/**
 * Centralized Log Manager used in YouTrackDB. All the log messages are routed through this class.
 * It uses SLF4J as the logging facade. Logging methods are accepting messages formatted as in
 * {@link String#format(String, Object...)} It is strongly recommended to use specialized logging
 * methods from this class instead of generic
 * {@link #log(Object, String, Level, String, Throwable, Object...)} method.
 */
public abstract class SLF4JLogManager {
  private final ConcurrentHashMap<String, Logger> loggersCache = new ConcurrentHashMap<>();

  /**
   * Loges a message if the provided level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param dbName         Name of the current database
   * @param level          the level of the log
   * @param message        the message to log, accepts a format provided in
   *                       {@link String#format(String, Object...)}
   * @param exception      the exception to log
   * @param additionalArgs additional arguments to format the message
   */
  public void log(
      @Nonnull Object requester,
      @Nullable String dbName,
      @Nonnull Level level,
      @Nonnull String message,
      @Nullable Throwable exception,
      @Nullable Object... additionalArgs) {
    Objects.requireNonNull(requester);
    Objects.requireNonNull(level);
    Objects.requireNonNull(message);
    final String requesterName;
    if (requester instanceof Class<?>) {
      requesterName = ((Class<?>) requester).getName();
    } else {
      requesterName = requester.getClass().getName();
    }

    var log =
        loggersCache.compute(
            requesterName,
            (k, v) -> {
              if (v == null) {
                return LoggerFactory.getLogger(k);
              } else {
                return v;
              }
            });

    if (log.isEnabledForLevel(level)) {
      String dbURL;
      if (dbName == null) {
        dbURL = fetchDbName(requester, exception);
      } else {
        dbURL = dbName;
      }

      Marker dbMarker = null;
      if (dbURL != null) {
        message = "[" + dbURL + "] " + message;
        dbMarker = MarkerFactory.getMarker("youtrackdb:" + dbURL);
      }

      // USE THE LOG
      try {
        final String msg;
        if (additionalArgs != null && additionalArgs.length > 0) {
          msg = String.format(message, additionalArgs);
        } else {
          msg = message;
        }

        var logEventBuilder = log.makeLoggingEventBuilder(level);
        logEventBuilder = logEventBuilder.setMessage(msg);
        if (dbMarker != null) {
          logEventBuilder = logEventBuilder.addMarker(dbMarker);
        }
        if (exception != null) {
          logEventBuilder = logEventBuilder.setCause(exception);
        }

        logEventBuilder.log();
      } catch (Exception e) {
        System.err.println("Error on formatting message '" + message + "'. Exception: " + e);
      }
    }
  }

  private static String fetchDbName(@Nullable Object requester, @Nullable Throwable exception) {
    String dbName = null;
    try {
      if (requester instanceof Storage storage) {
        dbName = storage.getName();
      } else if (requester instanceof DatabaseSessionEmbedded databaseSession) {
        dbName = databaseSession.getDatabaseName();
      } else if (exception instanceof BaseException baseException) {
        dbName = baseException.getDbName();
      }
    } catch (Exception ignore) {
    }

    return dbName;
  }

  /**
   * Loges a message with debug level if this level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param message        the message to log, accepts format provided in
   *                       {@link String#format(String, Object...)}
   * @param logger         Logger used to write message
   * @param additionalArgs additional arguments to format the message
   */
  public void debug(
      @Nonnull final Object requester,
      @Nonnull final String message,
      Logger logger, @Nullable final Object... additionalArgs) {
    debug(requester, message, logger, null, additionalArgs);
  }

  /**
   * Loges a message with debug level if this level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param message        the message to log, accepts format provided in
   *                       {@link String#format(String, Object...)}
   * @param logger         Logger used to write a message
   * @param exception      the exception to log
   * @param additionalArgs additional arguments to format the message
   */
  public void debug(
      @Nonnull final Object requester,
      @Nonnull final String message,
      Logger logger, @Nullable final Throwable exception,
      @Nullable final Object... additionalArgs) {
    if (logger.isDebugEnabled()) {
      log(requester, null, Level.DEBUG, message, exception, additionalArgs);
    }
  }

  /**
   * Loges a message with info level if this level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param message        the message to log, accepts format provided in
   *                       {@link String#format(String, Object...)}
   * @param additionalArgs additional arguments to format the message
   */
  public void info(
      @Nonnull final Object requester,
      @Nonnull final String message,
      @Nullable final Object... additionalArgs) {
    info(requester, message, null, additionalArgs);
  }

  /**
   * Loges a message with info level if this level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param message        the message to log, accepts format provided in
   *                       {@link String#format(String, Object...)}
   * @param exception      the exception to log
   * @param additionalArgs additional arguments to format the message
   */
  public void info(
      final @Nonnull Object requester,
      final @Nonnull String message,
      final @Nullable Throwable exception,
      final @Nullable Object... additionalArgs) {
    log(requester, null, Level.INFO, message, exception, additionalArgs);
  }

  /**
   * Loges a message with warn level if this level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param message        the message to log, accepts format provided in
   *                       {@link String#format(String, Object...)}
   * @param additionalArgs additional arguments to format the message
   */
  public void warn(
      @Nonnull final Object requester,
      @Nonnull final String message,
      @Nullable final Object... additionalArgs) {
    log(requester, null, Level.WARN, message, null, additionalArgs);
  }


  /**
   * Loges a message with warn level if this level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param message        the message to log, accepts format provided in
   *                       {@link String#format(String, Object...)}
   * @param exception      the exception to log
   * @param additionalArgs additional arguments to format the message
   */
  public void warn(
      @Nonnull final Object requester,
      @Nonnull final String message,
      @Nullable final Throwable exception,
      @Nullable final Object... additionalArgs) {
    log(requester, null, Level.WARN, message, exception, additionalArgs);
  }

  /**
   * Loges a message with warn level if this level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param dbName         Name of the current database.
   * @param message        the message to log, accepts format provided in
   *                       {@link String#format(String, Object...)}
   * @param additionalArgs additional arguments to format the message
   */
  public void warn(
      @Nonnull final Object requester,
      @Nonnull String dbName,
      @Nonnull final String message,
      @Nullable final Object... additionalArgs) {
    log(requester, dbName, Level.WARN, message, null, additionalArgs);
  }

  /**
   * Loges a message with error level if this level of logging is enabled.
   *
   * @param requester      the object that requested the log
   * @param message        the message to log, accepts format provided in
   *                       {@link String#format(String, Object...)}
   * @param additionalArgs additional arguments to format the message
   */
  public void error(
      @Nonnull final Object requester,
      @Nonnull final String message,
      @Nullable final Throwable exception,
      @Nullable final Object... additionalArgs) {
    log(requester, null, Level.ERROR, message, exception, additionalArgs);
  }


}
