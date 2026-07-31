package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
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

    Assert.assertEquals(YTDBMatchPlanStep.class, admin.getStartStep().getClass());
    Assert.assertEquals(YTDBMatchPlanStep.class, admin.getEndStep().getClass());
    // Guard the target class, not just the step type: the boundary's plan must count class V.
    var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
    Assert.assertTrue(
        "count boundary should target class V",
        boundary.getPlan().prettyPrint(0, 2).contains("CALCULATE CLASS SIZE: V"));
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
    session.getSchema().createVertexClass("Person");
    var traversal = graph.traversal();

    var admin = traversal.V().hasLabel("Person").count().asAdmin();

    admin.applyStrategies();

    Assert.assertEquals(YTDBMatchPlanStep.class, admin.getStartStep().getClass());
    Assert.assertEquals(YTDBMatchPlanStep.class, admin.getEndStep().getClass());
    // Guard the target class, not just the step type: the boundary's plan must count class Person.
    var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
    Assert.assertTrue(
        "count boundary should target class Person",
        boundary.getPlan().prettyPrint(0, 2).contains("CALCULATE CLASS SIZE: Person"));
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

  /**
   * Non-polymorphic {@code hasLabel(Person).count()} must translate (not fall to {@code
   * YTDBClassCountStep}) and use leaf-exact {@code CountFromClassStep}, excluding subclass rows.
   */
  @Test
  public void shouldUseExactClassCount_nonPolymorphicHasLabel() {
    var person = session.getSchema().createVertexClass("Person");
    session.getSchema().createClass("Employee", person);

    graph.addVertex(T.label, "Person");
    graph.addVertex(T.label, "Person");
    graph.addVertex(T.label, "Employee");
    graph.tx().commit();

    withNonPolymorphicDefault(
        () -> {
          var admin = graph.traversal().V().hasLabel("Person").count().asAdmin();
          admin.applyStrategies();
          Assert.assertEquals(YTDBMatchPlanStep.class, admin.getStartStep().getClass());
          var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
          var planText = boundary.getPlan().prettyPrint(0, 2);
          Assert.assertTrue(
              "non-poly hasLabel count must use exact class-size short-circuit",
              planText.contains("CALCULATE CLASS SIZE: Person (exact)"));
          Assert.assertEquals(
              2L, graph.traversal().V().hasLabel("Person").count().next().longValue());
        });
  }

  /**
   * Bare {@code g.V().count()} under non-poly still counts the full vertex hierarchy (native forces
   * polymorphic on empty has-containers).
   */
  @Test
  public void shouldCountAllVertices_nonPolymorphicBareV() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person");
    graph.addVertex();
    graph.tx().commit();

    withNonPolymorphicDefault(
        () -> {
          var admin = graph.traversal().V().count().asAdmin();
          admin.applyStrategies();
          Assert.assertEquals(YTDBMatchPlanStep.class, admin.getStartStep().getClass());
          var boundary = (YTDBMatchPlanStep<?, ?>) admin.getStartStep();
          var planText = boundary.getPlan().prettyPrint(0, 2);
          Assert.assertTrue(planText.contains("CALCULATE CLASS SIZE: V"));
          Assert.assertFalse(
              "bare V count must stay polymorphic even under non-poly sessions",
              planText.contains("(exact)"));
          Assert.assertEquals(2L, graph.traversal().V().count().next().longValue());
        });
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

  private void withNonPolymorphicDefault(Runnable body) {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    var config = tx.getDatabaseSession().getConfiguration();
    Assert.assertNotNull(config);
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }
}
