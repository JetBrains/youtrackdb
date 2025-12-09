package com.jetbrains.youtrackdb.internal.core.metadata.function;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;

/**
 *
 */
public class FunctionDuplicatedException extends BaseException {

  public FunctionDuplicatedException(String message) {
    super(message);
  }
}
