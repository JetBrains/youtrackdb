package com.jetbrains.youtrackdb.api.exception;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;

public class CollectionDoesNotExistException extends StorageException
    implements HighLevelException {

  public CollectionDoesNotExistException(CollectionDoesNotExistException exception) {
    super(exception);
  }

  public CollectionDoesNotExistException(String dbName, String string) {
    super(dbName, string);
  }
}
