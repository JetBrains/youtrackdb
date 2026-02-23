package com.jetbrains.youtrackdb.internal.core.exception;

/**
 * Exception thrown when a write cache operation fails.
 *
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
