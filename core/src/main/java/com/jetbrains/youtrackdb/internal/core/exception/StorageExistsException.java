package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

public class StorageExistsException extends StorageException implements HighLevelException {

  public StorageExistsException(StorageExistsException exception) {
    super(exception);
  }

  public StorageExistsException(String dbName, String string) {
    super(dbName, string);
  }
}
