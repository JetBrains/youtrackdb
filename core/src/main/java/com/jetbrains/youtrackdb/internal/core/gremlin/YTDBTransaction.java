package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
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

  private final CopyOnWriteArraySet<Consumer<Status>> transactionListeners = new CopyOnWriteArraySet<>();
  private final YTDBGraphImplAbstract graph;
  private DatabaseSessionEmbedded activeSession;

  public YTDBTransaction(YTDBGraphImplAbstract graph) {
    super(graph);
    this.graph = graph;
  }

  public static <X extends Exception> void executeInTX(
      FailableConsumer<YTDBGraphTraversalSource, X> code, YTDBGraphTraversalSource g) throws X {
    var ok = false;
    var tx = g.tx();
    try {
      code.accept(tx.begin(YTDBGraphTraversalSource.class));
      ok = true;
    } finally {
      finishTx(ok, tx);
    }
  }

  public static <X extends Exception> void executeInTX(
      FailableFunction<YTDBGraphTraversalSource, YTDBGraphTraversal<?, ?>, X> code,
      YTDBGraphTraversalSource g) throws X {
    var ok = false;
    var tx = g.tx();
    try {
      var traversal = code.apply(tx.begin(YTDBGraphTraversalSource.class));
      traversal.iterate();
      ok = true;
    } finally {
      finishTx(ok, tx);
    }
  }


  public static <X extends Exception, R> R computeInTx(
      FailableFunction<YTDBGraphTraversalSource, R, X> code, YTDBGraphTraversalSource g) throws X {
    var ok = false;
    R result;
    var tx = g.tx();
    try {
      result = code.apply(tx.begin(YTDBGraphTraversalSource.class));
      ok = true;
    } finally {
      finishTx(ok, tx);
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
  }

  @Override
  protected void doOpen() {
    var ok = false;
    try {
      activeSession = graph.getUnderlyingDatabaseSession();
      activeSession.begin();
      ok = true;
    } finally {
      if (!ok) {
        activeSession = null;
      }
    }
  }

  @Override
  protected void doCommit() throws TransactionException {
    if (activeSession != null) {
      try {
        activeSession.commit();
      } finally {
        activeSession = null;
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

  public DatabaseSessionEmbedded getDatabaseSession() {
    if (activeSession == null) {
      throw new IllegalStateException("Transaction is not active");
    }

    return activeSession;
  }
}
