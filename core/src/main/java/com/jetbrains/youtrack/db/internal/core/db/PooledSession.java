package com.jetbrains.youtrack.db.internal.core.db;

public interface PooledSession {

  boolean isBackendClosed();

  void reuse();

  void realClose();
}
