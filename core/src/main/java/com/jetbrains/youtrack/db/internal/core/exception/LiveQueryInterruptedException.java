package com.jetbrains.youtrack.db.internal.core.exception;

import com.jetbrains.youtrack.db.api.DatabaseSession;

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
