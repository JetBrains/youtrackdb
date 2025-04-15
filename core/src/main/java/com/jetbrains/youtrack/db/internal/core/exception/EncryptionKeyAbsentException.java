package com.jetbrains.youtrack.db.internal.core.exception;

public class EncryptionKeyAbsentException extends StorageException {

  public EncryptionKeyAbsentException(EncryptionKeyAbsentException exception) {
    super(exception);
  }

  public EncryptionKeyAbsentException(String dbName, String string) {
    super(dbName, string);
  }
}
