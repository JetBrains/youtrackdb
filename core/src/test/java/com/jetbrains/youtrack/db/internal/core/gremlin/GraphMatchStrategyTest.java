package com.jetbrains.youtrack.db.internal.core.gremlin;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

public class GraphMatchStrategyTest extends GraphBaseTest {

  @Test
  public void shouldUseMatchOptimization() {
    var cls = session.createClass("VMatch");
    var property = cls.createProperty("name", PropertyType.STRING);
    property.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var traversal = graph.traversal();
    var admin =
        traversal.V().hasLabel("VMatch").
            match(__.as("a").has("name", "Enrico").out("Friends").as("b"))
            .asAdmin();

    admin.applyStrategies();

    var startStep = admin.getStartStep();
    Assert.assertEquals(YTDBGraphStep.class, startStep.getClass());

    var graphStep = (YTDBGraphStep<?, ?>) startStep;
    assertEquals(2, graphStep.getHasContainers().size());

    Assert.assertEquals(1, usedIndexes(session, admin));
  }

  @Test
  public void shouldUseMatchOptimizationWithLabel() {

    var cls = session.createVertexClass("Person");
    var property = cls.createProperty("name", PropertyType.STRING);
    property.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var traversal = graph.traversal();

    var admin =
        traversal.V().match(__.as("a").has("name", "Foo").out("Friends").as("b")).asAdmin();

    admin.applyStrategies();

    var startStep = admin.getStartStep();
    Assert.assertEquals(YTDBGraphStep.class, startStep.getClass());

    var graphStep = (YTDBGraphStep<?, ?>) startStep;
    assertEquals(1, graphStep.getHasContainers().size());

    Assert.assertEquals(0, usedIndexes(session, admin));

    admin =
        traversal
            .V()
            .match(__.as("a").hasLabel("Person").
                has("name", "Foo").out("Friends").as("b"))
            .asAdmin();

    admin.applyStrategies();

    startStep = admin.getStartStep();
    Assert.assertEquals(YTDBGraphStep.class, startStep.getClass());

    graphStep = (YTDBGraphStep<?, ?>) startStep;
    assertEquals(2, graphStep.getHasContainers().size());

    Assert.assertEquals(1, usedIndexes(session, admin));
  }

  @Test
  public void shouldFetchDataUsingMatchOptimization() {
    var cls = session.createVertexClass("Person");
    var property = cls.createProperty("name", PropertyType.STRING);
    property.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    var bar = graph.addVertex(T.label, "Person", "name", "Bar");

    for (var i = 0; i < 100; i++) {
      var foo = graph.addVertex(T.label, "Person", "name", "Foo" + i);
      bar.addEdge("Friends", foo);
    }
    graph.tx().commit();

    var traversal = graph.traversal();

    var admin =
        traversal.V().match(__.as("a").has("name", "Foo0").in("Friends").as("b")).toList();

    Assert.assertEquals(1, admin.size());

    admin =
        traversal
            .V()
            .match(__.as("a").hasLabel("Person").has("name", "Foo0").in("Friends").as("b"))
            .toList();

    Assert.assertEquals(1, admin.size());
  }
}
