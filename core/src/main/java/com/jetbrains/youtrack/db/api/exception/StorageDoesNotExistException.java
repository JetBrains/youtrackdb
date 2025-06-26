package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.internal.core.exception.StorageException;

public class StorageDoesNotExistException extends StorageException
    implements HighLevelException {

  public StorageDoesNotExistException(StorageDoesNotExistException exception) {
    super(exception);
  }

  public StorageDoesNotExistException(String dbName, String string) {
    super(dbName, string);
  }
}
