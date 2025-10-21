package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.map.YTDBClassCountStep;
import java.util.Collections;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

public class GraphCountStrategyTest extends GraphBaseTest {

  @Test
  public void shouldUseGlobalCountStepWithV() {
    var traversal = graph.traversal();

    var admin = traversal.V().count().asAdmin();
    admin.applyStrategies();

    var startStep = admin.getStartStep();
    Assert.assertEquals(YTDBClassCountStep.class, startStep.getClass());
    Assert.assertEquals(YTDBClassCountStep.class, admin.getEndStep().getClass());

    var countStep = (YTDBClassCountStep<?>) startStep;
    assertEquals(Collections.singletonList("V"), countStep.getKlasses());
  }

  @Test
  public void shouldCountWithV() {
    for (var i = 0; i < 10; i++) {
      graph.addVertex();
    }
    graph.tx().commit();

    var g = graph.traversal();

    Assert.assertEquals(10, g.V().count().toStream().findFirst().get().longValue());
  }

  @Test
  public void shouldCountWithVWithAlias() {
    for (var i = 0; i < 10; i++) {
      graph.addVertex();
    }

    graph.tx().commit();
    var g = graph.traversal();
    Assert.assertEquals(10, g.V().as("a").count().toStream().findFirst().get().longValue());
  }

  @Test
  public void shouldUseGlobalCountStepWithE() {
    var traversal = graph.traversal();
    var admin = traversal.E().count().asAdmin();

    admin.applyStrategies();

    var startStep = admin.getStartStep();
    Assert.assertEquals(YTDBClassCountStep.class, startStep.getClass());
    Assert.assertEquals(YTDBClassCountStep.class, admin.getEndStep().getClass());

    var countStep = (YTDBClassCountStep<?>) startStep;
    assertEquals(Collections.singletonList("E"), countStep.getKlasses());
  }

  @Test
  public void shouldCountWithE() {
    var v1 = graph.addVertex();
    var v2 = graph.addVertex();
    for (var i = 0; i < 10; i++) {
      v1.addEdge("Rel", v2);
    }
    graph.tx().commit();

    var g = graph.traversal();

    Assert.assertEquals(10, g.E().count().toStream().findFirst().get().longValue());
    Assert.assertEquals(
        10, g.E().hasLabel("Rel").count().toStream().findFirst().get().longValue());
  }

  @Test
  public void shouldUseGlobalCountStepWithCustomClass() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("Person"));
    var traversal = graph.traversal();

    var admin = traversal.V().hasLabel("Person").count().asAdmin();

    admin.applyStrategies();

    var startStep = admin.getStartStep();
    Assert.assertEquals(YTDBClassCountStep.class, startStep.getClass());
    Assert.assertEquals(YTDBClassCountStep.class, admin.getEndStep().getClass());

    var countStep = (YTDBClassCountStep<?>) startStep;
    assertEquals(Collections.singletonList("Person"), countStep.getKlasses());
  }

  @Test
  public void shouldCountWithPerson() {
    for (var i = 0; i < 10; i++) {
      graph.addVertex(T.label, "Person");
    }
    graph.tx().commit();

    var g = graph.traversal();
    Assert.assertEquals(
        10, g.V().hasLabel("Person").count().toStream().findFirst().get().longValue());
  }

  @Test
  public void shouldUseLocalCountStep() {
    var v1 = graph.addVertex(T.label, "Person");
    var v2 = graph.addVertex(T.label, "Person");

    for (var i = 0; i < 10; i++) {
      v1.addEdge("HasFriend", v2);
    }
    graph.tx().commit();

    var traversal = graph.traversal();

    var count =
        traversal.V().hasLabel("Person").out("HasFriend").count().toStream().findFirst()
            .orElseThrow();

    Assert.assertEquals(10L, count.longValue());
  }
}
