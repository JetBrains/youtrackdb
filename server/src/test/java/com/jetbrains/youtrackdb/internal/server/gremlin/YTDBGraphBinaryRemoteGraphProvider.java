package com.jetbrains.youtrackdb.internal.server.gremlin;

import static org.apache.tinkerpop.gremlin.process.remote.RemoteConnection.GREMLIN_REMOTE;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.SessionPool;
import com.jetbrains.youtrackdb.internal.core.gremlin.YouTrackDBFeatures.YTDBFeatures;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrackdb.internal.driver.YTDBDriverRemoteConnection;
import com.jetbrains.youtrackdb.internal.driver.YTDBDriverWebSocketChannelizer;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.AbstractGraphProvider;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData;
import org.apache.tinkerpop.gremlin.driver.AuthProperties;
import org.apache.tinkerpop.gremlin.driver.AuthProperties.Property;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.TestClientFactory;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Graph.Features;
import org.apache.tinkerpop.gremlin.structure.RemoteGraph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerIoRegistryV3;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.ser.AbstractMessageSerializer;
import org.apache.tinkerpop.gremlin.util.ser.GraphBinaryMessageSerializerV1;

@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.ProfileTest",
    method = "*",
    reason = "Tests for profile() are not supported for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupTest",
    method = "g_V_both_name_order_byXa_bX_dedup_value",
    reason = "Tests that include lambdas are not supported by the test suite for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderTest",
    method = "g_V_name_order_byXa1_b1X_byXb2_a2X",
    reason = "Tests that include lambdas are not supported by the test suite for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderTest",
    method = "g_V_order_byXname_a1_b1X_byXname_b2_a2X_name",
    reason = "Tests that include lambdas are not supported by the test suite for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SackTest",
    method = "g_withSackXmap__map_cloneX_V_out_out_sackXmap_a_nameX_sack",
    reason = "Tests that include lambdas are not supported by the test suite for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SubgraphTest",
    method = "g_V_withSideEffectXsgX_outEXknowsX_subgraphXsgX_name_capXsgX",
    reason = "Tests that include lambdas are not supported by the test suite for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SubgraphTest",
    method = "g_V_withSideEffectXsgX_repeatXbothEXcreatedX_subgraphXsgX_outVX_timesX5X_name_dedup",
    reason = "Tests that include lambdas are not supported by the test suite for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SubgraphTest",
    method = "g_withSideEffectXsgX_V_hasXname_danielXout_capXsgX",
    reason = "Tests that include lambdas are not supported by the test suite for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.OrderabilityTest",
    method = "g_inject_order_with_unknown_type",
    reason = "Tests that inject a generic Java Object are not supported by the test suite for remotes")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.OrderabilityTest",
    method = "g_E_properties_order_value",
    reason = "Query and modification are performed in different pending transactions.")
