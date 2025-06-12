package com.jetbrains.youtrack.db.internal.core.gremlin;

import java.util.function.Consumer;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.util.AbstractTransaction;
import org.apache.tinkerpop.gremlin.structure.util.TransactionException;

public final class YTDBMultiThreadGraphTransaction extends AbstractTransaction {

  private final YTDBGraphImpl g;

  public YTDBMultiThreadGraphTransaction(YTDBGraphImpl graph) {
    super(graph);
    this.g = graph;
  }

  @Override
  protected void doOpen() {
    this.tx().doOpen();
  }

  @Override
  protected void doCommit() throws TransactionException {
    this.tx().doCommit();
  }

  @Override
  protected void doRollback() throws TransactionException {
    this.tx().doRollback();
  }

  @Override
  protected void fireOnCommit() {
    this.tx().fireOnCommit();
  }

  @Override
  protected void fireOnRollback() {
    this.tx().fireOnRollback();
  }

  @Override
  protected void doReadWrite() {
    this.tx().doReadWrite();
  }

  @Override
  protected void doClose() {
    this.tx().doClose();
  }

  @Override
  public Transaction onReadWrite(Consumer<Transaction> consumer) {
    return this.tx().onReadWrite(consumer);
  }

  @Override
  public Transaction onClose(Consumer<Transaction> consumer) {
    return this.tx().onClose(consumer);
  }

  @Override
  public void addTransactionListener(Consumer<Status> listener) {
    this.tx().addTransactionListener(listener);
  }

  @Override
  public void removeTransactionListener(Consumer<Status> listener) {
    this.tx().removeTransactionListener(listener);
  }

  @Override
  public void clearTransactionListeners() {
    this.tx().clearTransactionListeners();
  }

  @Override
  public boolean isOpen() {
    return g.isOpen() && this.tx().isOpen();
  }

  private YTDBSingleThreadGraphTransaction tx() {
    return ((YTDBSingleThreadGraph) g.graph()).tx();
  }
}
