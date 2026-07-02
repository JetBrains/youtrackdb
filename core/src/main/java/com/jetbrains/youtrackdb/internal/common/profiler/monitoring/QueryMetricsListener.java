package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
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

    /// The execution plan of the monitored query, or {@code null} if no plan was captured.
    ///
    /// A plan is present when the query ran a plan-backed source query (for example a Gremlin
    /// traversal starting from `g.V()` / `g.E()`); it is {@code null} for a lookup that runs no
    /// query (for example `g.V(id)`) and, when the per-transaction result cache is enabled
    /// (`QUERY_TX_RESULT_CACHE_ENABLED`, off by default), on a cache-hit replay of an identical
    /// query within the same transaction.
    ///
    /// The plan is for read-only inspection: read [ExecutionPlan#getSteps] or
    /// [ExecutionPlan#prettyPrint], and do not call [ExecutionPlan#toResult] — it needs a live
    /// session that is no longer valid after the query closed.
    ///
    /// The returned plan is valid only synchronously inside the
    /// [QueryMetricsListener#queryFinished] callback, the same window the lazily-resolved
    /// [#getQuery] accessor has: the source step resolves it and clears it on reset, so a listener
    /// must not retain the plan past the callback.
    @Nullable default ExecutionPlan getExecutionPlan() {
      return null;
    }
  }
}
