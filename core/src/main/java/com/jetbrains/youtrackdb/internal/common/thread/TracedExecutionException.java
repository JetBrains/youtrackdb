package com.jetbrains.youtrackdb.internal.common.thread;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;

public class TracedExecutionException extends BaseException {

  public TracedExecutionException(String message, Exception cause) {
    super(message);
    initCause(cause);
  }

  public TracedExecutionException(String message) {
    super(message);
  }

  private static String taskName(Object task) {
    if (task != null) {
      return task.getClass().getSimpleName();
    }
    return "?";
  }

  public static TracedExecutionException prepareTrace(Object task) {
    final TracedExecutionException trace;
    trace = new TracedExecutionException(String.format("Async task [%s] failed", taskName(task)));
    trace.fillInStackTrace();
    return trace;
  }

  public static TracedExecutionException trace(
      TracedExecutionException trace, Exception e, Object task)
      throws TracedExecutionException {
    if (trace != null) {
      trace.initCause(e);
      return trace;
    } else {
      return new TracedExecutionException(
          String.format("Async task [%s] failed", taskName(task)), e);
    }
  }
}
