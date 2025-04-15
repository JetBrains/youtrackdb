package com.jetbrains.youtrack.db.internal.core.exception;

/**
 * @since 9/28/2015
 */
public class WriteCacheException extends CoreException {

  public WriteCacheException(WriteCacheException exception) {
    super(exception);
  }

  public WriteCacheException(String dbName, String message) {
    super(dbName, message);
  }

  public WriteCacheException(String message) {
    super(message);
  }
}
