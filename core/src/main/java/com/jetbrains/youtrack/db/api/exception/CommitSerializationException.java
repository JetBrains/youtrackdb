package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.internal.core.exception.CoreException;

public class CommitSerializationException extends CoreException implements
    HighLevelException {

  public CommitSerializationException(CommitSerializationException exception) {
    super(exception);
  }

  public CommitSerializationException(String dbName, String message) {
    super(dbName, message);
  }
}
