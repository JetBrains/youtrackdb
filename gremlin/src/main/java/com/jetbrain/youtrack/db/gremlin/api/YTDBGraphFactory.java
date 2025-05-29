package com.jetbrain.youtrack.db.gremlin.api;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.structure.Graph;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphImpl;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBSingleThreadGraphFactory;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBSingleThreadGraphFactoryImpl;

public class YTDBGraphFactory {
  public static final String CONFIG_YOUTRACK_DB_PATH = "youtrackdb-path";
  public static final String CONFIG_YOUTRACK_DB_NAME = "youtrackdb-name";
  public static final String CONFIG_YOUTRACK_DB_TYPE = "youtrackdb-type";
  public static final String CONFIG_YOUTRACK_DB_USER = "youtrackdb-user";
  public static final String CONFIG_YOUTRACK_DB_PASS = "youtrackdb-pass";

  public static final String CONFIG_YOUTRACK_DB_INSTANCE = "youtrackdb-instance";
  public static final String CONFIG_YOUTRACK_DB_CLOSE_ON_SHUTDOWN = "youtrackdb-close-on-shutdown";

  private static final ConcurrentHashMap<String, YouTrackDB> dbPathToYTDBs =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<YTDBInstanceConfiguration, YTDBSingleThreadGraphFactory> dbPathToFactories =
      new ConcurrentHashMap<>();

  private static volatile Thread shutdownHook = new Thread() {
    @Override
    public void run() {
      dbPathToFactories.values().forEach(f -> {
        try {
          f.close();
        } catch (Exception e) {
          LogManager.instance().error(this, "Error closing YouTrackDB instance", e);
        }
      });
      dbPathToFactories.clear();
      dbPathToYTDBs.clear();
    }
  };
  private static final ReentrantLock shutdownHookLock = new ReentrantLock();

  /// This method is used in [org.apache.tinkerpop.gremlin.structure.util.GraphFactory] to open a
  /// new graph instance.
  @SuppressWarnings("unused")
  public static Graph open(Configuration configuration) {
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
    var password = configuration.getString(CONFIG_YOUTRACK_DB_PASS);
    if (password == null) {
      throw new IllegalArgumentException(
          "YouTrackDB password is not specified : " + CONFIG_YOUTRACK_DB_PASS);
    }

    YouTrackDB youTrackDB;
    boolean shouldCloseYouTrackDB;
    String dbPath;
    var providedYouTrackDB = (YouTrackDB) configuration.getProperty(CONFIG_YOUTRACK_DB_INSTANCE);

    if (providedYouTrackDB != null) {
      var internal = YouTrackDBInternal.extract(
          (YouTrackDBAbstract<?, ? extends BasicDatabaseSession<?, ?>>) providedYouTrackDB);
      dbPath = internal.getBasePath();

      dbPathToYTDBs.compute(dbPath, (dp, yt) -> {
        if (yt == null) {
          return providedYouTrackDB;
        } else if (providedYouTrackDB != yt) {
          throw new IllegalStateException(
              "YouTrackDB instance is already created for path " + dbPath);
        }

        return providedYouTrackDB;
      });

      shouldCloseYouTrackDB = configuration.getBoolean(CONFIG_YOUTRACK_DB_CLOSE_ON_SHUTDOWN,
          false);

      youTrackDB = providedYouTrackDB;
    } else {
      dbPath = configuration.getString(CONFIG_YOUTRACK_DB_PATH);
      if (dbPath == null) {
        throw new IllegalArgumentException(
            "YouTrackDB path is not specified : " + CONFIG_YOUTRACK_DB_PATH);
      }

      youTrackDB = dbPathToYTDBs.compute(dbPath, (dp, yt) -> {
        if (yt == null) {
          return YourTracks.embedded(dbPath);
        }

        return yt;
      });
      shouldCloseYouTrackDB = configuration.getBoolean(CONFIG_YOUTRACK_DB_CLOSE_ON_SHUTDOWN,
          true);
    }

    var instanceConfiguration = new YTDBInstanceConfiguration(dbPath, dbName, user, password);
    var singleThreadFactory = dbPathToFactories.compute(instanceConfiguration, (ic, factory) -> {
      if (factory == null) {
        return new YTDBSingleThreadGraphFactoryImpl(providedYouTrackDB, configuration,
            shouldCloseYouTrackDB);
      } else if (youTrackDB != factory.getYouTrackDB()) {
        throw new IllegalStateException(
            "YouTrackDB instance is already created for path " + dbPath);
      }

      return factory;
    });

    return new YTDBGraphImpl(singleThreadFactory, configuration);
  }

  private record YTDBInstanceConfiguration(String dbPath, String dbName, String user,
                                           String password) {
  }
}
