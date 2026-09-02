package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Graph;

public class YTDBGraphInitUtil {

  public static Map<String, Object> getBaseConfiguration(String graphName, String directoryPath) {
    var configs = new HashMap<String, Object>();
    configs.put(Graph.GRAPH, YTDBGraph.class.getName());

    var dbType = calculateDbType();

    configs.put(YTDBGraphFactory.CONFIG_DB_NAME, graphName);
    configs.put(YTDBGraphFactory.CONFIG_USER_NAME, "adminuser");
    configs.put(YTDBGraphFactory.CONFIG_USER_PWD, "adminpwd");
    configs.put(YTDBGraphFactory.CONFIG_DB_PATH, directoryPath);
    configs.put(YTDBGraphFactory.CONFIG_CREATE_IF_NOT_EXISTS, true);
    configs.put(YTDBGraphFactory.CONFIG_DB_TYPE, dbType.name());
    configs.put(YTDBGraphFactory.CONFIG_USER_ROLE, "admin");

    // Portable order semantics for the upstream TinkerPop suites. YouTrackDB ships a deviation:
    // a global-scope order() keeps a record that lacks the ordered property and sorts it as a
    // null key. Upstream scenarios assert the portable drop, so the suites run with the deviation
    // switched off and keep measuring portable behaviour.
    //
    // This switches OFF the YouTrackDB default only. Upstream ProductiveByStrategy is untouched:
    // a scenario that adds that strategy still gets productive modulators, because the strategy
    // rewrites the by-modulator itself and reads none of this setting.
    configs.put(
        GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY.getKey(), Boolean.FALSE);

    return configs;
  }

  private static DatabaseType calculateDbType() {
    final var testConfig =
        System.getProperty("youtrackdb.test.env",
            DatabaseType.MEMORY.name().toLowerCase(Locale.ROOT));

    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      return DatabaseType.DISK;
    }

    return DatabaseType.MEMORY;
  }
}
