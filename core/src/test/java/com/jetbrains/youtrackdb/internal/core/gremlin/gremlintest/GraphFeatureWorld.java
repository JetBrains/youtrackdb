package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import io.cucumber.java.Scenario;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData;
import org.apache.tinkerpop.gremlin.TestHelper;
import org.apache.tinkerpop.gremlin.features.World;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.io.graphml.GraphMLResourceAccess;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONResourceAccess;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoResourceAccess;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;
import org.junit.AssumptionViolatedException;

/**
 * Shared {@link World} implementation for TinkerPop Cucumber feature tests.
 *
 * <p>Manages the lifecycle of pre-loaded graph datasets (MODERN, CLASSIC, CREW,
 * GRATEFUL, SINK) and an empty scratch graph that is cleaned before each scenario.
 * Subclasses only need to supply the outer test class via {@link #GraphFeatureWorld(Class)}
 * so that each runner gets its own working directory.
 */
public class GraphFeatureWorld implements World {

  private static final Map<String, String> IGNORED_TESTS = Map.of(
      "g_injectXhello_hiX_concat_XV_valuesXnameXX",
      "YouTrackDB doesn't guarantee a consistent order of element's IDs"
  );

  /** Cached data-file paths shared across all World instances in the same JVM. */
  public static final ConcurrentHashMap<String, String> PATHS = new ConcurrentHashMap<>();

  // Lazily-initialized graph sets, one per test class, so that core and embedded
  // runners each get their own independent set of databases.
  private static final ConcurrentHashMap<Class<?>, GraphSet> GRAPH_CACHE =
      new ConcurrentHashMap<>();

  private final GraphSet graphs;

  protected GraphFeatureWorld(Class<?> testClass) {
    this.graphs = GRAPH_CACHE.computeIfAbsent(testClass, GraphFeatureWorld::createGraphs);
  }

  // ----- World interface implementation -----

  @Override
  public GraphTraversalSource getGraphTraversalSource(GraphData graphData) {
    return (switch (graphData) {
      case null -> graphs.empty;
      case CLASSIC -> graphs.classic;
      case MODERN -> graphs.modern;
      case CREW -> graphs.crew;
      case GRATEFUL -> graphs.grateful;
      case SINK -> graphs.sink;
    }).traversal();
  }

  @Override
  public String changePathToDataFile(final String pathToFileFromGremlin) {
    var fileName = Paths.get(pathToFileFromGremlin).getFileName().toString();
    @SuppressWarnings("UnnecessaryLocalVariable")
    var realPath = PATHS.compute(fileName, (file, path) -> {
          try {
            if (file.endsWith(".kryo")) {
              var resourceName = fileName.substring(0, fileName.length() - 5) + "-v3.kryo";
              return TestHelper.generateTempFileFromResource(
                      GryoResourceAccess.class, resourceName, "")
                  .getAbsolutePath();
            }
            if (file.endsWith(".json")) {
              var resourceName = fileName.substring(0, fileName.length() - 5) + "-v3.json";
              return TestHelper.generateTempFileFromResource(
                      GraphSONResourceAccess.class, resourceName, "")
                  .getAbsolutePath();
            }
            if (file.endsWith(".xml")) {
              return TestHelper.generateTempFileFromResource(
                  GraphMLResourceAccess.class, fileName, "").getAbsolutePath();
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }

          throw new IllegalArgumentException(file + " is not supported");
        }
    );
    return realPath;
  }

  @Override
  public String convertIdToScript(Object id, Class<? extends Element> type) {
    return "\"" + id + "\"";
  }

  @Override
  public void beforeEachScenario(final Scenario scenario) {
    if (IGNORED_TESTS.containsKey(scenario.getName())) {
      throw new AssumptionViolatedException(IGNORED_TESTS.get(scenario.getName()));
    }
    graphs.empty.traversal().V().drop().iterate();
  }

  // ----- Graph initialization helpers -----

  private record GraphSet(
      YTDBGraph modern, YTDBGraph classic, YTDBGraph crew,
      YTDBGraph grateful, YTDBGraph sink, YTDBGraph empty) {
  }

  private static GraphSet createGraphs(Class<?> testClass) {
    return new GraphSet(
        initGraph(GraphData.MODERN, testClass),
        initGraph(GraphData.CLASSIC, testClass),
        initGraph(GraphData.CREW, testClass),
        initGraph(GraphData.GRATEFUL, testClass),
        initGraph(GraphData.SINK, testClass),
        initGraph(null, testClass)
    );
  }

  private static YTDBGraph initGraph(GraphData graphData, Class<?> testClass) {
    final var configs = new BaseConfiguration();
    final var directory =
        makeTestDirectory(
            graphData == null ? "default" : graphData.name().toLowerCase(Locale.ROOT),
            testClass);

    try {
      FileUtils.deleteDirectory(new File(directory));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    YTDBGraphInitUtil.getBaseConfiguration("ssss", directory)
        .forEach(configs::setProperty);

    final var graph = (YTDBGraph) GraphFactory.open(configs);
    if (graphData != null) {
      readIntoGraph(graph, graphData);
    }
    return graph;
  }

  private static String makeTestDirectory(final String graphName, Class<?> testClass) {
    return TestHelper.makeTestDataDirectory(testClass, "graph-provider-data")
        + File.separator
        + RandomStringUtils.randomAlphabetic(10) + File.separator
        + TestHelper.cleanPathSegment(graphName);
  }

  private static void readIntoGraph(final Graph graph, final GraphData graphData) {
    try {
      final var dataFile = TestHelper.generateTempFileFromResource(
          graph.getClass(),
          GryoResourceAccess.class,
          graphData.location().substring(graphData.location().lastIndexOf(File.separator) + 1),
          "", false
      ).getAbsolutePath();
      graph.traversal().io(dataFile).read().iterate();
    } catch (IOException ioe) {
      throw new IllegalStateException(ioe);
    }
  }
}
