package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrain.youtrack.db.gremlin.api.YTDBGraphFactory;
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import org.apache.commons.configuration2.Configuration;

public final class YTDBSingleThreadGraphFactoryImpl implements AutoCloseable,
    YTDBSingleThreadGraphFactory {

  private final String dbName;
  private final YouTrackDB youTrackDB;
  private final SessionPool<DatabaseSession> pool;
  private final Configuration configuration;

  public YTDBSingleThreadGraphFactoryImpl(final YouTrackDB youTrackDB, final Configuration config) {
    dbName = config.getString(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME);

    var user = config.getString(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER);
    var password = config.getString(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER_PWD);

    this.pool = youTrackDB.cachedPool(dbName, user, password,
        GremlinUtils.createYTDBConfig(config));
    this.configuration = config;
    this.youTrackDB = youTrackDB;
  }

  @Override
  public YTDBSingleThreadGraph openGraph() {
    return new YTDBSingleThreadGraph(this, (DatabaseSessionEmbedded) pool.acquire(), configuration);
  }


  @Override
  public void close() {
    if (pool != null) {
      pool.close();
    }
  }

  @Override
  public String toString() {
    return YTDBGraph.class.getSimpleName()
        + "[" + dbName + "]";
  }

  @Override
  public boolean isOpen() {
    return youTrackDB.isOpen();
  }

  @Override
  public YouTrackDB getYouTrackDB() {
    return youTrackDB;
  }

  @Override
  public String getDatabaseName() {
    return dbName;
  }
}
