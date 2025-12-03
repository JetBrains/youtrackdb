package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.ConfigurationParameters;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YTDBGraphFactory {

  private static final Logger logger = LoggerFactory.getLogger(YTDBGraphFactory.class);

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
    return ytdbInstance(dbPath, () -> YouTrackDBInternal.embedded(dbPath,
        YouTrackDBConfig.builder().fromApacheConfiguration(configuration).build(), false));
  }

  public static YouTrackDBImpl ytdbInstance(
      String dbPath, Supplier<YouTrackDBInternal<DatabaseSession>> internalSupplier) {
    var path = Paths.get(dbPath);
    try {
      Files.createDirectories(path);
      path = path.toRealPath();
    } catch (IOException e) {
      logger.error("Can not retrieve real path for {}", dbPath, e);
      path = path.toAbsolutePath().normalize();
    }

    return storagePathYTDBMap.compute(path, (p, youTrackDB) -> {
      if (youTrackDB != null && youTrackDB.isOpen()) {
        return youTrackDB;
      }

      return new YouTrackDBImpl(internalSupplier.get());
    });

  }

  @Nullable
  public static YouTrackDB getYTDBInstance(String dbPath) {
    var path = Path.of(dbPath);
    try {
      path = path.toRealPath();
    } catch (IOException e) {
      logger.error("Can not retrieve real path for {}", dbPath, e);
      path = path.toAbsolutePath().normalize();
    }

    var ytdb = storagePathYTDBMap.get(path);
    if (ytdb == null || !ytdb.isOpen()) {
      return null;
    }

    return ytdb;

  }

  public static void unregisterYTDBInstance(@Nonnull YouTrackDBImpl youTrackDB,
      @Nullable Runnable onCloseCallback) {
    var ytdbInternal = youTrackDB.internal;
    var path = Paths.get(ytdbInternal.getBasePath());
    try {
      path = path.toRealPath();
    } catch (IOException e) {
      logger.error("Can not retrieve real path for {}", path, e);
      path = path.toAbsolutePath().normalize();
    }

    storagePathYTDBMap.compute(path, (p, ytdb) -> {
      if (ytdb == youTrackDB) {
        if (onCloseCallback != null) {
          onCloseCallback.run();
        }

        return null;
      } else {
        throw new IllegalStateException(
            "There is another YTDB instance registered for the same path: " + p);
      }
    });
  }

  public static void closeAll() {
    var ytdbs = new HashMap<>(storagePathYTDBMap);
    ytdbs.values().forEach(ytdb -> {
      try {
        if (ytdb.isOpen()) {
          //will be removed from storagePathYTDBMap in [unregisterYTDBInstance]
          ytdb.close();
        }
      } catch (Exception e) {
        LogManager.instance()
            .error(YTDBGraphFactory.class, "Error closing of YouTrackDB instance", e);
      }
    });
  }

}
