package com.jetbrains.youtrackdb.api.exception;

public class AcquireTimeoutException extends BaseException {

  public AcquireTimeoutException(String message) {
    super(message);
  }

  public AcquireTimeoutException(AcquireTimeoutException exception) {
    super(exception);
  }
}
