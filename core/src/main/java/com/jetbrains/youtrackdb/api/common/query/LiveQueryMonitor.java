package com.jetbrains.youtrackdb.api.common.query;

/**
 *
 */
public interface LiveQueryMonitor {
  void unSubscribe();

  int getMonitorId();
}
