package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;

final class RollbackException extends BaseException implements HighLevelException {

  public RollbackException() {
    super("");
  }

  public RollbackException(String message) {
    super(message);
  }

  public RollbackException(RollbackException exception) {
    super(exception);
  }
}
