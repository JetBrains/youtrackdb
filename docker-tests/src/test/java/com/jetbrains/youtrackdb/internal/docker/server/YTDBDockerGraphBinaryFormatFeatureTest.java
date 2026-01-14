package com.jetbrains.youtrackdb.internal.docker.server;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import com.jetbrains.youtrackdb.internal.docker.server.features.YTDBDockerGraphFeatureTestHooks;
import io.cucumber.guice.CucumberModules;
import io.cucumber.guice.GuiceFactory;
import io.cucumber.guice.InjectorSource;
import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import java.nio.file.Paths;
import org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData;
import org.apache.tinkerpop.gremlin.features.World;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.junit.runner.RunWith;

@SuppressWarnings("deprecation")
@RunWith(Cucumber.class)
@CucumberOptions(
    tags = "not @MultiProperties "
        + "and not @GraphComputerOnly "
        + "and not @UserSuppliedVertexPropertyIds "
        + "and not @UserSuppliedEdgeIds "
        + "and not @UserSuppliedVertexIds "
        + "and not @TinkerServiceRegistry "
        + "and not @DisallowNullPropertyValues "
        + "and not @InsertionOrderingRequired "
        + "and not @DataUUID "
        + "and not @DataDateTime",
    glue = {"org.apache.tinkerpop.gremlin.features",
        "com.jetbrains.youtrackdb.internal.docker.server.features"},
    objectFactory = GuiceFactory.class,
    features = {"classpath:/org/apache/tinkerpop/gremlin/test/features"},
    plugin = {"progress", "junit:target/cucumber.xml"})
public class YTDBDockerGraphBinaryFormatFeatureTest {

  @SuppressWarnings("NewClassNamingConvention")
  public static final class ServiceModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(World.class).to(YTDBGraphWorld.class);
    }
  }

  @SuppressWarnings("NewClassNamingConvention")
  public static class YTDBGraphWorld implements World {

    @Override
    public GraphTraversalSource getGraphTraversalSource(GraphData graphData) {
      return YTDBDockerGraphFeatureTestHooks.youTrackDB.openTraversal(
          YTDBDockerGraphFeatureTestHooks.getServerGraphName(graphData));
    }

    @Override
    public String convertIdToScript(Object id, Class<? extends Element> type) {
      return "\"" + id + "\"";
    }

    @Override
    public String changePathToDataFile(final String pathToFileFromGremlin) {
      var fileName = Paths.get(pathToFileFromGremlin).getFileName().toString();

      String resourceName;
      if (fileName.endsWith(".kryo")) {
        resourceName = fileName.substring(0, fileName.length() - 5) + "-v3.kryo";
      } else if (fileName.endsWith(".json")) {
        resourceName = fileName.substring(0, fileName.length() - 5) + "-v3.json";
      } else if (fileName.endsWith(".xml")) {
        resourceName = fileName;
      } else {
        throw new IllegalArgumentException(fileName + " is not supported");
      }

      return YTDBDockerGraphFeatureTestHooks.SERVER_DATA_GRAPHS + "/" + resourceName;
    }

    @Override
    public void afterEachScenario() {
      try (var traversal = YTDBDockerGraphFeatureTestHooks.youTrackDB.openTraversal("graph")) {
        traversal.autoExecuteInTx(g ->
            g.V().drop()
        );
      }
    }
  }

  @SuppressWarnings("NewClassNamingConvention")
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
