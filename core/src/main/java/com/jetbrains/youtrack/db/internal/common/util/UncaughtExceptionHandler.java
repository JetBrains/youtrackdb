package com.jetbrains.youtrack.db.internal.common.util;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;

/**
 * Handler which is used to log all exceptions which are left uncaught by any exception handler.
 */
public class UncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

  @Override
  public void uncaughtException(Thread t, Throwable e) {
    final var logManager = LogManager.instance();

    if (logManager != null) {
      LogManager.instance().error(this, "Uncaught exception in thread %s", e, t.getName());
    }
  }
}
