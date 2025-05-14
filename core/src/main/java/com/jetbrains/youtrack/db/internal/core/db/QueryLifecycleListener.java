package com.jetbrains.youtrack.db.internal.core.db;

/**
 *
 */
public interface QueryLifecycleListener {
  void queryClosed(String id);
}
