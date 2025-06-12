package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;

public class YTDBGraphFactory {

  public static final String CONFIG_YOUTRACK_DB_PATH = "youtrackdb.gremlin.path";
  public static final String CONFIG_YOUTRACK_DB_NAME = "youtrackdb.gremlin.name";
  public static final String CONFIG_YOUTRACK_DB_USER = "youtrackdb.gremlin.user";
  public static final String CONFIG_YOUTRACK_DB_USER_PWD = "youtrackdb.gremlin.pwd";
  public static final String CONFIG_YOUTRACK_DB_USER_ROLE = "youtrackdb.gremlin.user.role";
  public static final String CONFIG_YOUTRACK_DB_CREATE_IF_NOT_EXISTS = "youtrackdb.gremlin.createIfNotExists";
  public static final String CONFIG_YOUTRACK_DB_TYPE = "youtrackdb.gremlin.db.type";

  private static final ConcurrentHashMap<String, YouTrackDB> storagePathYTDBMap = new ConcurrentHashMap<>();
  private static volatile Thread shutdownHook = new Thread() {
    @Override
    public void run() {
      storagePathYTDBMap.values().forEach(ytdb -> {
        try {
          //closing YouTrackDB instance
          ytdb.close();
        } catch (Exception e) {
          LogManager.instance().error(this, "Error closing YouTrackDB instance", e);
        }
      });

      storagePathYTDBMap.clear();
    }
  };
  private static final ReentrantLock shutdownHookLock = new ReentrantLock();

  /// This method is used in [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open a
  /// new graph instance.
  @SuppressWarnings("unused")
  public static Graph open(Configuration configuration) {
    try {
      if (shutdownHook != null) {
        shutdownHookLock.lock();
        try {
          if (shutdownHook != null) {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            shutdownHook = null;
          }
        } finally {
          shutdownHookLock.unlock();
        }
      }

      var dbName = configuration.getString(CONFIG_YOUTRACK_DB_NAME);
      if (dbName == null) {
        throw new IllegalArgumentException(
            "YouTrackDB graph name is not specified : " + CONFIG_YOUTRACK_DB_NAME);
      }
      var user = configuration.getString(CONFIG_YOUTRACK_DB_USER);
      if (user == null) {
        throw new IllegalArgumentException(
            "YouTrackDB user is not specified : " + CONFIG_YOUTRACK_DB_USER);
      }
      var password = configuration.getString(CONFIG_YOUTRACK_DB_USER_PWD);
      if (password == null) {
        throw new IllegalArgumentException(
            "YouTrackDB password is not specified : " + CONFIG_YOUTRACK_DB_USER_PWD);
      }

      YouTrackDB youTrackDB;
      boolean shouldCloseYouTrackDB;
      String dbPath;

      dbPath = configuration.getString(CONFIG_YOUTRACK_DB_PATH);
      if (dbPath == null) {
        throw new IllegalArgumentException(
            "YouTrackDB path is not specified : " + CONFIG_YOUTRACK_DB_PATH);
      }

      var ytdb = storagePathYTDBMap.compute(dbPath, (dp, mappedYTDB) -> {
        if (mappedYTDB == null) {
          mappedYTDB = YourTracks.embedded(dbPath, GremlinUtils.createYTDBConfig(configuration));
        }

        var createIfNotExists = configuration.getBoolean(CONFIG_YOUTRACK_DB_CREATE_IF_NOT_EXISTS,
            false);
        if (createIfNotExists && !mappedYTDB.exists(dbName)) {
          var dbTypeStr = configuration.getString(CONFIG_YOUTRACK_DB_TYPE);
          if (dbTypeStr == null) {
            throw new IllegalArgumentException(
                "YouTrackDB type is not specified : " + CONFIG_YOUTRACK_DB_TYPE);
          }
          var dbType = DatabaseType.valueOf(dbTypeStr.toUpperCase(Locale.ROOT));

          var userRole = configuration.getString(CONFIG_YOUTRACK_DB_USER_ROLE);
          if (userRole == null) {
            throw new IllegalArgumentException(
                "YouTrackDB user role is not specified : " + CONFIG_YOUTRACK_DB_USER_ROLE);
          }
          mappedYTDB.createIfNotExists(dbName, dbType, user, password, userRole);
        }

        return mappedYTDB;
      });

      return new YTDBGraphImpl(ytdb.cachedPool(dbName, user, password), configuration);
    } finally {
      configuration.clearProperty(CONFIG_YOUTRACK_DB_USER_PWD);
    }
  }

  public static void registerYTDBInstance(String dbPath, YouTrackDB youTrackDB) {
    storagePathYTDBMap.compute(dbPath, (dp, mappedYouTrackDB) -> {
      if (mappedYouTrackDB != null && mappedYouTrackDB.isOpen() && youTrackDB != mappedYouTrackDB) {
        throw new IllegalStateException(
            "YouTrackDB instance is already created for path " + dbPath);
      }

      return youTrackDB;
    });
  }

  @Nullable
  public static YouTrackDB getYTDBInstance(String dbPath) {
    return storagePathYTDBMap.get(dbPath);
  }
}
