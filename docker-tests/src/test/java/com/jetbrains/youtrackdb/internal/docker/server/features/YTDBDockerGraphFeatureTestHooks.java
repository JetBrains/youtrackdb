package com.jetbrains.youtrackdb.internal.docker.server.features;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.docker.StdOutConsumer;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ResourceList;
import java.util.List;
import java.util.Locale;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.structure.io.graphml.GraphMLResourceAccess;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONResourceAccess;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoResourceAccess;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

public class YTDBDockerGraphFeatureTestHooks {

  public static final String DEFAULT_DB_NAME = "graph";

  public static final String ROOT_USER_NAME = "root";
  public static final String ROOT_USER_PASSWORD = "root";

  public static final String ADMIN_USER_NAME = "adminuser";
  public static final String ADMIN_USER_PASSWORD = "adminpwd";

  private static final boolean debug = Boolean.getBoolean("ytdb.testcontainer.debug.container");

  private final static GenericContainer<?> server = new GenericContainer<>(
      "youtrackdb/youtrackdb-server");

  public static final String SERVER_DATA_GRAPHS = "/opt/ytdb-server/data/graphs";
  public static YouTrackDB youTrackDB;

  @BeforeAll
  public static void setupSuite() throws Exception {
    server.withLogConsumer(new StdOutConsumer<>("ytdbServer:"));
    server.withCopyToContainer(Transferable.of(ROOT_USER_PASSWORD),
        "/opt/ytdb-server/secrets/root_password");

    if (debug) {
      server.withCommand("debug");
      server.setPortBindings(List.of("5005:5005"));
      server.withExposedPorts(8182, 5005);
    } else {
      server.withExposedPorts(8182);
    }

    server.waitingFor(Wait.forListeningPorts(8182));

    var classGraph = new ClassGraph();
    classGraph.acceptPaths(GryoResourceAccess.class.getPackageName().replace(".", "/"));
    classGraph.acceptPaths(GraphSONResourceAccess.class.getPackageName().replace(".", "/"));
    classGraph.acceptPaths(GraphMLResourceAccess.class.getPackageName().replace(".", "/"));

    try (var scanResult = classGraph.scan()) {
      var kryoResources = scanResult.getResourcesWithExtension(".kryo");
      registerIoFiles(kryoResources);

      var graphsonResources = scanResult.getResourcesWithExtension(".json");
      registerIoFiles(graphsonResources);

      var graphmlResources = scanResult.getResourcesWithExtension(".xml");
      registerIoFiles(graphmlResources);
    }

    server.start();

    youTrackDB = YourTracks.instance(server.getHost(), server.getMappedPort(8182),
        ROOT_USER_NAME, ROOT_USER_PASSWORD);

    reloadAllTestGraphs();
  }

  private static void registerIoFiles(ResourceList kryoResources) {
    kryoResources.forEach(resource -> {
      var resourcePath = resource.getPath();
      var resourceName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
      server.withCopyFileToContainer(MountableFile.forClasspathResource(resourcePath),
          SERVER_DATA_GRAPHS + "/" + resourceName);
    });
  }


  @AfterAll
  public static void tearDownSuite() throws Exception {
    youTrackDB.close();
    server.stop();
  }

  private static void reloadAllTestGraphs() {
    var graphsToLoad = LoadGraphWith.GraphData.values();
    var dbType = calculateDbType();

    youTrackDB.createIfNotExists(DEFAULT_DB_NAME, dbType,
        new LocalUserCredential(ADMIN_USER_NAME, ADMIN_USER_PASSWORD, PredefinedLocalRole.ADMIN));

    for (var graphToLoad : graphsToLoad) {
      var graphName = getServerGraphName(graphToLoad);
      var location = graphToLoad.location();
      var fileName = location.substring(location.lastIndexOf('/') + 1);

      youTrackDB.createIfNotExists(graphName, dbType,
          new LocalUserCredential(ADMIN_USER_NAME, ADMIN_USER_PASSWORD, PredefinedLocalRole.ADMIN)
      );

      try (var traversal = youTrackDB.openTraversal(graphName)) {
        traversal.autoExecuteInTx(g -> g.V().drop());
        traversal.autoExecuteInTx(g -> g.io(SERVER_DATA_GRAPHS + "/" +
            fileName).read().iterate());
      }
    }
  }

  private static DatabaseType calculateDbType() {
    final var testConfig =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());
    if ("ci".equals(testConfig) || "release".equals(testConfig)) {
      return DatabaseType.DISK;
    }

    return DatabaseType.MEMORY;
  }

  public static String getServerGraphName(final LoadGraphWith.GraphData loadGraphWith) {
    if (null == loadGraphWith) {
      return DEFAULT_DB_NAME;
    }

    return loadGraphWith.name().toLowerCase(Locale.ROOT);
  }
}
