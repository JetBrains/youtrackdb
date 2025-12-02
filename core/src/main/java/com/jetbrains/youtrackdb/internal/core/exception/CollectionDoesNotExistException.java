package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

public class CollectionDoesNotExistException extends StorageException
    implements HighLevelException {

  public CollectionDoesNotExistException(CollectionDoesNotExistException exception) {
    super(exception);
  }

  public CollectionDoesNotExistException(String dbName, String string) {
    super(dbName, string);
  }
}
