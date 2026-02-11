package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

public class InternalErrorException extends CoreException {

  public InternalErrorException(InternalErrorException exception) {
    super(exception);
  }

  public InternalErrorException(DatabaseSessionEmbedded db, String string) {
    super(db, string);
  }
}
