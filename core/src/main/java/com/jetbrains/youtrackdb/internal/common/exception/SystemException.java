package com.jetbrains.youtrackdb.internal.common.exception;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;

/**
 * @since 9/28/2015
 */
public class SystemException extends BaseException {

  public SystemException(SystemException exception) {
    super(exception);
  }

  public SystemException(String message) {
    super(message);
  }
}
