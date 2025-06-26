package com.jetbrains.youtrack.db.internal.core.exception;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.IndexAbstract;

/**
 * Exception which is thrown by core components to ask command handler to rebuild and run executed
 * command again.
 *
 * @see IndexAbstract#getRebuildVersion()
 */
public abstract class RetryQueryException extends CoreException {
  public RetryQueryException(RetryQueryException exception) {
    super(exception);
  }

  public RetryQueryException(DatabaseSessionInternal db, String message) {
    super(db, message);
  }
}
