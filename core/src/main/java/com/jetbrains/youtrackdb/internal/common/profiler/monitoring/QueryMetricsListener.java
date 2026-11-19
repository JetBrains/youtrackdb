package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import io.opentelemetry.context.Context;
import java.util.Optional;
import java.util.OptionalLong;
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

  /// Callback-scoped query view. Copy owned scalar values with [QueryCompletion#capture].
  /// Do not retain this view, its plan or its Context. Query text is not a retained value.
  interface QueryDetails {

    /// Get the string representation of the query.
    String getQuery();

    /// Optional client-provided summary of the query.
    @Nullable String getQuerySummary();

    /// Transaction tracking identifier, or null when the operation has no transaction.
    /// A producer must not substitute a numeric placeholder for absence.
    @Nullable String getTransactionTrackingId();

    /// Explicit caller tracking identifier, absent when unset or without a transaction.
    default Optional<String> getExplicitTrackingId() {
      return Optional.empty();
    }

    /// Whole-call operation name, absent when classification cannot prove one operation.
    default Optional<String> getOperationName() {
      return Optional.empty();
    }

    /// Whole-call collection name, absent when classification cannot prove one collection.
    default Optional<String> getCollectionName() {
      return Optional.empty();
    }

    /// Database name captured for this operation, absent when unavailable.
    default Optional<String> getDatabaseName() {
      return Optional.empty();
    }

    /// Known outcome, absent when the producer does not report it.
    default Optional<CompletionOutcome> getOutcome() {
      return Optional.empty();
    }

    /// Failure class name, absent on success or when the outcome is unavailable.
    default Optional<String> getErrorType() {
      return getOutcome().flatMap(CompletionOutcome::errorType);
    }

    /// Returned rows, absent when unavailable. A known zero is present.
    default OptionalLong getResultCount() {
      return OptionalLong.empty();
    }

    /// Real start on the epoch timeline in nanoseconds, absent when unavailable.
    default OptionalLong getStartedAtEpochNanos() {
      return OptionalLong.empty();
    }

    /// Real end on the epoch timeline in nanoseconds, absent when unavailable.
    /// This endpoint includes consumer pauses and cleanup, unlike EXACT active time.
    default OptionalLong getFinishedAtEpochNanos() {
      return OptionalLong.empty();
    }

    /// Captured transaction threshold in nanoseconds, absent when unset. Zero is valid.
    default OptionalLong getSlowQueryThresholdOverrideNanos() {
      return OptionalLong.empty();
    }

    /// Entry-captured parent Context, absent when not captured.
    /// Context can contain live objects. Use it only within the synchronous callback.
    default Optional<Context> getParentContext() {
      return Optional.empty();
    }

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
