package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

/// Transaction-level listener that gets notified when a write transaction is finished. It won't get
/// called if the transaction was idempotent.
public interface TransactionMetricsListener {

  /// Called synchronously on the committing thread when a write transaction is committed,
  /// providing information about its runtime. The callback fires after the storage commit
  /// completes but before the transaction is closed.
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
  void writeTransactionCommitted(TransactionDetails txDetails, long commitAtMillis,
      long commitTimeNanos);

  /// A do nothing listener.
  TransactionMetricsListener NO_OP = (txDetails, commitAtMillis, commitTimeNanos) -> {

  };

  /// Details about the current transaction.
  interface TransactionDetails {

    /// Get transaction tracking id
    String getTransactionTrackingId();
  }

}
