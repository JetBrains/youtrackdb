package com.jetbrains.youtrack.db.api.common;

import com.jetbrains.youtrack.db.api.exception.AcquireTimeoutException;

public interface SessionPool<S extends BasicDatabaseSession<?, ?>> extends AutoCloseable {
  S acquire() throws AcquireTimeoutException;

  boolean isClosed();

  void close();
}
