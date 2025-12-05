package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

public class NoTxRecordReadException extends DatabaseException implements HighLevelException {

  public NoTxRecordReadException(String dbName, String message) {
    super(dbName, message);
  }

  public NoTxRecordReadException(String message) {
    super(message);
  }

  public NoTxRecordReadException(NoTxRecordReadException exception) {
    super(exception);
  }
}
