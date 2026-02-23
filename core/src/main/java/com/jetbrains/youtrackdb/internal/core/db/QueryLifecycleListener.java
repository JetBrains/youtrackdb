package com.jetbrains.youtrackdb.internal.core.db;

/**
 * Listener that receives notifications about query lifecycle events.
 */
public interface QueryLifecycleListener {
  void queryClosed(String id);
}
