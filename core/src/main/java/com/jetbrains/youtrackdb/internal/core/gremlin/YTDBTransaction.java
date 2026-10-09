package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.TransactionMetricsListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.util.AbstractTransaction;
import org.apache.tinkerpop.gremlin.structure.util.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class YTDBTransaction extends AbstractTransaction {

  private static final Logger logger = LoggerFactory.getLogger(YTDBTransaction.class);

  private Consumer<Transaction> readWriteConsumerInternal = READ_WRITE_BEHAVIOR.AUTO;
  private Consumer<Transaction> closeConsumerInternal = CLOSE_BEHAVIOR.ROLLBACK;

  private final CopyOnWriteArraySet<Consumer<Status>> transactionListeners =
      new CopyOnWriteArraySet<>();
  private final YTDBGraphImplAbstract graph;
  private DatabaseSessionEmbedded activeSession;
  private YTDBGraphImplAbstract.ThreadLocalState owningScope;
  private long activation;
  private Completion completion;
  private final Consumer<Status> sessionCloseListener = status -> closeCompletedSession();
  private TransactionRole transactionRole;
  private boolean strategyInspection;
  private int helperScopeDepth;

  // Monitoring generations separate completed settings from callback-created settings.
  private MonitoringState monitoring = new MonitoringState();

  public YTDBTransaction(YTDBGraphImplAbstract graph) {
    super(graph);
    this.graph = graph;
  }

  public static <X extends Exception> void executeInTX(
      FailableConsumer<YTDBGraphTraversalSource, X> code, YTDBGraphTraversalSource g) throws X {
    var ok = false;
    var tx = g.tx();
    var ytdbTx = tx instanceof YTDBTransaction localTx ? localTx : null;
    if (ytdbTx != null) {
      ytdbTx.enterHelperScope();
    }
    try {
      code.accept(tx.begin(YTDBGraphTraversalSource.class));
      ok = true;
    } finally {
      try {
        finishTx(ok, tx);
      } finally {
        if (ytdbTx != null) {
          ytdbTx.exitHelperScope();
        }
      }
    }
  }

  public static <X extends Exception> void executeInTX(
      FailableFunction<YTDBGraphTraversalSource, YTDBGraphTraversal<?, ?>, X> code,
      YTDBGraphTraversalSource g) throws X {
    var ok = false;
    var tx = g.tx();
    var ytdbTx = tx instanceof YTDBTransaction localTx ? localTx : null;
    if (ytdbTx != null) {
      ytdbTx.enterHelperScope();
    }
    try {
      var traversal = code.apply(tx.begin(YTDBGraphTraversalSource.class));
      traversal.iterate();
      ok = true;
    } finally {
      try {
        finishTx(ok, tx);
      } finally {
        if (ytdbTx != null) {
          ytdbTx.exitHelperScope();
        }
      }
    }
  }

  public static <X extends Exception, R> R computeInTx(
      FailableFunction<YTDBGraphTraversalSource, R, X> code, YTDBGraphTraversalSource g) throws X {
    var ok = false;
    R result;
    var tx = g.tx();
    var ytdbTx = tx instanceof YTDBTransaction localTx ? localTx : null;
    if (ytdbTx != null) {
      ytdbTx.enterHelperScope();
    }
    try {
      result = code.apply(tx.begin(YTDBGraphTraversalSource.class));
      ok = true;
    } finally {
      try {
        finishTx(ok, tx);
      } finally {
        if (ytdbTx != null) {
          ytdbTx.exitHelperScope();
        }
      }
    }
    return result;
  }

  private static void finishTx(boolean ok, Transaction tx) {
    if (tx.isOpen()) {
      if (ok) {
        try {
          tx.commit();
        } catch (Exception e) {
          logger.error("Failed to commit transaction", e);
          if (e instanceof RuntimeException re) {
            throw re;
          }
          throw new IllegalStateException("Failed to commit transaction", e);
        }
      } else {
        try {
          tx.rollback();
        } catch (Exception e) {
          logger.error("Failed to rollback transaction", e);
        }
      }
    }
  }

  /** Opens a transaction without making strategy inspection a caller operation. */
  public void readWriteForTraversalStrategy() {
    if (isOpen()) {
      return;
    }
    strategyInspection = true;
    try {
      readWrite();
    } finally {
      strategyInspection = false;
    }
  }

  /** Returns whether real caller work has claimed the active transaction. */
  public boolean hasCallerTransaction() {
    return isOpen() && transactionRole == TransactionRole.CALLER_ACTIVE;
  }

  public void markTransactionControlBegin() {
    if (isOpen()) {
      transactionRole = TransactionRole.CALLER_ACTIVE;
    }
  }

  private void enterHelperScope() {
    helperScopeDepth++;
    if (isOpen()) {
      transactionRole = TransactionRole.CALLER_ACTIVE;
    }
  }

  private void exitHelperScope() {
    helperScopeDepth--;
  }

  @Override
  public boolean isOpen() {
    if (activeSession != null) {
      return activeSession.isTxActive();
    }

    return false;
  }

  @Override
  public Transaction onReadWrite(Consumer<Transaction> consumer) {
    this.readWriteConsumerInternal =
        Optional.ofNullable(consumer)
            .orElseThrow(Exceptions::onReadWriteBehaviorCannotBeNull);
    return this;
  }

  @Override
  public Transaction onClose(Consumer<Transaction> consumer) {
    this.closeConsumerInternal =
        Optional.ofNullable(consumer)
            .orElseThrow(Exceptions::onReadWriteBehaviorCannotBeNull);
    return this;
  }

  @Override
  public void addTransactionListener(Consumer<Status> listener) {
    transactionListeners.add(listener);
  }

  @Override
  public void removeTransactionListener(Consumer<Status> listener) {
    transactionListeners.remove(listener);
  }

  @Override
  public void clearTransactionListeners() {
    transactionListeners.clear();
  }

  @Override
  protected void doClose() {
    closeConsumerInternal.accept(this);
  }

  @Override
  protected void doReadWrite() {
    readWriteConsumerInternal.accept(this);
    if (!strategyInspection && isOpen()) {
      transactionRole = TransactionRole.CALLER_ACTIVE;
    }
  }

  @Override
  protected void doOpen() {
    monitoringForConfiguration();
    var ok = false;
    try {
      activeSession = graph.getUnderlyingDatabaseSession();
      owningScope = graph.currentScope();
      activeSession.begin();
      activation++;
      transactionRole = strategyInspection && helperScopeDepth == 0
          ? TransactionRole.INSPECTION_ONLY
          : TransactionRole.CALLER_ACTIVE;
      ok = true;
    } finally {
      if (!ok) {
        activeSession = null;
        transactionRole = null;
      }
    }
  }

  @Override
  public void commit() {
    complete(true);
  }

  @Override
  public void rollback() {
    complete(false);
  }

  private void complete(boolean commit) {
    // Match AbstractTransaction's read-write, database step, and listener order.
    readWrite();
    var attempted = new Completion(activeSession, owningScope, activation, monitoring);
    var previousCompletion = completion;
    completion = attempted;
    try {
      if (commit) {
        doCommit();
      } else {
        doRollback();
      }
      if (commit) {
        fireOnCommit();
      } else {
        fireOnRollback();
      }
    } catch (RuntimeException | Error failure) {
      // A listener can reopen this transaction, even on the same pooled session.
      // Its session belongs to the newer activation, not this completion attempt.
      if (activation == attempted.activation()) {
        try {
          graph.closeCachedSession(attempted.scope(), attempted.session());
        } catch (RuntimeException | Error cleanupFailure) {
          if (failure != cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
      }
      throw failure;
    } finally {
      // Keep completed settings visible through notification and teardown. A callback can
      // replace this generation, even with identical values, without losing its settings.
      if (monitoring == attempted.monitoring()) {
        monitoring = new MonitoringState();
      }
      // A callback can complete a reopened activation. Restore the enclosing attempt.
      completion = previousCompletion;
    }
  }

  void addSessionCloseListener() {
    // One stable listener per transaction preserves registration order and set deduplication.
    addTransactionListener(sessionCloseListener);
  }

  private void closeCompletedSession() {
    var completed = completion;
    if (completed == null || activation != completed.activation()) {
      return;
    }
    var session = completed.session();
    if (session != null && session.isTxActive()) {
      throw new IllegalStateException("Transaction is still active");
    }
    graph.closeCachedSession(completed.scope(), session);
  }

  private record Completion(DatabaseSessionEmbedded session,
      YTDBGraphImplAbstract.ThreadLocalState scope, long activation, MonitoringState monitoring) {
  }

  @Override
  protected void doCommit() throws TransactionException {
    if (activeSession != null) {
      try {
        if (isTransactionMetricsEnabled()) {
          activeSession.monitoredCommit(
              monitoring.transactionMetricsListener,
              monitoring.queryMonitoringMode,
              getTrackingId());
        } else {
          activeSession.commit();
        }
      } finally {
        activeSession = null;
        transactionRole = null;
      }
    }
  }

  @Override
  protected void doRollback() throws TransactionException {
    if (activeSession != null) {
      try {
        activeSession.rollback();
      } finally {
        activeSession = null;
        transactionRole = null;
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public YTDBGraphTraversalSource begin() {
    return new YTDBGraphTraversalSource(graph);
  }

  @Override
  protected void fireOnCommit() {
    this.transactionListeners.forEach(c -> c.accept(Status.COMMIT));
  }

  @Override
  protected void fireOnRollback() {
    this.transactionListeners.forEach(c -> c.accept(Status.ROLLBACK));
  }

  private MonitoringState monitoringForConfiguration() {
    // Opening or configuring after the database step starts a fresh generation. Detach before
    // applying setters so configuration before open is retained without inheriting old fields.
    if (completion != null && activeSession == null && monitoring == completion.monitoring()) {
      monitoring = new MonitoringState();
    }
    return monitoring;
  }

  private static final class MonitoringState {

    private QueryMonitoringMode queryMonitoringMode = QueryMonitoringMode.LIGHTWEIGHT;
    private String trackingId;
    private QueryMetricsListener queryMetricsListener = QueryMetricsListener.NO_OP;
    private TransactionMetricsListener transactionMetricsListener =
        TransactionMetricsListener.NO_OP;
  }

  public DatabaseSessionEmbedded getDatabaseSession() {
    if (activeSession == null) {
      throw new IllegalStateException("Transaction is not active");
    }

    return activeSession;
  }

  /// Set the tracking ID for this transaction that can be obtained from the QueryDetails inside the
  /// listener. If not set, YTDB will generate its own tracking ID.
  public YTDBTransaction withTrackingId(@Nonnull String trackingId) {
    Objects.requireNonNull(trackingId);
    monitoringForConfiguration().trackingId = trackingId;
    return this;
  }

  /// Set the mode under which the listener will operate. Lightweight mode uses approximate
  /// timestamps and lower precision of durations. Exact mode uses precise timestamp values but is
  /// heavier performance-wise.
  public YTDBTransaction withQueryMonitoringMode(@Nonnull QueryMonitoringMode mode) {
    Objects.requireNonNull(mode);
    monitoringForConfiguration().queryMonitoringMode = mode;
    return this;
  }

  /// Register a query metrics listener for this transaction. Supported only when YouTrackDB is run
  /// in embedded mode.
  public YTDBTransaction withQueryListener(@Nonnull QueryMetricsListener listener) {
    Objects.requireNonNull(listener);
    monitoringForConfiguration().queryMetricsListener = listener;
    return this;
  }

  public boolean isQueryMetricsEnabled() {
    return monitoring.queryMetricsListener != null
        && monitoring.queryMetricsListener != QueryMetricsListener.NO_OP;
  }

  public @Nonnull QueryMonitoringMode getQueryMonitoringMode() {
    return monitoring.queryMonitoringMode;
  }

  public @Nonnull String getTrackingId() {
    return monitoring.trackingId != null ? monitoring.trackingId
        : String.valueOf(getDatabaseSession().getActiveTransaction().getId());
  }

  public QueryMetricsListener getQueryMetricsListener() {
    return monitoring.queryMetricsListener;
  }

  /// Register a metrics listener for this transaction. Supported only when YouTrackDB is run in
  /// embedded mode.
  public YTDBTransaction withTransactionListener(@Nonnull TransactionMetricsListener listener) {
    Objects.requireNonNull(listener);
    monitoringForConfiguration().transactionMetricsListener = listener;
    return this;
  }

  public boolean isTransactionMetricsEnabled() {
    return monitoring.transactionMetricsListener != null
        && monitoring.transactionMetricsListener != TransactionMetricsListener.NO_OP;
  }

  private enum TransactionRole {
    INSPECTION_ONLY, CALLER_ACTIVE
  }
}
