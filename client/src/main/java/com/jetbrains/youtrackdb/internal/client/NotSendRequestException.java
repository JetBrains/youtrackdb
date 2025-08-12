package com.jetbrains.youtrackdb.internal.client;

import com.jetbrains.youtrackdb.internal.common.exception.SystemException;

public class NotSendRequestException extends SystemException {

  public NotSendRequestException(SystemException exception) {
    super(exception);
  }

  public NotSendRequestException(String message) {
    super(message);
  }
}
