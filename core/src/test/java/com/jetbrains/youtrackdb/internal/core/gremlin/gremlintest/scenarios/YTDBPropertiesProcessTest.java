package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversal;
import com.jetbrains.youtrackdb.api.gremlin.__;
import java.util.List;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GremlinProcessRunner.class)
public class YTDBPropertiesProcessTest extends YTDBAbstractGremlinTest {

  @Test
  @LoadGraphWith(MODERN)
  public void removeFromOne() {
    assertTrue(
        marko()
            .removeProperty("age")
            .valueMap("age")
            .next()
            .isEmpty()
    );
    assertEquals(
        3,
        people().has("age").toList().size()
    );
  }

  @Test
  @LoadGraphWith(MODERN)
  public void removeFromNone() {

    assertFalse(
        people()
            .has("age", P.gt(100))
            .removeProperty("age")
            .hasNext()
    );
    assertEquals(
        4,
        people().has("age").toList().size()
    );
  }

  @Test
  @LoadGraphWith(MODERN)
  public void removeFromAll() {
    assertEquals(
        4,
        people().removeProperty("age").toList().size()
    );
    assertEquals(
        0,
        people().has("age").toList().size()
    );
  }

  @Test
  @LoadGraphWith(MODERN)
  public void removeFromOther() {
    assertEquals(
        6,
        g().V().removeProperty("age").toList().size()
    );
    assertEquals(
        0,
        people().has("age").toList().size()
    );
  }

  @Test
  @LoadGraphWith(MODERN)
  public void removeNonExisting() {
    assertEquals(
        4,
        people().removeProperty("non-existing").toList().size()
    );

    // check that all properties are still there
    assertEquals(
        List.of(List.of("name", "age")),
        people()
            .project("keys")
            .by(__.properties().key().fold())
            .select("keys")
            .dedup()
            .toList()
    );
  }

  @Test
  @LoadGraphWith(MODERN)
  public void removeFromEdge() {
    assertEquals(
        2,
        g().E().hasLabel("knows").removeProperty("weight").toList().size()
    );

    assertFalse(
        g().E()
            .hasLabel("knows")
            .has("weight")
            .hasNext()
    );

    assertEquals(
        4,
        g().E()
            .hasLabel("created")
            .has("weight")
            .toList()
            .size()
    );
  }

  private YTDBGraphTraversal<Vertex, Vertex> marko() {
    return people().has("name", "marko");
  }

  private YTDBGraphTraversal<Vertex, Vertex> people() {
    return g().V().hasLabel("person");
  }

}
