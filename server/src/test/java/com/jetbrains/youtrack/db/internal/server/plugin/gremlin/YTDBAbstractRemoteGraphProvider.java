package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import static org.apache.tinkerpop.gremlin.process.remote.RemoteConnection.GREMLIN_REMOTE;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
import org.apache.tinkerpop.gremlin.structure.RemoteGraph;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;

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
          "/com/jetbrains/youtrack/db/internal/server/gremlin/youtrackdb-server-config.xml"));
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

  private void reloadAllTestGraphs() throws IOException {
    if (graphGetterSessionPools != null) {
      graphGetterSessionPools.values().forEach(SessionPool::close);
    }
    graphGetterSessionPools = new HashMap<>();

    var serverContext = ytdbServer.getContext();
    var graphsToLoad = LoadGraphWith.GraphData.values();
    var dbType = calculateDbType();

    for (var graphToLoad : graphsToLoad) {
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
      try (var session = serverContext.open(graphName, ADMIN_USER_NAME, ADMIN_USER_PASSWORD)) {
        var graph = session.asGraph();
        readIntoGraph(graph, location);
      }

      graphGetterSessionPools.put(graphName,
          serverContext.cachedPool(graphName, ADMIN_USER_NAME, ADMIN_USER_PASSWORD));
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

    final Supplier<Graph> graphGetter = () -> graphGetterSessionPools.get(serverGraphName).asGraph();
    return new HashMap<>() {{
      put(Graph.GRAPH, RemoteGraph.class.getName());
      put(RemoteConnection.GREMLIN_REMOTE_CONNECTION_CLASS, DriverRemoteConnection.class.getName());
      put(DriverRemoteConnection.GREMLIN_REMOTE_DRIVER_SOURCENAME, "g" + serverGraphName);
      put("clusterConfiguration.port", TestClientFactory.PORT);
      put("clusterConfiguration.hosts", "localhost");
      put(GREMLIN_REMOTE + "attachment", graphGetter);
    }};
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
    return TestClientFactory.build().maxContentLength(1000000).serializer(serializer);
  }

}
