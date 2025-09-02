package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;


public interface DatabasePoolInternal<S extends BasicDatabaseSession<?, ?>> extends AutoCloseable {
  S acquire();

  @Override
  void close();

  void release(S database);

  YouTrackDBConfigImpl getConfig();

  /**
   * Check if database pool is closed
   *
   * @return true if pool is closed
   */
  boolean isClosed();

  /**
   * Check that all resources owned by the pool are in the pool
   */
  boolean isUnused();

  /**
   * Check last time that a resource was returned to the pool
   */
  long getLastCloseTime();

  String getDatabaseName();

  String getUserName();
}
