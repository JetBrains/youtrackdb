package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

/**
 * Exception thrown when an operation is attempted while a backup is already in progress.
 *
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
