package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.RemoteYouTrackDB;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.util.Map;

public class YouTrackDBRemoteImpl extends
    YouTrackDBAbstract<RemoteResult, RemoteDatabaseSession> implements
    RemoteYouTrackDB {

  public YouTrackDBRemoteImpl(YouTrackDBInternal<RemoteDatabaseSession> internal) {
    super(internal);
  }

  @Override
  public LiveQueryMonitor live(String databaseName, String user, String password, String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener,
      Map<String, ?> args) {
    return live(databaseName, user, password, YouTrackDBConfig.defaultConfig(), query, listener,
        args);
  }

  @Override
  public LiveQueryMonitor live(String databaseName, String user, String password,
      YouTrackDBConfig config, String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult>
          listener, Object... args) {
    var pool = internal.openPool(databaseName, user, password, config);

    try (var session = (RemoteDatabaseSessionInternal) pool.acquire()) {
      var orchestrator = session.getCommandOrchestrator();
      return orchestrator.live(pool, query, listener, args);
    }
  }

  @Override
  public LiveQueryMonitor live(String databaseName, String user, String password,
      YouTrackDBConfig config, String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener,
      Map<String, ?> args) {
    var pool = internal.openPool(databaseName, user, password, config);

    try (var session = (RemoteDatabaseSessionInternal) pool.acquire()) {
      var orchestrator = session.getCommandOrchestrator();
      return orchestrator.live(pool, query, listener, args);
    }
  }

  @Override
  public LiveQueryMonitor live(String databaseName, String user, String password, String query,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener, Object... args) {
    return live(databaseName, user, password, YouTrackDBConfig.defaultConfig(), query, listener,
        args);
  }
}
