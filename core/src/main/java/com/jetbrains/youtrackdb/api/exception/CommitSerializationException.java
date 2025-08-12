package com.jetbrains.youtrackdb.api.exception;

import com.jetbrains.youtrackdb.internal.core.exception.CoreException;

public class CommitSerializationException extends CoreException implements
    HighLevelException {

  public CommitSerializationException(CommitSerializationException exception) {
    super(exception);
  }

  public CommitSerializationException(String dbName, String message) {
    super(dbName, message);
  }
}
