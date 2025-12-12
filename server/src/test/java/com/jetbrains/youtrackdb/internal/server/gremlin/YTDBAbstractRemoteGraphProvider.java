package com.jetbrains.youtrackdb.internal.server.gremlin;

import static org.apache.tinkerpop.gremlin.process.remote.RemoteConnection.GREMLIN_REMOTE;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.db.SessionPool;
import com.jetbrains.youtrackdb.internal.core.gremlin.YouTrackDBFeatures.YTDBFeatures;
import com.jetbrains.youtrackdb.internal.driver.YTDBDriverRemoteConnection;
import com.jetbrains.youtrackdb.internal.driver.YTDBDriverWebSocketChannelizer;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.AbstractRemoteGraphProvider;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource;
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
  private static final AtomicLong idGenerator = new AtomicLong(
      ThreadLocalRandom.current().nextLong(Long.MAX_VALUE >> 1));

  public static final String ADMIN_USER_NAME = "adminuser";
  public static final String ADMIN_USER_PASSWORD = "adminpwd";
  public static final String DEFAULT_DB_NAME = "graph";

  private YouTrackDBServer ytdbServer;
  public HashMap<String, SessionPool> graphGetterSessionPools = new HashMap<>();

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
          buildDirectory.resolve("gremlinServerRoot")
              .resolve("serverHome" + idGenerator.incrementAndGet()).toAbsolutePath().toString());
      ytdbServer.startup(
          "classpath:com/jetbrains/youtrackdb/internal/server/youtrackdb-server-integration.yaml");
      ytdbServer.activate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    reloadAllTestGraphs();

    server = ytdbServer.getGremlinServer();
  }

  private void reloadAllTestGraphs() throws Exception {
    var serverContext = ytdbServer.getYouTrackDB();
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

      SessionPool cachedPool;
      if (serverContext.exists(graphName)) {
        if (graphToLoad == GraphData.GRATEFUL) {
          //this graph is read-only so no need to re-load it if it already exists

          graphGetterSessionPools.put(graphName,
              serverContext.cachedPool(graphName, ADMIN_USER_NAME, ADMIN_USER_PASSWORD));
          continue;
        }

        cachedPool = graphGetterSessionPools.get(graphName);
        if (cachedPool == null) {
          cachedPool = serverContext.cachedPool(graphName, ADMIN_USER_NAME, ADMIN_USER_PASSWORD);
          graphGetterSessionPools.put(graphName, cachedPool);
        }
      } else {
        serverContext.create(graphName, dbType, ADMIN_USER_NAME, ADMIN_USER_PASSWORD, "admin");
        cachedPool = serverContext.cachedPool(graphName, ADMIN_USER_NAME, ADMIN_USER_PASSWORD);

        graphGetterSessionPools.put(graphName, cachedPool);
      }

      var graph = cachedPool.asGraph();
      graph.traversal().autoExecuteInTx(g -> g.V().drop());
      readIntoGraph(graph, location);
    }

    if (serverContext.exists(DEFAULT_DB_NAME)) {
      var cachedPool = graphGetterSessionPools.get(DEFAULT_DB_NAME);
      if (cachedPool != null) {
        cachedPool.asGraph().traversal().autoExecuteInTx(g ->
            g.V().drop()
        );
      } else {
        graphGetterSessionPools.put(DEFAULT_DB_NAME,
            serverContext.cachedPool(DEFAULT_DB_NAME, ADMIN_USER_NAME, ADMIN_USER_PASSWORD));
      }
      return;
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
    graphGetterSessionPools.forEach((k, v) -> v.close());
    graphGetterSessionPools.clear();

    ytdbServer.shutdown();
    ytdbServer = null;
    server = null;
  }


  @Override
  public Map<String, Object> getBaseConfiguration(final String graphName, Class<?> test,
      final String testMethodName,
      final LoadGraphWith.GraphData loadGraphWith) {
    final var serverGraphName = getServerGraphName(loadGraphWith);

    return new HashMap<>() {{
      put(Graph.GRAPH, RemoteGraph.class.getName());
      put(RemoteConnection.GREMLIN_REMOTE_CONNECTION_CLASS,
          YTDBDriverRemoteConnection.class.getName());
      put(DriverRemoteConnection.GREMLIN_REMOTE_DRIVER_SOURCENAME,
          serverGraphName);
      put("clusterConfiguration.port", TestClientFactory.PORT);
      put("clusterConfiguration.hosts", "localhost");
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

  // overriding to create an instance of YTDBGraphTraversalSource
  @Override
  public YTDBGraphTraversalSource traversal(final Graph graph) {
    assert graph instanceof RemoteGraph;

    return AnonymousTraversalSource
        .traversal(YTDBGraphTraversalSource.class)
        .withRemote(((RemoteGraph) graph).getConnection());
  }

  public void closeConnections() {
    remoteCache.forEach((k, v) -> {
      try {
        v.getConnection().close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    remoteCache.clear();
  }
}
