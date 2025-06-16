package com.jetbrains.youtrack.db.internal.core.gremlin;


import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_INTEGRATE)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_COMPUTER)
@GraphFactoryClass(YTDBGraphFactory.class)
public final class YTDBGraphImpl extends YTDBSingleThreadGraphContainer {
  private final SessionPool<DatabaseSession> sessionPool;

  public YTDBGraphImpl(SessionPool<DatabaseSession> sessionPool, Configuration configuration) {
    super(configuration);
    this.sessionPool = sessionPool;
  }

  @Override
  public YTDBSingleThreadGraph initSingleThreadGraph() {
    return new YTDBSingleThreadGraph((DatabaseSessionEmbedded) sessionPool.acquire(), config,
        YTDBElementImplFactory.INSTANCE, this);
  }

  @Override
  protected void closeGraphs() throws Exception {
    super.closeGraphs();

    sessionPool.close();
  }

  @Override
  public boolean isOpen() {
    if (sessionPool.isClosed()) {
      return false;
    }

    return super.isOpen();
  }

  @Override
  public String toString() {
    return StringFactory.graphString(this, sessionPool.getDbName());
  }
}
