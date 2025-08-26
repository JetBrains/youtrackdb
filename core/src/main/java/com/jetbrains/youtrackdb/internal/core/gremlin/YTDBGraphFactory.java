package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.ConfigurationParameters;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;

public class YTDBGraphFactory {

  private static final ConcurrentHashMap<Path, YouTrackDBImpl> storagePathYTDBMap = new ConcurrentHashMap<>();

  /// This method is used in [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open a
  /// new graph instance.
  @SuppressWarnings("unused")
  public static Graph open(Configuration configuration) {
    try {
      var dbName = configuration.getString(ConfigurationParameters.CONFIG_DB_NAME);
      if (dbName == null) {
        throw new IllegalArgumentException(
            "YouTrackDB graph name is not specified : " + ConfigurationParameters.CONFIG_DB_NAME);
      }
      var user = configuration.getString(ConfigurationParameters.CONFIG_USER_NAME);
      if (user == null) {
        throw new IllegalArgumentException(
            "YouTrackDB user is not specified : " + ConfigurationParameters.CONFIG_USER_NAME);
      }
      var password = configuration.getString(ConfigurationParameters.CONFIG_USER_PWD);
      if (password == null) {
        throw new IllegalArgumentException(
            "YouTrackDB password is not specified : " + ConfigurationParameters.CONFIG_USER_PWD);
      }

      YouTrackDB youTrackDB;
      boolean shouldCloseYouTrackDB;
      String dbPath;

      dbPath = configuration.getString(ConfigurationParameters.CONFIG_DB_PATH);
      if (dbPath == null) {
        throw new IllegalArgumentException(
            "YouTrackDB path is not specified : " + ConfigurationParameters.CONFIG_DB_PATH);
      }

      var path = Path.of(dbPath).toAbsolutePath();
      var createIfNotExists = configuration.getBoolean(
          ConfigurationParameters.CONFIG_CREATE_IF_NOT_EXISTS,
          false);
      var currentYouTrackDB = ytdbInstance(dbPath, configuration);

      if (createIfNotExists && !currentYouTrackDB.exists(dbName)) {
        var dbTypeStr = configuration.getString(ConfigurationParameters.CONFIG_DB_TYPE);
        if (dbTypeStr == null) {
          throw new IllegalArgumentException(
              "YouTrackDB type is not specified : " + ConfigurationParameters.CONFIG_DB_TYPE);
        }
        var dbType = DatabaseType.valueOf(dbTypeStr.toUpperCase(Locale.ROOT));

        var userRole = configuration.getString(ConfigurationParameters.CONFIG_USER_ROLE);
        if (userRole == null) {
          throw new IllegalArgumentException(
              "YouTrackDB user role is not specified : "
                  + ConfigurationParameters.CONFIG_USER_ROLE);
        }

        currentYouTrackDB.createIfNotExists(dbName, dbType, user, password, userRole);
      }

      return currentYouTrackDB.openGraph(dbName, user, password, configuration);
    } finally {
      configuration.clearProperty(ConfigurationParameters.CONFIG_USER_PWD);
    }
  }

  public static YouTrackDBImpl ytdbInstance(String dbPath, Configuration configuration) {
    return ytdbInstance(() -> YouTrackDBInternal.embedded(dbPath,
        YouTrackDBConfig.builder().fromApacheConfiguration(configuration).build(), false));
  }

  public static YouTrackDBImpl ytdbInstance(
      Supplier<YouTrackDBInternal<DatabaseSession>> internalSupplier) {
    var dbPath = internalSupplier.get().getBasePath();
    var path = Path.of(dbPath).toAbsolutePath();

    return storagePathYTDBMap.compute(path, (p, youTrackDB) -> {
      if (youTrackDB != null) {
        return youTrackDB;
      }

      return new YouTrackDBImpl(internalSupplier.get());
    });
  }

  @Nullable
  public static YouTrackDB getYTDBInstance(String dbPath) {
    var path = Path.of(dbPath).toAbsolutePath();
    return storagePathYTDBMap.get(path);
  }

  public static void unregisterYTDBInstance(@Nonnull YouTrackDBImpl youTrackDB,
      @Nullable Runnable onCloseCallback) {
    var ytdbInternal = youTrackDB.internal;
    var path = Path.of(ytdbInternal.getBasePath()).toAbsolutePath();

    storagePathYTDBMap.compute(path, (p, ytdb) -> {
      if (ytdb == youTrackDB) {
        if (onCloseCallback != null) {
          onCloseCallback.run();
        }

        return null;
      }

      return ytdb;
    });
  }

  public static void closeAll() {
    var ytdbs = new HashMap<>(storagePathYTDBMap);
    ytdbs.values().forEach(ytdb -> {
      try {
        //will be removed from storagePathYTDBMap in [unregisterYTDBInstance]
        ytdb.close();
      } catch (Exception e) {
        LogManager.instance()
            .error(YTDBGraphFactory.class, "Error closing of YouTrackDB instance", e);
      }
    });
  }

}
