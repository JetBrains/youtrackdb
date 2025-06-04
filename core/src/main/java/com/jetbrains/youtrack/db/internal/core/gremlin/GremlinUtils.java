package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrack.db.internal.core.storage.memory.DirectMemoryStorage;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;

public class GremlinUtils {

  public static YouTrackDBConfig createYTDBConfig(Configuration config) {
    var builder = YouTrackDBConfig.builder();
    var keys = config.getKeys();
    while (keys.hasNext()) {
      var key = keys.next();
      if (key.startsWith("youtrackdb.")) {
        var globalConfigKey = GlobalConfiguration.findByKey(key);
        if (globalConfigKey != null) {
          builder.addGlobalConfigurationParameter(globalConfigKey,
              config.getProperty(key));
        }
      }
    }

    return builder.build();
  }

  public static Configuration createGraphConfiguration(DatabaseSession session) {
    var config = new BaseConfiguration();
    var embeddedSession = (DatabaseSessionEmbedded) session;
    var storage = embeddedSession.getStorage();
    String dbPath;
    if (storage instanceof DirectMemoryStorage) {
      dbPath = ".";
    } else if (storage instanceof DiskStorage diskStorage) {
      var storagePath = diskStorage.getStoragePath();
      dbPath = storagePath.toAbsolutePath().toString();
    } else {
      throw new IllegalArgumentException("Unsupported storage type: " + storage.getClass().getName()
          + " (only DirectMemoryStorage and DiskStorage are supported at the moment) ");
    }

    config.addProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_PATH, dbPath);
    config.addProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME, storage.getName());

    var user = embeddedSession.getCurrentUser();
    if (user != null) {
      config.addProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER,
          embeddedSession.getCurrentUser().getName(embeddedSession));
    }

    var ytdbConfig = embeddedSession.getConfiguration();
    var ytdbConfigKeys = ytdbConfig.getContextKeys();
    for (var key : ytdbConfigKeys) {
      config.addProperty(key, config.getProperty(key));
    }

    return config;
  }
}
