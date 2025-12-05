package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;

public class SequenceLimitReachedException extends BaseException implements HighLevelException {

  public SequenceLimitReachedException(String message) {
    super(message);
  }
}
