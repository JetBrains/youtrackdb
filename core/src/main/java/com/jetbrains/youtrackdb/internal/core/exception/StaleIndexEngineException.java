package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

/** Reports stale or incomplete index engine ownership. A retry must start fresh work. */
public final class StaleIndexEngineException extends NeedRetryException
    implements HighLevelException {

  public StaleIndexEngineException(StaleIndexEngineException exception) {
    super(exception, exception.getErrorCode());
  }

  public StaleIndexEngineException(String message) {
    super((String) null, message);
  }

  public StaleIndexEngineException(String dbName, String message) {
    super(dbName, message);
  }

  public StaleIndexEngineException(DatabaseSessionEmbedded session, String message) {
    super(session != null ? session.getDatabaseName() : null, message);
  }
}
