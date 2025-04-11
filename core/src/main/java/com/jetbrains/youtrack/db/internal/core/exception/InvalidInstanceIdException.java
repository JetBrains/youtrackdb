package com.jetbrains.youtrack.db.internal.core.exception;

public class InvalidInstanceIdException extends StorageException {

  public InvalidInstanceIdException(String dbName, String string) {
    super(dbName, string);
  }
}
