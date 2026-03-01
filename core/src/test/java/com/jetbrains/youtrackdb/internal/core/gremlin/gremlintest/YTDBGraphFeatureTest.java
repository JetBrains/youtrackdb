package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import io.cucumber.guice.CucumberModules;
import io.cucumber.guice.GuiceFactory;
import io.cucumber.guice.InjectorSource;
import io.cucumber.java.Scenario;
import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import java.io.File;
import java.util.Locale;
import java.io.IOException;
import java.nio.file.Paths;
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
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(
    tags = "not @RemoteOnly "
        + "and not @MultiProperties "
        + "and not @GraphComputerOnly "
        + "and not @UserSuppliedVertexPropertyIds "
        + "and not @UserSuppliedEdgeIds "
        + "and not @UserSuppliedVertexIds "
        + "and not @TinkerServiceRegistry "
        + "and not @DisallowNullPropertyValues "
        + "and not @InsertionOrderingRequired "
        + "and not @DataUUID "
        + "and not @DataDateTime",
    glue = {"org.apache.tinkerpop.gremlin.features"},
    objectFactory = GuiceFactory.class,
    features = {"classpath:/org/apache/tinkerpop/gremlin/test/features",
        "classpath:/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/features"},
    plugin = {"progress", "junit:target/cucumber.xml"})
public class YTDBGraphFeatureTest {

  private static final Map<String, String> IGNORED_TESTS = Map.of(
      "g_injectXhello_hiX_concat_XV_valuesXnameXX",
      "YouTrackDB doesn't guarantee a consistent order of element's IDs"
  );

  public static final class ServiceModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(World.class).to(YTDBGraphWorld.class);
    }
  }

  public static class YTDBGraphWorld implements World {

    public static final ConcurrentHashMap<String, String> PATHS = new ConcurrentHashMap<>();

    private static final YTDBGraph GRAPH_MODERN = initGraph(GraphData.MODERN);
    private static final YTDBGraph GRAPH_CLASSIC = initGraph(GraphData.CLASSIC);
    private static final YTDBGraph GRAPH_CREW = initGraph(GraphData.CREW);
    private static final YTDBGraph GRAPH_GRATEFUL = initGraph(GraphData.GRATEFUL);
    private static final YTDBGraph GRAPH_SINK = initGraph(GraphData.SINK);
    private static final YTDBGraph GRAPH_EMPTY = initGraph(null);

    @Override
    public GraphTraversalSource getGraphTraversalSource(GraphData graphData) {
      return (switch (graphData) {
        case null -> GRAPH_EMPTY;
        case CLASSIC -> GRAPH_CLASSIC;
        case MODERN -> GRAPH_MODERN;
        case CREW -> GRAPH_CREW;
        case GRATEFUL -> GRAPH_GRATEFUL;
        case SINK -> GRAPH_SINK;
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
                return TestHelper.generateTempFileFromResource(GryoResourceAccess.class, resourceName,
                        "")
                    .getAbsolutePath();
              }
              if (file.endsWith(".json")) {
                var resourceName = fileName.substring(0, fileName.length() - 5) + "-v3.json";
                return TestHelper.generateTempFileFromResource(GraphSONResourceAccess.class,
                        resourceName,
                        "")
                    .getAbsolutePath();
              }
              if (file.endsWith(".xml")) {
                return TestHelper.generateTempFileFromResource(GraphMLResourceAccess.class, fileName,
                    "").getAbsolutePath();
              }
            } catch (IOException e) {
              throw new RuntimeException(e);
            }

            throw new IllegalArgumentException(file + " is not supported");
          }
      );
      return realPath;
    }

    private static YTDBGraph initGraph(GraphData graphData) {
      final var configs = new BaseConfiguration();
      final var directory =
          makeTestDirectory(graphData == null ? "default" : graphData.name().toLowerCase(Locale.ROOT));

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

    private static String makeTestDirectory(final String graphName) {
      return getWorkingDirectory() + File.separator
          + RandomStringUtils.randomAlphabetic(10) + File.separator
          + TestHelper.cleanPathSegment(graphName);
    }

    private static void cleanEmpty() {
      GRAPH_EMPTY.traversal().V().drop().iterate();
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
      cleanEmpty();
    }

    private static void readIntoGraph(final Graph graph, final GraphData graphData) {
      try {
        final var dataFile = TestHelper.generateTempFileFromResource(
            graph.getClass(),
            GryoResourceAccess.class,
            Paths.get(graphData.location()).getFileName().toString(),
            "", false
        ).getAbsolutePath();
        graph.traversal().io(dataFile).read().iterate();
      } catch (IOException ioe) {
        throw new IllegalStateException(ioe);
      }
    }

    private static String getWorkingDirectory() {
      return TestHelper.makeTestDataDirectory(YTDBGraphFeatureTest.class, "graph-provider-data");
    }
  }

  public static final class WorldInjectorSource implements InjectorSource {

    @Override
    public Injector getInjector() {
      return Guice.createInjector(Stage.PRODUCTION, CucumberModules.createScenarioModule(),
          new ServiceModule());
    }
  }
}
