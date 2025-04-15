package com.jetbrains.youtrack.db.internal.core.exception;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;

public class InternalErrorException extends CoreException {

  public InternalErrorException(InternalErrorException exception) {
    super(exception);
  }

  public InternalErrorException(DatabaseSessionInternal db, String string) {
    super(db, string);
  }
}
