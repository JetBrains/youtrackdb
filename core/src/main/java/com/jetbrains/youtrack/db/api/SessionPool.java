package com.jetbrains.youtrack.db.api;

import com.jetbrains.youtrack.db.api.exception.AcquireTimeoutException;

public interface SessionPool extends AutoCloseable {

  DatabaseSession acquire() throws AcquireTimeoutException;

  boolean isClosed();

  @Override
  void close();
}
