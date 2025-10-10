package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.ConfigurationParameters;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import java.util.HashMap;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Graph;

public class YTDBGraphInitUtil {

  public static Map<String, Object> getBaseConfiguration(String graphName, String directoryPath) {
    var configs = new HashMap<String, Object>();
    configs.put(Graph.GRAPH, YTDBGraph.class.getName());

    var dbType = calculateDbType();

    configs.put(ConfigurationParameters.CONFIG_DB_NAME, graphName);
    configs.put(ConfigurationParameters.CONFIG_USER_NAME, "adminuser");
    configs.put(ConfigurationParameters.CONFIG_USER_PWD, "adminpwd");
    configs.put(ConfigurationParameters.CONFIG_DB_PATH, directoryPath);
    configs.put(ConfigurationParameters.CONFIG_CREATE_IF_NOT_EXISTS, true);
    configs.put(ConfigurationParameters.CONFIG_DB_TYPE, dbType.name());
    configs.put(ConfigurationParameters.CONFIG_USER_ROLE, "admin");

    return configs;
  }

  private static DatabaseType calculateDbType() {
    final var testConfig =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());

    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      return DatabaseType.DISK;
    }

    return DatabaseType.MEMORY;
  }
}
