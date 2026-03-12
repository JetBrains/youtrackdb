package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import io.cucumber.guice.CucumberModules;
import io.cucumber.guice.GuiceFactory;
import io.cucumber.guice.InjectorSource;
import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.apache.tinkerpop.gremlin.features.World;
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

  public static final class ServiceModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(World.class).to(YTDBGraphWorld.class);
    }
  }

  public static class YTDBGraphWorld extends GraphFeatureWorld {

    public YTDBGraphWorld() {
      super(YTDBGraphFeatureTest.class);
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
