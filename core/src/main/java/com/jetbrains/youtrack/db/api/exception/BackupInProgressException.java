package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.internal.common.exception.ErrorCode;
import com.jetbrains.youtrack.db.internal.core.exception.CoreException;

/**
 * @since 10/5/2015
 */
public class BackupInProgressException extends CoreException implements HighLevelException {

  public BackupInProgressException(BackupInProgressException exception) {
    super(exception);
  }

  public BackupInProgressException(String dbName, String message, String componentName,
      ErrorCode errorCode) {
    super(dbName, message, componentName, errorCode);
  }
}
