package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
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
}
