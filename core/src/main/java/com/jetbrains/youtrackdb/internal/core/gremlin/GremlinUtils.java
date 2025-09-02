package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB.ConfigurationParameters;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.BaseConfiguration;

public class GremlinUtils {
  @Nonnull
  public static BaseConfiguration createBaseConfiguration(DatabaseSession session) {
    var config = new BaseConfiguration();
    config.addProperty(ConfigurationParameters.CONFIG_DB_NAME, session.getDatabaseName());
    config.addProperty(ConfigurationParameters.CONFIG_USER_NAME, session.getCurrentUserName());
    return config;
  }
}
