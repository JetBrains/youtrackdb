package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.SessionPool;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;

@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@GraphFactoryClass(YTDBGraphFactory.class)
public class YTDBGraphImplSessionPool extends YTDBGraphImplAbstract {
  static {
    registerOptimizationStrategies(YTDBGraphImplSessionPool.class);
  }

  private final SessionPool<DatabaseSession> sessionPool;

  public YTDBGraphImplSessionPool(SessionPool<DatabaseSession> sessionPool,
      Configuration configuration) {
    super(configuration);
    this.sessionPool = sessionPool;
  }

  @Override
  public void close() {
    super.close();
    sessionPool.close();
  }

  @Override
  public boolean isOpen() {
    return !sessionPool.isClosed();
  }

  @Override
  public DatabaseSessionEmbedded acquireSession() {
    return (DatabaseSessionEmbedded) sessionPool.acquire();
  }

}
