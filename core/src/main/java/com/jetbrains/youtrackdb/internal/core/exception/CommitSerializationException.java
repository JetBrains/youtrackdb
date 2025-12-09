package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

public class CommitSerializationException extends CoreException implements
    HighLevelException {

  public CommitSerializationException(CommitSerializationException exception) {
    super(exception);
  }

  public CommitSerializationException(String dbName, String message) {
    super(dbName, message);
  }
}
