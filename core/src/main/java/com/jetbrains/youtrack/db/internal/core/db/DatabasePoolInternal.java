package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;

/**
 *
 */
public interface DatabasePoolInternal extends AutoCloseable {

  DatabaseSession acquire();

  @Override
  void close();

  void release(DatabaseSessionInternal database);

  YouTrackDBConfigImpl getConfig();

  /**
   * Check if database pool is closed
   *
   * @return true if pool is closed
   */
  boolean isClosed();

  /**
   * Check that all resources owned by the pool are in the pool
   *
   * @return
   */
  boolean isUnused();

  /**
   * Check last time that a resource was returned to the pool
   *
   * @return
   */
  long getLastCloseTime();
}
