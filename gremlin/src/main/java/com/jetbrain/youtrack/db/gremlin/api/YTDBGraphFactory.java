package com.jetbrain.youtrack.db.gremlin.api;

import com.jetbrain.youtrack.db.gremlin.internal.GremlinUtils;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphImpl;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBSingleThreadGraphFactory;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBSingleThreadGraphFactoryImpl;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
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
  public static final String CONFIG_YOUTRACK_DB_TYPE = "youtrackdb-type";

  private static final ConcurrentHashMap<String, RawPair<YouTrackDB, ConcurrentHashMap<YTDBInstanceConfiguration, YTDBSingleThreadGraphFactory>>>
      dbFactoriesMap = new ConcurrentHashMap<>();

  private static volatile Thread shutdownHook = new Thread() {
    @Override
    public void run() {
      dbFactoriesMap.values().forEach(f -> {
        try {
          //closing all factories for the same YouTrackDB instance
          var factories = f.second().values();
          factories.forEach(factory -> {
            try {
              factory.close();
            } catch (Exception e) {
              LogManager.instance()
                  .error(this, "Error closing YouTrackDB Graph Factory instance", e);
            }
          });

          //closing YouTrackDB instance
          f.first().close();
        } catch (Exception e) {
          LogManager.instance().error(this, "Error closing YouTrackDB instance", e);
        }
      });

      dbFactoriesMap.clear();
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

      var singleThreadGraphFactory = new YTDBSingleThreadGraphFactory[1];
      dbFactoriesMap.compute(dbPath, (dp, ytdbFactoryMap) -> {
        if (ytdbFactoryMap == null) {

          var ytdb = YourTracks.embedded(dbPath, GremlinUtils.createYTDBConfig(configuration));
          var factoryMap = new ConcurrentHashMap<YTDBInstanceConfiguration, YTDBSingleThreadGraphFactory>();
          ytdbFactoryMap = new RawPair<>(ytdb, factoryMap);
        }

        var ytdb = ytdbFactoryMap.first();
        var createIfNotExists = configuration.getBoolean(CONFIG_YOUTRACK_DB_CREATE_IF_NOT_EXISTS,
            false);
        if (createIfNotExists && !ytdb.exists(dbName)) {
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
          ytdb.create(dbName, dbType, user, password, userRole);
        }

        var factoryMap = ytdbFactoryMap.second();
        var instanceConfiguration = new YTDBInstanceConfiguration(dbPath, dbName, user);

        singleThreadGraphFactory[0] = factoryMap.compute(instanceConfiguration, (ic, factory) -> {
          if (factory == null) {
            return new YTDBSingleThreadGraphFactoryImpl(ytdb, configuration);
          } else if (ytdb != factory.getYouTrackDB()) {
            throw new IllegalStateException(
                "YouTrackDB instance is already created for path " + dbPath);
          }

          return factory;
        });

        return ytdbFactoryMap;
      });

      return new YTDBGraphImpl(singleThreadGraphFactory[0], configuration);
    } finally {
      configuration.clearProperty(CONFIG_YOUTRACK_DB_USER_PWD);
    }
  }

  public static void closeYTDBInstance(Configuration configuration) {
    var dbPath = configuration.getString(CONFIG_YOUTRACK_DB_PATH);
    if (dbPath == null) {
      throw new IllegalArgumentException(
          "YouTrackDB path is not specified : " + CONFIG_YOUTRACK_DB_PATH);
    }

    var ytDBFactoryMap = dbFactoriesMap.remove(dbPath);
    if (ytDBFactoryMap != null) {
      try {
        ytDBFactoryMap.second().values().forEach(factory -> {
          try {
            factory.close();
          } catch (Exception e) {
            LogManager.instance()
                .error(YTDBGraphFactory.class, "Error closing YouTrackDB Graph Factory instance",
                    e);
          }
        });
        ytDBFactoryMap.first().close();
      } catch (Exception e) {
        LogManager.instance().error(YTDBGraphFactory.class, "Error closing YouTrackDB instance", e);
      }
    }
  }

  public static void registerYTDBInstance(String dbPath, YouTrackDB youTrackDB) {
    dbFactoriesMap.compute(dbPath, (dp, ytdbFactoryMap) -> {
      if (ytdbFactoryMap == null) {
        var factoryMap = new ConcurrentHashMap<YTDBInstanceConfiguration, YTDBSingleThreadGraphFactory>();
        return new RawPair<>(youTrackDB, factoryMap);
      }
      if (youTrackDB != ytdbFactoryMap.first()) {
        throw new IllegalStateException(
            "YouTrackDB instance is already created for path " + dbPath);
      }

      return ytdbFactoryMap;
    });
  }

  public static void unregisterYTDBInstance(String dbPath) {
    var factoryMap = dbFactoriesMap.remove(dbPath);
    factoryMap.second().values().forEach(factory -> {
      try {
        factory.close();
      } catch (Exception e) {
        LogManager.instance()
            .error(YTDBGraphFactory.class, "Error closing YouTrackDB Graph Factory instance",
                e);
      }
    });
  }

  public static boolean isYTDBInstanceRegistered(String dbPath) {
    return dbFactoriesMap.containsKey(dbPath);
  }

  public static boolean registerYTDBInstanceIfNotExist(String dbPath, YouTrackDB youTrackDB) {
    var result = dbFactoriesMap.computeIfAbsent(dbPath,
        dp -> new RawPair<>(youTrackDB, new ConcurrentHashMap<>()));
    return result.first() == youTrackDB;
  }

  @Nullable
  public static YouTrackDB getYTDBInstance(String dbPath) {
    var ytdbFactoryMap = dbFactoriesMap.get(dbPath);
    if (ytdbFactoryMap == null) {
      return null;
    }

    return dbFactoriesMap.get(dbPath).first();
  }

  private record YTDBInstanceConfiguration(String dbPath, String dbName, String user) {

  }
}
