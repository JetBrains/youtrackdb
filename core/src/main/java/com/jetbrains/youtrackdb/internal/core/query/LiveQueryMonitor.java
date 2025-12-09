package com.jetbrains.youtrackdb.internal.core.query;

/**
 *
 */
public interface LiveQueryMonitor {

  void unSubscribe();

  int getMonitorId();
}
