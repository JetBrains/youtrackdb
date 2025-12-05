package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

public class SecurityAccessException extends SecurityException implements HighLevelException {

  public SecurityAccessException(SecurityAccessException exception) {
    super(exception);
  }

  public SecurityAccessException(final String iDatabasename, final String message) {
    super(iDatabasename, message);
  }

  public SecurityAccessException(final String message) {
    super(message);
  }
}
