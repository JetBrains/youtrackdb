package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.YouTrackDB.DatabaseConfigurationParameters;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.BaseConfiguration;

public class GremlinUtils {
  @Nonnull
  public static BaseConfiguration createBaseConfiguration(DatabaseSession session) {
    var config = new BaseConfiguration();
    config.addProperty(DatabaseConfigurationParameters.CONFIG_DB_NAME, session.getDatabaseName());
    config.addProperty(DatabaseConfigurationParameters.CONFIG_USER_NAME,
        session.getCurrentUserName());
    return config;
  }
}
