package com.jetbrains.youtrackdb.api.exception;

public class InvalidDatabaseNameException extends BaseException implements HighLevelException {

  public InvalidDatabaseNameException(final String message) {
    super(message);
  }

  public InvalidDatabaseNameException(final InvalidDatabaseNameException exception) {
    super(exception);
  }
}
