package com.jetbrains.youtrackdb.internal.core.exception;

public class AcquireTimeoutException extends BaseException {

  public AcquireTimeoutException(String message) {
    super(message);
  }

  public AcquireTimeoutException(AcquireTimeoutException exception) {
    super(exception);
  }
}
