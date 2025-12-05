package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

public class InvalidDatabaseNameException extends BaseException implements HighLevelException {

  public InvalidDatabaseNameException(final String message) {
    super(message);
  }

  public InvalidDatabaseNameException(final InvalidDatabaseNameException exception) {
    super(exception);
  }
}
