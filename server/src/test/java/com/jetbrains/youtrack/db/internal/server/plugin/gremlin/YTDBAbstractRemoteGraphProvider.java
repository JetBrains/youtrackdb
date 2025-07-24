package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import static org.apache.tinkerpop.gremlin.process.remote.RemoteConnection.GREMLIN_REMOTE;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.internal.core.gremlin.YouTrackDBFeatures.YTDBFeatures;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
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
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_addEXknowsX_fromXaX_toXbX_propertyXweight_0_1X",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_addEXV_outE_label_groupCount_orderXlocalX_byXvalues_descX_selectXkeysX_unfold_limitX1XX_fromXV_hasXname_vadasXX_toXV_hasXname_lopXX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_addV_asXfirstX_repeatXaddEXnextX_toXaddVX_inVX_timesX5X_addEXnextX_toXselectXfirstXX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_VX1X_asXaX_outXcreatedX_addEXcreatedByX_toXaX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_V_asXaX_inXcreatedX_addEXcreatedByX_fromXaX_propertyXyear_2009X_propertyXacl_publicX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_withSideEffectXb_bX_VXaX_addEXknowsX_toXbX_propertyXweight_0_5X",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_V_aggregateXxX_asXaX_selectXxX_unfold_addEXexistsWithX_toXaX_propertyXtime_nowX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_V_hasXname_markoX_asXaX_outEXcreatedX_asXbX_inV_addEXselectXbX_labelX_toXaX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_VX1X_asXaX_outXcreatedX_addEXcreatedByX_toXaX_propertyXweight_2X",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_V_asXaX_outXcreatedX_inXcreatedX_whereXneqXaXX_asXbX_addEXcodeveloperX_fromXaX_toXbX_propertyXyear_2009X",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeTest",
    method = "g_VXaX_addEXknowsX_toXbX_propertyXweight_0_1X",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexTest",
    method = "g_addVXpersonX_propertyXname_stephenX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexTest",
    method = "g_VX1X_addVXanimalX_propertyXage_selectXaX_byXageXX_propertyXname_puppyX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexTest",
    method = "g_addV_propertyXlabel_personX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexTest",
    method = "g_V_addVXanimalX_propertyXage_0X",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexTest",
    method = "g_addVXpersonX_propertyXsingle_name_stephenX_propertyXsingle_name_stephenmX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphTest",
    method = "g_V_hasLabelXpersonX_asXpX_VXsoftwareX_addInEXuses_pX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.MergeEdgeTest",
    method = "g_V_mergeEXlabel_self_weight_05X",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.MergeVertexTest",
    method = "g_mergeVXlabel_person_name_stephenX_optionXonCreate_label_person_name_stephen_age_19X_option",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.MergeVertexTest",
    method = "g_injectX0X_mergeVXlabel_person_name_stephenX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.MergeVertexTest",
    method = "g_withSideEffectXc_label_person_name_stephenX_withSideEffectXm_label_person_name_stephen_age_19X_mergeVXselectXcXX_optionXonCreate_selectXmXX_option",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
@Graph.OptOut(
    test = "org.apache.tinkerpop.gremlin.process.traversal.step.map.MergeVertexTest",
    method = "g_mergeVXlabel_person_name_stephenX",
    reason = "YTDB returns negative rids for new records that can not be re-attached.")
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

  private void reloadAllTestGraphs() throws Exception {
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

      var featuresRequired = graphToLoad.featuresRequired();
      for (var feature : featuresRequired) {
        if (!YTDBFeatures.INSTANCE.supports(feature.featureClass(), feature.feature())) {
          //features are not supported for the given graph
          continue;
        }
      }

      serverContext.create(graphName, dbType, ADMIN_USER_NAME, ADMIN_USER_PASSWORD, "admin");

      try (var pool = serverContext.cachedPool(graphName, ADMIN_USER_NAME, ADMIN_USER_PASSWORD)) {
        var graph = pool.asGraph();
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

    final Supplier<Graph> graphGetter = () -> graphGetterSessionPools.get(serverGraphName)
        .asGraph();
    return new HashMap<>() {{
      put(Graph.GRAPH, RemoteGraph.class.getName());
      put(RemoteConnection.GREMLIN_REMOTE_CONNECTION_CLASS, DriverRemoteConnection.class.getName());
      put(DriverRemoteConnection.GREMLIN_REMOTE_DRIVER_SOURCENAME, "g" + serverGraphName);
      put("clusterConfiguration.port", TestClientFactory.PORT);
      put("clusterConfiguration.hosts", "localhost");
      put(GREMLIN_REMOTE + "attachment", graphGetter);
    }};
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
    return TestClientFactory.build().maxContentLength(1000000).serializer(serializer);
  }

}
