package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import io.cucumber.guice.CucumberModules;
import io.cucumber.guice.GuiceFactory;
import io.cucumber.guice.InjectorSource;
import io.cucumber.java.Scenario;
import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import java.io.File;
import java.util.Map;
import org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData;
import org.apache.tinkerpop.gremlin.features.World;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.junit.AssumptionViolatedException;
import org.junit.Ignore;
import org.junit.runner.RunWith;

@Ignore
@RunWith(Cucumber.class)
@CucumberOptions(
    tags = "not @RemoteOnly and not @MultiProperties and not @GraphComputerOnly and not @UserSuppliedVertexPropertyIds and not @UserSuppliedEdgeIds and not @UserSuppliedVertexIds and not @TinkerServiceRegistry and not @DisallowNullPropertyValues and not @InsertionOrderingRequired",
    glue = {"org.apache.tinkerpop.gremlin.features"},
    objectFactory = GuiceFactory.class,
    features = {"classpath:/org/apache/tinkerpop/gremlin/test/features"},
    plugin = {"progress", "junit:target/cucumber.xml"})
public class YTDBRemoteGraphFeatureTest {

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

    private static final YTDBAbstractRemoteGraphProvider provider = new YTDBGraphSONRemoteGraphProvider();

    static {
      try {
        provider.startServer();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public GraphTraversalSource getGraphTraversalSource(GraphData graphData) {
      final var graph = (switch (graphData) {
        case null -> initGraph(null);
        case CLASSIC -> initGraph(GraphData.CLASSIC);
        case MODERN -> initGraph(GraphData.MODERN);
        case CREW -> initGraph(GraphData.CREW);
        case GRATEFUL -> initGraph(GraphData.GRATEFUL);
        case SINK -> initGraph(GraphData.SINK);
      });

      return provider.traversal(graph);
    }

    private static Graph initGraph(GraphData graphData) {
      final var config =
          provider.standardGraphConfiguration(YTDBRemoteGraphFeatureTest.class, "y", graphData);
      return provider.openTestGraph(config);
    }

    private void cleanEmpty() {
      initGraph(null).traversal().E().drop().iterate();
      initGraph(null).traversal().V().drop().iterate();
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

    @Override
    public void afterEachScenario() {
      provider.closeConnections();
    }

    @Override
    public String changePathToDataFile(final String pathToFileFromGremlin) {
      return ".." + File.separator + pathToFileFromGremlin;
    }
  }

  public static final class WorldInjectorSource implements InjectorSource {

    @Override
    public Injector getInjector() {
      return Guice.createInjector(
          Stage.PRODUCTION,
          CucumberModules.createScenarioModule(),
          new ServiceModule()
      );
    }
  }
}
