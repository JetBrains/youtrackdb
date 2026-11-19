package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import java.util.Optional;

/// Transaction-level listener that gets notified when a write transaction finishes (either
/// successfully or with a failure). It won't get called if the transaction was idempotent.
///
/// Both methods have default no-op implementations so that clients can override only the
/// callbacks they care about.
public interface TransactionMetricsListener {

  /// Called synchronously on the committing thread when a write transaction is committed
  /// successfully, providing information about its runtime. The callback fires after the
  /// storage commit completes but before the transaction is closed.
  ///
  /// This method will not be called if:
  /// - the transaction was read-only (no writes to the database), or
  /// - the commit is an inner commit of a nested transaction (only the outermost commit that
  ///   actually flushes to storage triggers the callback).
  ///
  /// @param txDetails       Provider of the transaction details.
  /// @param commitAtMillis  Exact or approximate (depending on the mode) timestamp of the
  ///                        transaction commit.
  /// @param commitTimeNanos Exact or approximate (depending on the mode) duration of the commit
  ///                        operation in nanoseconds.
  default void writeTransactionCommitted(TransactionDetails txDetails, long commitAtMillis,
      long commitTimeNanos) {
  }

  /// Called synchronously on the committing thread when a write transaction fails to commit
  /// (e.g., due to a concurrent modification conflict). The callback fires after the internal
  /// rollback but before the exception is rethrown to the caller.
  ///
  /// @param txDetails       Provider of the transaction details.
  /// @param commitAtMillis  Exact or approximate (depending on the mode) timestamp when the
  ///                        commit was attempted.
  /// @param commitTimeNanos Exact or approximate (depending on the mode) duration from commit
  ///                        start to the failure, in nanoseconds.
  /// @param cause           The exception that caused the commit to fail.
  default void writeTransactionFailed(TransactionDetails txDetails, long commitAtMillis,
      long commitTimeNanos, Exception cause) {
  }

  /// A do-nothing listener.
  TransactionMetricsListener NO_OP = new TransactionMetricsListener() {
  };

  /// Callback-scoped transaction view. Owned strings may be copied and retained.
  /// The failure callback's exception, message and cause chain are callback-scoped.
  /// Retain failure only as [CompletionOutcome], never as an exception.
  interface TransactionDetails {

    /// Get transaction tracking id
    String getTransactionTrackingId();

    /// Database name captured for the commit, absent when unavailable.
    default Optional<String> getDatabaseName() {
      return Optional.empty();
    }

    /// Explicit caller tracking identifier, absent when unset.
    default Optional<String> getExplicitTrackingId() {
      return Optional.empty();
    }
  }
}
