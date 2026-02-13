package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

/// Controls the precision of query monitoring.
///
/// @see QueryMetricsListener
/// @see TransactionMetricsListener
public enum QueryMonitoringMode {

  /// Lightweight monitoring mode that uses approximate time and does not affect performance.
  LIGHTWEIGHT,

  /// Exact monitoring mode that uses precise time and may affect performance.
  EXACT
}
