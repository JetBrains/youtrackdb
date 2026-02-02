package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import java.io.IOException;
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

  /// Path to the root folder that contains all embedded databases managed by [YouTrackDB], this
  /// parameter is used only in [org.apache.tinkerpop.gremlin.structure.util.GraphFactory]
  public static final String CONFIG_DB_PATH = "youtrackdb.embedded.path";
  /// Name of the embedded database to open, this parameter is used only in
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory].
  public static final String CONFIG_DB_NAME = "youtrackdb.database.name";
  /// Type of the embedded database to open, this parameter is used only in
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory]
  public static final String CONFIG_DB_TYPE = "youtrackdb.database.type";
  /// Current username, this parameter is used only in
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open the graph instance.
  public static final String CONFIG_USER_NAME = "youtrackdb.user.name";
  /// User role that will be created during database creation, this parameter is used only in
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory].
  public static final String CONFIG_USER_ROLE = "youtrackdb.user.role";
  /// Current user password, this parameter is used only in
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory].
  public static final String CONFIG_USER_PWD = "youtrackdb.user.pwd";
  /// This parameter indicates if a database should be created during the opening of the graph
  /// instance. This parameter is used only in
  /// [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open the [YTDBGraph] instance.
  public static final String CONFIG_CREATE_IF_NOT_EXISTS = "youtrackdb.database.createIfNotExists";
  private static final ConcurrentHashMap<Path, YouTrackDBImpl> storagePathYTDBMap = new ConcurrentHashMap<>();

  /// This method is used in [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open a
  /// new graph instance.
  @SuppressWarnings("unused")
  public static Graph open(Configuration configuration) {
    try {
      var dbName = configuration.getString(CONFIG_DB_NAME);
      if (dbName == null) {
        throw new IllegalArgumentException(
            "YouTrackDB graph name is not specified : "
                + CONFIG_DB_NAME);
      }
      var user = configuration.getString(CONFIG_USER_NAME);
      if (user == null) {
        throw new IllegalArgumentException(
            "YouTrackDB user is not specified : "
                + CONFIG_USER_NAME);
      }
      var password = configuration.getString(CONFIG_USER_PWD);
      if (password == null) {
        throw new IllegalArgumentException(
            "YouTrackDB password is not specified : "
                + CONFIG_USER_PWD);
      }

      YouTrackDB youTrackDB;
      boolean shouldCloseYouTrackDB;
      String dbPath;

      dbPath = configuration.getString(CONFIG_DB_PATH);
      if (dbPath == null) {
        throw new IllegalArgumentException(
            "YouTrackDB path is not specified : " + CONFIG_DB_PATH);
      }

      var createIfNotExists = configuration.getBoolean(
          CONFIG_CREATE_IF_NOT_EXISTS,
          false);
      var currentYouTrackDB = ytdbInstance(dbPath, configuration);

      if (createIfNotExists && !currentYouTrackDB.exists(dbName)) {
        var dbTypeStr = configuration.getString(CONFIG_DB_TYPE);
        if (dbTypeStr == null) {
          throw new IllegalArgumentException(
              "YouTrackDB type is not specified : "
                  + CONFIG_DB_TYPE);
        }
        var dbType = DatabaseType.valueOf(dbTypeStr.toUpperCase(Locale.ROOT));

        var userRole = configuration.getString(CONFIG_USER_ROLE);
        if (userRole == null) {
          throw new IllegalArgumentException(
              "YouTrackDB user role is not specified : "
                  + CONFIG_USER_ROLE);
        }

        currentYouTrackDB.createIfNotExists(dbName, dbType, user, password, userRole);
      }

      var ytdbConfig = YouTrackDBConfig.builder().fromApacheConfiguration(configuration).build();
      return currentYouTrackDB.cachedPool(dbName, user, password, ytdbConfig).asGraph();
    } finally {
      configuration.clearProperty(CONFIG_USER_PWD);
    }
  }

  public static YouTrackDBImpl ytdbInstance(String dbPath, Configuration configuration) {
    return ytdbInstance(dbPath, () -> YouTrackDBInternal.embedded(dbPath,
        YouTrackDBConfig.builder().fromApacheConfiguration(configuration).build(), false));
  }

  public static YouTrackDBImpl ytdbInstance(
      String dbPath, Supplier<YouTrackDBInternal> internalSupplier) {
    var path = Path.of(dbPath).toAbsolutePath().normalize();
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
      path = path.toAbsolutePath().normalize();
    }

    //noinspection resource
    storagePathYTDBMap.compute(path, (p, ytdb) -> {
      if (ytdb == youTrackDB) {
        if (onCloseCallback != null) {
          onCloseCallback.run();
        }

        return null;
      } else {
        if (ytdb != null) {
          throw new IllegalStateException(
              "There is another YTDB instance registered for the same path: " + p);
        } else {
          throw new IllegalStateException(
              "There is no YTDB instance registered for the path: " + p);
        }
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
