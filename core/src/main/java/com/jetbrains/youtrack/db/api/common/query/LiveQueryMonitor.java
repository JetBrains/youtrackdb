package com.jetbrains.youtrack.db.api.common.query;

/**
 *
 */
public interface LiveQueryMonitor {
  void unSubscribe();

  int getMonitorId();
}
