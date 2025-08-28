package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.util.AbstractTransaction;
import org.apache.tinkerpop.gremlin.structure.util.TransactionException;

public final class YTDBTransaction extends AbstractTransaction {

  private Consumer<Transaction> readWriteConsumerInternal = READ_WRITE_BEHAVIOR.AUTO;
  private Consumer<Transaction> closeConsumerInternal = CLOSE_BEHAVIOR.ROLLBACK;

  private final CopyOnWriteArraySet<Consumer<Status>> transactionListeners = new CopyOnWriteArraySet<>();
  private final YTDBGraphImplAbstract graph;
  private DatabaseSessionEmbedded activeSession;

  public YTDBTransaction(YTDBGraphImplAbstract graph) {
    super(graph);
    this.graph = graph;
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