public class YTDBGraphBinaryRemoteGraphProvider extends AbstractGraphProvider implements
    AutoCloseable {

  @SuppressWarnings("rawtypes")
  private static final Set<Class> IMPLEMENTATION = new HashSet<>() {{
    add(RemoteGraph.class);
  }};

  public static final String ADMIN_USER_NAME = "adminuser";
  public static final String ADMIN_USER_PASSWORD = "adminpwd";
  public static final String DEFAULT_DB_NAME = "graph";

  protected static GremlinServer server;
  protected final Map<String, RemoteGraph> remoteCache = new HashMap<>();

  protected Cluster cluster;

  public YouTrackDBServer ytdbServer;
  public final HashMap<String, SessionPool> graphGetterSessionPools = new HashMap<>();

  public YTDBGraphBinaryRemoteGraphProvider() {
  }

  private static MessageSerializer<?> createSerializer() {
    var graphBinarySerializer = new GraphBinaryMessageSerializerV1();
    var config = new HashMap<String, Object>();
    var ytdbIoRegistry = YTDBIoRegistry.class.getName();
    var tinkerGraphIoRegistry = TinkerIoRegistryV3.class.getName();

    var registries = new ArrayList<String>();
    registries.add(ytdbIoRegistry);
    registries.add(tinkerGraphIoRegistry);

    config.put(AbstractMessageSerializer.TOKEN_IO_REGISTRIES, registries);
    graphBinarySerializer.configure(config, null);

    return graphBinarySerializer;
  }

  private static Cluster.Builder createClusterBuilder(MessageSerializer<?> serializer) {
    return TestClientFactory.build(server.getPort()).maxContentLength(1000000)
        .serializer(serializer).channelizer(
            YTDBDriverWebSocketChannelizer.class);
  }

  @Override
  public Optional<TestListener> getTestListener() {
    return Optional.of(new TestListener() {
      @Override
      public void onTestStart(Class<?> test, String testName) {
        try {
          if (server == null) {
            startServer();
          }
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }
    });
  }

  @Override
  public void close() {
    try {
      cluster.close();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }

    try {
      stopServer();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public Graph openTestGraph(final Configuration config) {
    final var serverGraphName = config.getString(
        DriverRemoteConnection.GREMLIN_REMOTE_DRIVER_SOURCENAME);
    return remoteCache.compute(serverGraphName,
        (k, graph) -> {

          if (graph != null) {
            var graphConfig = graph.configuration();
            if (areConfigsTheSame(config, graphConfig)) {
              return graph;
            }
          }

          return RemoteGraph.open(new YTDBDriverRemoteConnection(cluster, config), config);
        });
  }

  private static boolean areConfigsTheSame(final Configuration config1,
      final Configuration config2) {
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
      put("clusterConfiguration.port", ytdbServer.getGremlinServer().getPort());
      put("clusterConfiguration.hosts", "localhost");
    }};
  }

  @Override
  public void loadGraphData(final Graph graph, final LoadGraphWith loadGraphWith,
      final Class testClass, final String testName) {
    // server already loads with the all the graph instances for LoadGraphWith
  }

  @SuppressWarnings("rawtypes")
  @Override
  public Set<Class> getImplementations() {
    return IMPLEMENTATION;
  }

  @Override
  public YTDBGraphTraversalSource traversal(final Graph graph) {
    assert graph instanceof RemoteGraph;

    return AnonymousTraversalSource
        .traversal(YTDBGraphTraversalSource.class)
        .with(((RemoteGraph) graph).getConnection());
  }

  @Override
  public Optional<Features> getStaticFeatures() {
    return Optional.of(YTDBFeatures.INSTANCE);
  }

  public void startServer() throws Exception {
    ytdbServer = new YouTrackDBServer();
    try {
      ytdbServer.setServerRootDirectory(DbTestBase.getBaseDirectoryPathStr(getClass()));
      ytdbServer.startup(
          "classpath:com/jetbrains/youtrackdb/internal/server/youtrackdb-server-integration.yaml");
      ytdbServer.activate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    server = ytdbServer.getGremlinServer();

    cluster = createClusterBuilder(createSerializer()).authProperties(new AuthProperties().with(
        Property.USERNAME, "root").with(Property.PASSWORD, "root")).create();
    reloadAllTestGraphs();
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

  public void stopServer() {
    graphGetterSessionPools.forEach((k, v) -> v.close());
    graphGetterSessionPools.clear();

    ytdbServer.shutdown();
    ytdbServer = null;
    server = null;
  }

  private static DatabaseType calculateDbType() {
    final var testConfig =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());

    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      return DatabaseType.DISK;
    }

    return DatabaseType.MEMORY;
  }

  protected static String getServerGraphName(final LoadGraphWith.GraphData loadGraphWith) {
    final String serverGraphName;

    if (null == loadGraphWith) {
      return "graph";
    }

    serverGraphName = switch (loadGraphWith) {
      case CLASSIC -> "classic";
      case MODERN -> "modern";
      case GRATEFUL -> "grateful";
      case CREW -> "crew";
      case SINK -> "sink";
    };

    return serverGraphName;
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
