package com.jetbrains.youtrackdb.internal.core.exception;

public class EncryptionKeyAbsentException extends StorageException {

  public EncryptionKeyAbsentException(EncryptionKeyAbsentException exception) {
    super(exception);
  }

  public EncryptionKeyAbsentException(String dbName, String string) {
    super(dbName, string);
  }
}
