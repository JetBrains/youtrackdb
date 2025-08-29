package com.jetbrains.youtrackdb.internal.core.db;

/**
 *
 */
public interface QueryLifecycleListener {
  void queryClosed(String id);
}
