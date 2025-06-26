package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.storage.disk.DiskStorage;
import java.util.Map;

public class YouTrackDBImpl extends YouTrackDBAbstract<Result, DatabaseSession> implements
    YouTrackDB {

  public YouTrackDBImpl(YouTrackDBInternal<DatabaseSession> internal) {
    super(internal);
  }

  @Override
  public LiveQueryMonitor live(String databaseName, String user, String password, String query,
      BasicLiveQueryResultListener<DatabaseSession, Result> listener, Map<String, ?> args) {
    return live(databaseName, user, password, YouTrackDBConfig.defaultConfig(), query, listener,
        args);
  }

  @Override
  public LiveQueryMonitor live(String databaseName, String user, String password,
      YouTrackDBConfig config, String query, BasicLiveQueryResultListener<DatabaseSession, Result>
          listener, Object... args) {
    var pool = internal.openPool(databaseName, user, password, config);

    try (var session = (DatabaseSessionInternal) pool.acquire()) {
      var storage = (DiskStorage) session.getStorage();
      return storage.live(pool, query, listener, args);
    }
  }

  @Override
  public LiveQueryMonitor live(String databaseName, String user, String password,
      YouTrackDBConfig config, String query,
      BasicLiveQueryResultListener<DatabaseSession, Result> listener, Map<String, ?> args) {
    var pool = internal.openPool(databaseName, user, password, config);

    try (var session = (DatabaseSessionInternal) pool.acquire()) {
      var storage = (DiskStorage) session.getStorage();
      return storage.live(pool, query, listener, args);
    }
  }

  @Override
  public LiveQueryMonitor live(String databaseName, String user, String password, String query,
      BasicLiveQueryResultListener<DatabaseSession, Result> listener, Object... args) {
    return live(databaseName, user, password, YouTrackDBConfig.defaultConfig(), query, listener,
        args);
  }

  @Override
  public ResultSet execute(String script, Map<String, Object> params) {
    return (ResultSet) super.execute(script, params);
  }

  @Override
  public ResultSet execute(String script, Object... params) {
    return (ResultSet) super.execute(script, params);
  }
}
