package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/// Owned scalar completion values. Safe to retain independently of the callback view.
/// Producers bind identity and transaction choices at entry and reuse them at completion.
/// No query text, parameter values, plan, Context, result or transaction belongs here.
public record QueryCompletion(
    Optional<String> transactionTrackingId,
    Optional<String> explicitTrackingId,
    Optional<String> querySummary,
    Optional<String> operationName,
    Optional<String> collectionName,
    Optional<String> databaseName,
    Optional<CompletionOutcome> outcome,
    OptionalLong resultCount,
    OptionalLong startedAtEpochNanos,
    OptionalLong finishedAtEpochNanos,
    OptionalLong slowQueryThresholdOverrideNanos) {

  public QueryCompletion {
    Objects.requireNonNull(transactionTrackingId);
    Objects.requireNonNull(explicitTrackingId);
    Objects.requireNonNull(querySummary);
    Objects.requireNonNull(operationName);
    Objects.requireNonNull(collectionName);
    Objects.requireNonNull(databaseName);
    Objects.requireNonNull(outcome);
    Objects.requireNonNull(resultCount);
    Objects.requireNonNull(startedAtEpochNanos);
    Objects.requireNonNull(finishedAtEpochNanos);
    Objects.requireNonNull(slowQueryThresholdOverrideNanos);
  }

  /// Copy scalar accessors during the callback, without resolving query text or a live plan.
  /// An empty optional means unavailable, not zero or a successful operation.
  public static QueryCompletion capture(QueryMetricsListener.QueryDetails details) {
    return new QueryCompletion(
        Optional.ofNullable(details.getTransactionTrackingId()),
        details.getExplicitTrackingId(),
        Optional.ofNullable(details.getQuerySummary()),
        details.getOperationName(), details.getCollectionName(), details.getDatabaseName(),
        details.getOutcome(), details.getResultCount(), details.getStartedAtEpochNanos(),
        details.getFinishedAtEpochNanos(), details.getSlowQueryThresholdOverrideNanos());
  }
}
