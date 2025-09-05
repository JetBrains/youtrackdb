package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import static org.apache.tinkerpop.gremlin.process.remote.RemoteConnection.GREMLIN_REMOTE;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.common.SessionPool;
import com.jetbrains.youtrackdb.internal.core.gremlin.YouTrackDBFeatures.YTDBFeatures;
import com.jetbrains.youtrackdb.internal.driver.YTDBDriverWebSocketChannelizer;
import com.jetbrains.youtrackdb.internal.gremlin.tests.YTDBTemporaryRidConversionTest;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.AbstractRemoteGraphProvider;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.server.TestClientFactory;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Graph.Features;
import org.apache.tinkerpop.gremlin.structure.RemoteGraph;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;

@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.OrderabilityTest",
    method = "g_E_properties_order_value",
    reason = "Query and modification are performed in different pending transactions.")
public abstract class YTDBAbstractRemoteGraphProvider extends AbstractRemoteGraphProvider {

  public static final String ADMIN_USER_NAME = "adminuser";
  public static final String ADMIN_USER_PASSWORD = "adminpwd";
  public static final String DEFAULT_DB_NAME = "graph";

  private YouTrackDBServer ytdbServer;
  public HashMap<String, SessionPool<DatabaseSession>> graphGetterSessionPools = new HashMap<>();

  public YTDBAbstractRemoteGraphProvider(Cluster cluster) {
    super(cluster);
  }

  @Override
  public void startServer() throws Exception {
    final var buildDirectory = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath();
    ytdbServer = new YouTrackDBServer();
    try {
      ytdbServer.setServerRootDirectory(
          buildDirectory.resolve("gremlinServerRoot").toAbsolutePath().toString());
      ytdbServer.startup(getClass().getResourceAsStream(
          "/com/jetbrains/youtrackdb/internal/server/gremlin/youtrackdb-server-config.xml"));
      ytdbServer.activate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    reloadAllTestGraphs();

    var pluginManager = ytdbServer.getPluginManager();
    var pluginInfo = pluginManager.getPluginByName(GremlinServerPlugin.NAME);
    var plugin = (GremlinServerPlugin) pluginInfo.getInstance();

    server = plugin.getGremlinServer();
  }

  private void reloadAllTestGraphs() throws Exception {
    if (graphGetterSessionPools != null) {
      graphGetterSessionPools.values().forEach(SessionPool::close);
    }
    graphGetterSessionPools = new HashMap<>();

    var serverContext = ytdbServer.getContext();
    var graphsToLoad = LoadGraphWith.GraphData.values();
    var dbType = calculateDbType();

    graphLoadingLoop:
    for (var graphToLoad : graphsToLoad) {
      var featuresRequired = graphToLoad.featuresRequired();
      for (var feature : featuresRequired) {
        if (!YTDBFeatures.INSTANCE.supports(feature.featureClass(), feature.feature())) {
          //features are not supported for the given graph
          continue graphLoadingLoop;
        }
      }

      var location = graphToLoad.location();
      var graphName = getServerGraphName(graphToLoad);

      if (serverContext.exists(graphName)) {
        if (graphToLoad == GraphData.GRATEFUL) {
          //this graph is read-only so no need to re-load it if it already exists

          graphGetterSessionPools.put(graphName,
              serverContext.cachedPool(graphName, ADMIN_USER_NAME, ADMIN_USER_PASSWORD));
          continue;
        }

        serverContext.drop(graphName);
      }

      serverContext.create(graphName, dbType, ADMIN_USER_NAME, ADMIN_USER_PASSWORD, "admin");

      var cachedPool = serverContext.cachedPool(graphName, ADMIN_USER_NAME, ADMIN_USER_PASSWORD);
      readIntoGraph(cachedPool.asGraph(), location);

      graphGetterSessionPools.put(graphName, cachedPool);
    }

    if (serverContext.exists(DEFAULT_DB_NAME)) {
      serverContext.drop(DEFAULT_DB_NAME);
    }

    serverContext.create(DEFAULT_DB_NAME, dbType, ADMIN_USER_NAME, ADMIN_USER_PASSWORD, "admin");
    graphGetterSessionPools.put(DEFAULT_DB_NAME,
        serverContext.cachedPool(DEFAULT_DB_NAME, ADMIN_USER_NAME,
            ADMIN_USER_PASSWORD));
  }

  @Override
  public void clear(Graph graph, Configuration configuration) throws Exception {
    reloadAllTestGraphs();
  }

  @Override
  public void stopServer() {
    ytdbServer.shutdown();
    ytdbServer = null;
    server = null;
  }


  @Override
  public Map<String, Object> getBaseConfiguration(final String graphName, Class<?> test,
      final String testMethodName,
      final LoadGraphWith.GraphData loadGraphWith) {
    final var serverGraphName = getServerGraphName(loadGraphWith);

    final Supplier<Graph> graphGetter = () -> graphGetterSessionPools.get(serverGraphName)
        .asGraph();
    return new HashMap<>() {{
      put(Graph.GRAPH, RemoteGraph.class.getName());
      put(RemoteConnection.GREMLIN_REMOTE_CONNECTION_CLASS, DriverRemoteConnection.class.getName());
      put(DriverRemoteConnection.GREMLIN_REMOTE_DRIVER_SOURCENAME,
          YTDBGraphManager.TRAVERSAL_SOURCE_PREFIX + serverGraphName);
      put("clusterConfiguration.port", TestClientFactory.PORT);
      put("clusterConfiguration.hosts", "localhost");

      if (!YTDBTemporaryRidConversionTest.class.isAssignableFrom(test)) {
        put(GREMLIN_REMOTE + "attachment", graphGetter);
      }
    }};
  }

  @Override
  public boolean areConfigsTheSame(Configuration config1, Configuration config2) {
    if (config1.size() != config2.size()) {
      return false;
    }

    var config1Keys = config1.getKeys();
    while (config1Keys.hasNext()) {
      var config1Key = config1Keys.next();

      var config1Value = config1.getProperty(config1Key);
      var config2Value = config2.getProperty(config1Key);
      if (config1Key.equals(GREMLIN_REMOTE + "attachment")) {
        if (config1Value == null && config2Value == null) {
          return true;
        }
        if (config1Value == null) {
          return false;
        }

        return config2Value != null;
      }

      if (!config1Value.equals(config2Value)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public Optional<Features> getStaticFeatures() {
    return Optional.of(YTDBFeatures.INSTANCE);
  }

  private static DatabaseType calculateDbType() {
    final var testConfig =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());

    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      return DatabaseType.DISK;
    }

    return DatabaseType.MEMORY;
  }

  public static Cluster.Builder createClusterBuilder(MessageSerializer<?> serializer) {
    // match the content length in the server yaml
    return TestClientFactory.build().maxContentLength(1000000).serializer(serializer).channelizer(
        YTDBDriverWebSocketChannelizer.class);
  }
}
