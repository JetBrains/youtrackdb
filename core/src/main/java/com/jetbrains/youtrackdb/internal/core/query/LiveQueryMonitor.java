package com.jetbrains.youtrackdb.internal.core.query;

/**
 * Handle for managing a live query subscription.
 */
public interface LiveQueryMonitor {

  void unSubscribe();

  int getMonitorId();
}
