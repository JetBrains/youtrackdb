package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBElementWrapperFactory;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBSingleThreadGraph;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBSingleThreadGraphContainer;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.util.Objects;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public class YTDBServerGraphImpl extends YTDBSingleThreadGraphContainer {
  private final YouTrackDBServer youTrackDBServer;
  private final YTDBGraphManager graphManager;
  private final String databaseName;

  public YTDBServerGraphImpl(String databaseName, Configuration config,
      YouTrackDBServer youTrackDBServer,
      YTDBGraphManager graphManager) {
    super(config);

    this.youTrackDBServer = youTrackDBServer;
    this.graphManager = graphManager;
    this.databaseName = databaseName;
  }

  @Override
  public YTDBSingleThreadGraph initSingleThreadGraph() {
    var currentUser = graphManager.getCurrentUser();
    if (currentUser == null) {
      throw new IllegalStateException("User is not authenticated");
    }

    var userName = currentUser.getName();
    var sessionPool = youTrackDBServer.getDatabases()
        .cachedPoolNoAuthentication(databaseName, userName,
            youTrackDBServer.getDatabases().getConfiguration());

    return new YTDBSingleThreadGraph((DatabaseSessionEmbedded) sessionPool.acquire(), config,
        YTDBElementWrapperFactory.INSTANCE, this);
  }

  @Override
  protected boolean validateGraph(YTDBSingleThreadGraph graph) {
    var session = graph.getUnderlyingDatabaseSession();
    var currentUser = graphManager.getCurrentUser();

    if (currentUser == null) {
      return false;
    }

    var dbUser = session.getCurrentUserName();
    var currentUserName = currentUser.getName();

    return Objects.equals(currentUserName, dbUser);
  }

  @Override
  public String toString() {
    return StringFactory.graphString(this, databaseName);
  }
}
