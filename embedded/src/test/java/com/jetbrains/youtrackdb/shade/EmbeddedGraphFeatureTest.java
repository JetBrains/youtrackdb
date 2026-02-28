package com.jetbrains.youtrackdb.shade;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.GraphFeatureWorld;
import io.cucumber.guice.CucumberModules;
import io.cucumber.guice.GuiceFactory;
import io.cucumber.guice.InjectorSource;
import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.apache.tinkerpop.gremlin.features.World;
import org.junit.runner.RunWith;

/**
 * Cucumber runner that executes the TinkerPop Gremlin feature test suite against
 * the embedded (shaded uber-jar) module. Validates full Gremlin compliance using
 * the same datasets and scenarios as {@code YTDBGraphFeatureTest} in the core module.
 *
 * <p>Excluded from the default surefire run (heavyweight). Activated only when
 * the {@code ci-integration-tests} Maven profile is enabled.
 */
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
public class EmbeddedGraphFeatureTest {

  public static final class ServiceModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(World.class).to(EmbeddedGraphWorld.class);
    }
  }

  public static class EmbeddedGraphWorld extends GraphFeatureWorld {

    public EmbeddedGraphWorld() {
      super(EmbeddedGraphFeatureTest.class);
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
