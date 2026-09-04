package com.jetbrains.youtrackdb.internal.core.exception;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

/** Reports that an index engine cannot be recovered through its durable descriptor identity. */
public final class StaleIndexEngineException extends CoreException implements HighLevelException {

  public StaleIndexEngineException(StaleIndexEngineException exception) {
    super(exception);
  }

  public StaleIndexEngineException(String message) {
    super(message);
  }

  public StaleIndexEngineException(String dbName, String message) {
    super(dbName, message);
  }

  public StaleIndexEngineException(DatabaseSessionEmbedded session, String message) {
    super(session, message);
  }
}
