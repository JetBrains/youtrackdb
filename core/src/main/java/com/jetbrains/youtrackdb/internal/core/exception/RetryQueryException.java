package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;

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
