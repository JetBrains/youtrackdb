package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

public class StorageDoesNotExistException extends StorageException
    implements HighLevelException {

  public StorageDoesNotExistException(StorageDoesNotExistException exception) {
    super(exception);
  }

  public StorageDoesNotExistException(String dbName, String string) {
    super(dbName, string);
  }
}
