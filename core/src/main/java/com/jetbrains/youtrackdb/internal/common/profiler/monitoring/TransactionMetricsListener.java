package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

/// Transaction-level listener that gets notified when a write transaction is finished. It won't get
/// called if the transaction was idempotent.
public interface TransactionMetricsListener {

  /// Called when a write transaction is commited, providing information about its runtime. If the
  /// transaction was a read-only one (without writing anything to the database), this method will
  /// not be called.
  ///
  /// @param txDetails       Provider of the transaction details.
  /// @param commitAtMillis  Exact or approximate (depending on the mode) timestamp of the
  ///                        transaction commit.
  /// @param commitTimeNanos Exact or approximate (depending on the mode) duration of the commit
  ///                        operation in nanoseconds.
  void writeTransactionCommited(TransactionDetails txDetails, long commitAtMillis,
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
