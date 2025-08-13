package com.jetbrains.youtrackdb.api.exception;

import com.jetbrains.youtrackdb.internal.core.exception.CoreException;

/**
 * @since 10/5/2015
 */
public class BackupInProgressException extends CoreException implements HighLevelException {
  public BackupInProgressException(BackupInProgressException exception) {
    super(exception);
  }

  public BackupInProgressException(String dbName, String message) {
    super(dbName, message);
  }
}
