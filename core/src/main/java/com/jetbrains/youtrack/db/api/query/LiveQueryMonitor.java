package com.jetbrains.youtrack.db.api.query;

/**
 *
 */
public interface LiveQueryMonitor {
  void unSubscribe();

  int getMonitorId();
}
