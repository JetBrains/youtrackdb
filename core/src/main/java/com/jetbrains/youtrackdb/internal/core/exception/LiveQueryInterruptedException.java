package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

/**
 *
 */
public class LiveQueryInterruptedException extends CoreException {

  public LiveQueryInterruptedException(CoreException exception) {
    super(exception);
  }

  public LiveQueryInterruptedException(DatabaseSessionEmbedded db, String message) {
    super(db, message);
  }
}
