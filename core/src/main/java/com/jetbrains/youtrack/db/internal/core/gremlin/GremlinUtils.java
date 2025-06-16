package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import javax.annotation.Nonnull;
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

  public static YTDBSingleThreadGraph wrapSession(DatabaseSession session,
      YTDBGraphImpl parentGraph) {
    if (!(session instanceof DatabaseSessionEmbedded embeddedSession)) {
      throw new IllegalArgumentException(
          "Passed in database session is not embedded. Only sessions of embedded databases are supported.");
    }

    var config = createBaseConfiguration(session);
    if (parentGraph != null) {
      return new YTDBSingleThreadGraph(embeddedSession, config, YTDBElementImplFactory.INSTANCE,
          parentGraph);
    }

    return new YTDBSingleThreadGraph(embeddedSession, config, YTDBElementWrapperFactory.INSTANCE,
        null);
  }

  @Nonnull
  public static BaseConfiguration createBaseConfiguration(DatabaseSession session) {
    var config = new BaseConfiguration();
    config.addProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_NAME, session.getDatabaseName());
    config.addProperty(YTDBGraphFactory.CONFIG_YOUTRACK_DB_USER, session.getCurrentUserName());
    return config;
  }
}
