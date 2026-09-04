package com.jetbrains.youtrackdb.internal.core.exception;

/** Reports that an index handle expects a different engine at a registered slot. */
public final class IndexEngineReplacedException extends InvalidIndexEngineIdException {

  public IndexEngineReplacedException(String message) {
    super(message);
  }
}
