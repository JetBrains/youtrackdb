package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import java.util.function.Consumer;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction.Status;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;

@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@GraphFactoryClass(YTDBGraphFactory.class)
public class YTDBGraphImplSessionPool extends YTDBGraphImplAbstract implements Consumer<Status> {

  static {
    registerOptimizationStrategies(YTDBGraphImplSessionPool.class);
  }

  private final ThreadLocal<DatabaseSessionEmbedded> session = new ThreadLocal<>();
  private final SessionPool<DatabaseSession> sessionPool;

  public YTDBGraphImplSessionPool(SessionPool<DatabaseSession> sessionPool,
      Configuration configuration) {
    super(configuration);
    this.sessionPool = sessionPool;
  }

  @Override
  public void close() {
    var tx = tx();

    if (tx.isOpen()) {
      tx.close();
    }
    sessionPool.close();
  }

  @Override
  public DatabaseSessionEmbedded getUnderlyingDatabaseSession() {
    var currentSession = session.get();

    if (currentSession != null) {
      if (!currentSession.isTxActive()) {
        tx().addTransactionListener(this);
      }
      return currentSession;
    }

    currentSession = (DatabaseSessionEmbedded) sessionPool.acquire();
    tx().addTransactionListener(this);

    session.set(currentSession);

    return currentSession;
  }

  @Override
  public DatabaseSessionEmbedded peekUnderlyingDatabaseSession() {
    return session.get();
  }

  @Override
  public boolean isOpen() {
    return !sessionPool.isClosed();
  }

  @Override
  public void accept(Status status) {
    var currentSession = session.get();
    if (currentSession == null) {
      return;
    }

    if (currentSession.isTxActive()) {
      throw new IllegalStateException("Transaction is still active");
    }

    currentSession.close();
    session.remove();
  }

  @Override
  public boolean isSingleThreaded() {
    return false;
  }
}
