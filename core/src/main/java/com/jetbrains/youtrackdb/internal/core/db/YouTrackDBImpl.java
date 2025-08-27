package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrackdb.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

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

  @Override
  public void restore(String name, Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier, @Nullable String expectedUUID,
      YouTrackDBConfig config) {
    internal.restore(name, ibuFilesSupplier, ibuInputStreamSupplier,
        expectedUUID, config);
  }


}
