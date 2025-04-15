package com.jetbrains.youtrack.db.internal.core.exception;

/**
 * @since 2/28/2015
 */
public class SequenceException extends CoreException {
  public SequenceException(SequenceException exception) {
    super(exception);
  }

  public SequenceException(String dbName, String message) {
    super(dbName, message);
  }

  public SequenceException(String message) {
    super(message);
  }
}
