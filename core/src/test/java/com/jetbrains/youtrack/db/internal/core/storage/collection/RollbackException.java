package com.jetbrains.youtrack.db.internal.core.storage.collection;

import com.jetbrains.youtrack.db.api.exception.HighLevelException;
import com.jetbrains.youtrack.db.api.exception.BaseException;

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
