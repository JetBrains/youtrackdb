package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.junit.Assert.assertEquals;

import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GremlinProcessRunner.class)
public class YTDBAddVertexProcessTest extends YTDBAbstractGremlinTest {

  @Test
  @LoadGraphWith(GraphData.MODERN)
  public void addVertexWhileIteratingSimple() {

    final var added = g().V().addV("animal").property("age", 0).toList();

    assertEquals(6, added.size());

    assertEquals(
        6,
        g().V().has("animal", "age", 0).count().next().longValue()
    );
  }

  @Test
  public void addVertexWhileIteratingAfterGraphModification() {

    g().addV("foo").iterate();
    g().addV("bar").iterate();
    g().V().drop().iterate();
    g().tx().commit();

    g().addV("foo").iterate();

    g().tx().commit();
    assertEquals(
        1,
        g().V().count().next().longValue()
    );

    g().V().addV("bar").iterate();

    assertEquals(
        2,
        g().V().count().next().longValue()
    );
  }
}
