package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@code yql()} on {@code YTDBGraphTraversalSource}.
 * Verifies that SQL queries executed through the Gremlin traversal source return
 * correctly typed results: vertices, edges, projected scalars, and link references.
 */
public class YTDBCommandServiceExecuteSqlTest extends GraphBaseTest {

  @After
  public void rollbackOpenTx() {
    if (graph.tx().isOpen()) {
      graph.tx().rollback();
    }
  }

  @Test
  public void shouldReturnVertexForFullRecordSelect() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var results = graph.traversal()
        .yql("SELECT FROM Person WHERE name = 'Alice'")
        .toList();

    Assert.assertEquals(1, results.size());
    Assert.assertTrue(
        "Expected Vertex but got " + results.getFirst().getClass().getSimpleName(),
        results.getFirst() instanceof Vertex
    );
  }

  @Test
  public void shouldReturnEdgeForFullRecordSelect() {
    session.getSchema().createVertexClass("Person");
    session.getSchema().createEdgeClass("Knows");

    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("Knows", bob);
    graph.tx().commit();

    var results = graph.traversal()
        .yql("SELECT FROM Knows")
        .toList();

    Assert.assertEquals(1, results.size());
    Assert.assertTrue(
        "Expected Edge but got " + results.getFirst().getClass().getSimpleName(),
        results.getFirst() instanceof Edge
    );
  }

  @Test
  public void shouldProjectIntegerScalar() {
    var map = executeSingleProjection("SELECT 1 AS value");

    Assert.assertEquals(1, map.get("value"));
  }

  @Test
  public void shouldProjectStringScalar() {
    var map = executeSingleProjection("SELECT 'hello' AS value");

    Assert.assertEquals("hello", map.get("value"));
  }

  @Test
  public void shouldProjectNull() {
    var map = executeSingleProjection("SELECT NULL AS value");

    Assert.assertNull(map.get("value"));
  }

  @Test
  public void shouldProjectEmptyList() {
    var map = executeSingleProjection("SELECT [] AS value");

    Assert.assertTrue(
        "Expected List but got " + map.get("value").getClass().getSimpleName(),
        map.get("value") instanceof List
    );
  }

  @Test
  public void shouldProjectEmptyMap() {
    var map = executeSingleProjection("SELECT {} AS value");

    Assert.assertTrue(
        "Expected Map but got " + map.get("value").getClass().getSimpleName(),
        map.get("value") instanceof Map
    );
  }

  @Test
  public void shouldProjectRidAsVertex() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var map = executeSingleProjection(
        "SELECT @rid AS rid FROM Person WHERE name = 'Alice'"
    );

    Assert.assertTrue(
        "Expected Vertex but got " + map.get("rid").getClass().getSimpleName(),
        map.get("rid") instanceof Vertex
    );
  }

  @Test
  public void shouldProjectLinkPropertyAsVertexList() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.addVertex(T.label, "Person", "name", "Bob");
    graph.tx().commit();

    session.command("BEGIN");
    session.command(
        "UPDATE Person SET friend = (SELECT FROM Person WHERE name = 'Bob')"
            + " WHERE name = 'Alice'"
    );
    session.command("COMMIT");

    var map = executeSingleProjection(
        "SELECT friend FROM Person WHERE name = 'Alice'"
    );

    if (map.get("friend") instanceof List<?> friends) {
      Assert.assertEquals(1, friends.size());
      Assert.assertTrue(
          "Expected Vertex but got " + friends.getFirst().getClass().getSimpleName(),
          friends.getFirst() instanceof Vertex
      );
    } else {
      Assert.fail("Expected List but got "
          + map.get("friend").getClass().getSimpleName());
    }
  }

  private Map<String, Object> executeSingleProjection(String sql) {
    var results = graph.traversal()
        .yql(sql)
        .toList();

    Assert.assertEquals(1, results.size());
    Assert.assertTrue(
        "Expected Map but got " + results.getFirst().getClass().getSimpleName(),
        results.getFirst() instanceof Map
    );

    @SuppressWarnings("unchecked")
    var map = (Map<String, Object>) results.getFirst();
    return map;
  }
}
