package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;

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
