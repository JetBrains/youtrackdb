package com.jetbrains.youtrackdb.internal.core.exception;

public class InvalidInstanceIdException extends StorageException {

  public InvalidInstanceIdException(String dbName, String string) {
    super(dbName, string);
  }
}
