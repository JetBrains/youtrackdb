package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;

/**
 *
 */
public class LiveQueryInterruptedException extends CoreException {

  public LiveQueryInterruptedException(CoreException exception) {
    super(exception);
  }

  public LiveQueryInterruptedException(DatabaseSession db, String message) {
    super(db, message);
  }
}
