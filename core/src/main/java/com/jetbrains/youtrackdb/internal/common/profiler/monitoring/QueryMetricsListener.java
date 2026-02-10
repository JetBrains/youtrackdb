package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import javax.annotation.Nullable;

/// Transaction-level listener that gets notified when a query is finished.
public interface QueryMetricsListener {

  /// Called when a query is finished, providing information about its runtime.
  ///
  /// @param queryDetails       Provider of the query details.
  /// @param startedAtMillis    Exact or approximate (depending on the mode) timestamp of the query
  ///                           start.
  /// @param executionTimeNanos Exact or approximate (depending on the mode) duration of the query
  ///                           in nanoseconds.
  void queryFinished(QueryDetails queryDetails, long startedAtMillis, long executionTimeNanos);

  /// A do nothing listener.
  QueryMetricsListener NO_OP = (queryDetails, startedAtMillis, executionTimeNanos) -> {
  };

  /// Details about the current query.
  interface QueryDetails {

    /// Get the string representation of the query.
    String getQuery();

    /// Optional client-provided summary of the query.
    @Nullable String getQuerySummary();

    /// Get transaction tracking id
    String getTransactionTrackingId();
  }
}
