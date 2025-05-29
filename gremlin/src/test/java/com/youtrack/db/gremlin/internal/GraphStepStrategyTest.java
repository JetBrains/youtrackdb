package com.youtrack.db.gremlin.internal;

import static org.junit.Assert.assertEquals;

import com.jetbrain.youtrack.db.gremlin.internal.traversal.step.sideeffect.YTDBGraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;

import org.junit.Test;

public class GraphStepStrategyTest extends GraphBaseTest {

  @Test
  public void shouldFoldInHasContainers() {
    var g = graph.traversal();
    ////
    var traversal = g.V().has("name", "marko").asAdmin();
    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    assertEquals(2, traversal.getSteps().size());
    assertEquals(HasStep.class, traversal.getEndStep().getClass());
    traversal.applyStrategies();
    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    assertEquals(1, traversal.getSteps().size());
    assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    assertEquals(YTDBGraphStep.class, traversal.getEndStep().getClass());
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

    assertEquals(1, traversal.getSteps().size());
    assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    assertEquals(2, ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().size());
    ////
    traversal = g.V().has("name", "marko").out().has("name", "daniel").asAdmin();

    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    traversal.applyStrategies();

    System.out.println("STEPS:");
    traversal.getSteps().forEach(System.out::println);

    assertEquals(3, traversal.getSteps().size());
    assertEquals(YTDBGraphStep.class, traversal.getStartStep().getClass());
    assertEquals(1, ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().size());
    assertEquals(
        "name",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getKey());
    assertEquals(
        "marko",
        ((YTDBGraphStep<?, ?>) traversal.getStartStep()).getHasContainers().getFirst().getValue());
    assertEquals(HasStep.class, traversal.getEndStep().getClass());
  }
}
