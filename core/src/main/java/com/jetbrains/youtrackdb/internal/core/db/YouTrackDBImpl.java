package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;

public class YouTrackDBImpl extends YouTrackDBAbstract<Result, DatabaseSession> implements
    YouTrackDB {

  public YouTrackDBImpl(YouTrackDBInternal<DatabaseSession> internal) {
    super(internal);
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
  public void restore(@Nonnull String databaseName,
      @Nonnull String path) {
    internal.restore(databaseName, null, null, path, null, null);
  }

  @Override
  public void restore(@Nonnull String databaseName,
      @Nonnull String path,
      @Nullable String expectedUUID, @Nonnull Configuration config) {
    internal.restore(databaseName, null, null, path, expectedUUID,
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
  }

  @Override
  public void restore(@Nonnull String databaseName,
      @Nonnull Supplier<Iterator<String>> ibuFilesSupplier,
      @Nonnull Function<String, InputStream> ibuInputStreamSupplier, @Nullable String expectedUUID,
      @Nonnull Configuration config) {
    internal.restore(databaseName, ibuFilesSupplier, ibuInputStreamSupplier, expectedUUID,
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
  }

  @Override
  public void close() {
    YTDBGraphFactory.unregisterYTDBInstance(this, super::close);
  }

  @Override
  public String toString() {
    return "youtrackdb:" + internal.getBasePath();
  }
}
