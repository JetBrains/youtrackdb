package com.jetbrains.youtrackdb.internal.core.db;

public interface PooledSession {

  boolean isBackendClosed();

  void reuse();

  void realClose();
}
