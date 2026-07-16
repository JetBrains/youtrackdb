package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GraphStepStrategyTest extends GraphBaseTest {

  /**
   * Isolate the native {@code YTDBGraphStepStrategy} has-container fold under test. With the
   * Gremlin-to-MATCH translator on (its default), a fully recognised {@code g.V().has(...)} shape is
   * rewritten to a single boundary step before the native fold runs, so this test would see one
   * {@code YTDBMatchPlanStep} rather than the folded {@code YTDBGraphStep} it asserts on. Disabling
   * the translator exercises the native folding path this test targets.
   */
  @Before
  public void disableGremlinToMatchTranslator() {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    tx.getDatabaseSession()
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, false);
  }

  @Test
  public void shouldFoldInHasContainers() {
    var g = graph.traversal();
    ////
    var traversal = g.V().has("name", "marko").asAdmin();
    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    Assert.assertEquals(2, traversal.getSteps().size());
    Assert.assertEquals(HasStep.class, traversal.getEndStep().getClass());
    traversal.applyStrategies();
    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    Assert.assertEquals(1, traversal.getSteps().size());
    Assert.assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    Assert.assertEquals(YTDBGraphStep.class, traversal.getEndStep().getClass());
    assertEquals(1, ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().size());
    assertEquals(
        "name",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getKey());
    assertEquals(
        "marko",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getValue());
    ////
    traversal = g.V().has("name", "marko").has("age", P.gt(20)).asAdmin();
    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    traversal.applyStrategies();

    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    Assert.assertEquals(1, traversal.getSteps().size());
    Assert.assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    assertEquals(2, ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().size());
    ////
    traversal = g.V().has("name", "marko").out().has("name", "daniel").asAdmin();

    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    traversal.applyStrategies();

    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    Assert.assertEquals(3, traversal.getSteps().size());
    Assert.assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    assertEquals(1, ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().size());
    assertEquals(
        "name",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getKey());
    assertEquals(
        "marko",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getValue());
    Assert.assertEquals(HasStep.class, traversal.getEndStep().getClass());
  }
}
