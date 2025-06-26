package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.internal.core.exception.StorageException;

public class CollectionDoesNotExistException extends StorageException
    implements HighLevelException {

  public CollectionDoesNotExistException(CollectionDoesNotExistException exception) {
    super(exception);
  }

  public CollectionDoesNotExistException(String dbName, String string) {
    super(dbName, string);
  }
}
