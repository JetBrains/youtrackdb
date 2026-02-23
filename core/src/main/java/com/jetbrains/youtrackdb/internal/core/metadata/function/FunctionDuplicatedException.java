package com.jetbrains.youtrackdb.internal.core.metadata.function;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;

/**
 * Thrown when attempting to register a function with a name that already exists.
 */
public class FunctionDuplicatedException extends BaseException {

  public FunctionDuplicatedException(String message) {
    super(message);
  }
}
