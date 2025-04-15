package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.internal.core.exception.StorageException;

public class StorageExistsException extends StorageException implements HighLevelException {
  public StorageExistsException(StorageExistsException exception) {
    super(exception);
  }

  public StorageExistsException(String dbName, String string) {
    super(dbName, string);
  }
}
