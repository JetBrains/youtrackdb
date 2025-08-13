package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.junit.Assert;
import org.junit.Test;

public class GraphStepStrategyTest extends GraphBaseTest {

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
