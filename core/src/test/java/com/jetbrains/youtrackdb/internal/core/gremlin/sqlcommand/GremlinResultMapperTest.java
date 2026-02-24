package com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class GremlinResultMapperTest extends GraphBaseTest {

  @After
  public void rollbackOpenTx() {
    if (graph.tx().isOpen()) {
      graph.tx().rollback();
    }
  }

  @Test
  public void shouldMapNullValueInProjection() {
    var results = sqlCommand("SELECT NULL AS value");

    Assert.assertEquals(1, results.size());
    var map = asMap(results.getFirst());
    Assert.assertNull(map.get("value"));
  }

  @Test
  public void shouldPassThroughScalars() {
    Assert.assertEquals(42, singleProjection("SELECT 42 AS value").get("value"));
    Assert.assertEquals("hello world",
        singleProjection("SELECT 'hello world' AS value").get("value"));
    Assert.assertEquals(true, singleProjection("SELECT true AS value").get("value"));

    Assert.assertTrue(singleProjection("SELECT 9999999999 AS value")
        .get("value") instanceof Number);
    Assert.assertTrue(singleProjection("SELECT 3.14 AS value")
        .get("value") instanceof Number);
  }

  @Test
  public void shouldMapFullRecordSelectToVertex() {
    session.getSchema().createVertexClass("Animal");
    graph.addVertex(T.label, "Animal", "species", "Cat");
    graph.tx().commit();

    var results = sqlCommand("SELECT FROM Animal");

    Assert.assertEquals(1, results.size());
    Assert.assertSame(YTDBVertexImpl.class, results.getFirst().getClass());
  }

  @Test
  public void shouldMapMultipleVertices() {
    session.getSchema().createVertexClass("Color");
    graph.addVertex(T.label, "Color", "name", "Red");
    graph.addVertex(T.label, "Color", "name", "Blue");
    graph.addVertex(T.label, "Color", "name", "Green");
    graph.tx().commit();

    var results = sqlCommand("SELECT FROM Color ORDER BY name ASC");

    Assert.assertEquals(3, results.size());
    assertAllInstanceOf(results, Vertex.class);
  }

  @Test
  public void shouldMapFullRecordSelectToEdge() {
    session.getSchema().createVertexClass("City");
    session.getSchema().createEdgeClass("Road");

    var a = graph.addVertex(T.label, "City", "name", "Paris");
    var b = graph.addVertex(T.label, "City", "name", "Lyon");
    a.addEdge("Road", b, "distance", 465);
    graph.tx().commit();

    var results = sqlCommand("SELECT FROM Road");

    Assert.assertEquals(1, results.size());
    Assert.assertSame(YTDBStatefulEdgeImpl.class, results.getFirst().getClass());
  }

  @Test
  public void shouldMapMultipleEdges() {
    session.getSchema().createVertexClass("Node");
    session.getSchema().createEdgeClass("Link");

    var n1 = graph.addVertex(T.label, "Node", "name", "N1");
    var n2 = graph.addVertex(T.label, "Node", "name", "N2");
    var n3 = graph.addVertex(T.label, "Node", "name", "N3");
    n1.addEdge("Link", n2);
    n2.addEdge("Link", n3);
    graph.tx().commit();

    var results = sqlCommand("SELECT FROM Link");

    Assert.assertEquals(2, results.size());
    assertAllInstanceOf(results, Edge.class);
  }

  @Test
  public void shouldWrapVertexRidAsVertex() {
    session.getSchema().createVertexClass("Item");
    graph.addVertex(T.label, "Item", "name", "Widget");
    graph.tx().commit();

    var map = singleProjection("SELECT @rid AS rid FROM Item");

    assertInstanceOf(map.get("rid"), Vertex.class);
  }

  @Test
  public void shouldWrapEdgeRidAsEdge() {
    session.getSchema().createVertexClass("Point");
    session.getSchema().createEdgeClass("Line");

    var p1 = graph.addVertex(T.label, "Point", "name", "A");
    var p2 = graph.addVertex(T.label, "Point", "name", "B");
    p1.addEdge("Line", p2);
    graph.tx().commit();

    var map = singleProjection("SELECT @rid AS rid FROM Line");

    assertInstanceOf(map.get("rid"), Edge.class);
  }

  @Test
  public void shouldMapLinkPropertyAsVertex() {
    session.getSchema().createVertexClass("Employee");
    graph.addVertex(T.label, "Employee", "name", "Alice");
    graph.addVertex(T.label, "Employee", "name", "Bob");
    graph.tx().commit();

    session.command("BEGIN");
    session.command(
        "UPDATE Employee SET manager = (SELECT FROM Employee WHERE name = 'Bob')"
            + " WHERE name = 'Alice'"
    );
    session.command("COMMIT");

    var map = singleProjection("SELECT manager FROM Employee WHERE name = 'Alice'");

    if (map.get("manager") instanceof List<?> managers) {
      Assert.assertEquals(1, managers.size());
      assertInstanceOf(managers.getFirst(), Vertex.class);
    } else {
      assertInstanceOf(map.get("manager"), Vertex.class);
    }
  }

  @Test
  public void shouldMapEmbeddedListProjection() {
    var map = singleProjection("SELECT [1, 2, 3] AS nums");

    var list = asList(map.get("nums"));
    Assert.assertEquals(3, list.size());
    Assert.assertEquals(1, list.get(0));
    Assert.assertEquals(2, list.get(1));
    Assert.assertEquals(3, list.get(2));
  }

  @Test
  public void shouldMapEmptyListProjection() {
    var map = singleProjection("SELECT [] AS items");

    Assert.assertTrue(asList(map.get("items")).isEmpty());
  }

  @Test
  public void shouldMapEmbeddedMapProjection() {
    var map = singleProjection("SELECT {'a': 1, 'b': 2} AS data");

    var nested = asMap(map.get("data"));
    Assert.assertEquals(1, nested.get("a"));
    Assert.assertEquals(2, nested.get("b"));
  }

  @Test
  public void shouldMapEmptyMapProjection() {
    var map = singleProjection("SELECT {} AS data");

    Assert.assertTrue(asMap(map.get("data")).isEmpty());
  }

  @Test
  public void shouldRecursivelyWrapRidsInsideList() {
    session.getSchema().createVertexClass("Tag");
    graph.addVertex(T.label, "Tag", "name", "A");
    graph.addVertex(T.label, "Tag", "name", "B");
    graph.tx().commit();

    var map = singleProjection("SELECT list(@rid) AS rids FROM Tag");

    assertAllInstanceOf(asList(map.get("rids")), Vertex.class);
  }

  @Test
  public void shouldRecursivelyWrapRidInsideMap() {
    session.getSchema().createVertexClass("Ref");
    graph.addVertex(T.label, "Ref", "name", "target");
    graph.tx().commit();

    var map = singleProjection(
        "SELECT {'ref': first(@rid)} AS data FROM Ref"
    );

    var nested = asMap(map.get("data"));
    assertInstanceOf(nested.get("ref"), Vertex.class);
  }

  @Test
  public void shouldMapMultipleProjectedFields() {
    session.getSchema().createVertexClass("Product");
    graph.addVertex(T.label, "Product", "name", "Laptop", "price", 999);
    graph.tx().commit();

    var map = singleProjection(
        "SELECT name, price, @rid AS rid FROM Product"
    );

    Assert.assertEquals("Laptop", map.get("name"));
    Assert.assertEquals(999, map.get("price"));
    assertInstanceOf(map.get("rid"), Vertex.class);
  }

  @Test
  public void shouldMapInsertReturnAsVertex() {
    session.getSchema().createVertexClass("Log");

    graph.tx().readWrite();
    var results = sqlCommand("INSERT INTO Log SET message = 'entry1'");

    Assert.assertEquals(1, results.size());
    assertInstanceOf(results.getFirst(), Vertex.class);
  }

  @Test
  public void shouldMapCountAggregate() {
    session.getSchema().createVertexClass("Entry");
    graph.addVertex(T.label, "Entry", "val", 1);
    graph.addVertex(T.label, "Entry", "val", 2);
    graph.addVertex(T.label, "Entry", "val", 3);
    graph.tx().commit();

    var map = singleProjection("SELECT count(*) AS cnt FROM Entry");

    Assert.assertTrue(map.get("cnt") instanceof Number);
    Assert.assertEquals(3L, ((Number) map.get("cnt")).longValue());
  }

  @Test
  public void shouldMapSumAggregate() {
    session.getSchema().createVertexClass("Score");
    graph.addVertex(T.label, "Score", "points", 10);
    graph.addVertex(T.label, "Score", "points", 20);
    graph.addVertex(T.label, "Score", "points", 30);
    graph.tx().commit();

    var map = singleProjection("SELECT sum(points) AS total FROM Score");

    Assert.assertTrue(map.get("total") instanceof Number);
    Assert.assertEquals(60L, ((Number) map.get("total")).longValue());
  }

  @Test
  public void shouldHandleMixedVertexAndProjectionResults() {
    session.getSchema().createVertexClass("Fruit");
    graph.addVertex(T.label, "Fruit", "name", "Apple");
    graph.tx().commit();

    var vertices = sqlCommand("SELECT FROM Fruit");
    var projections = sqlCommand("SELECT 'hello' AS greeting");

    Assert.assertEquals(1, vertices.size());
    Assert.assertTrue(vertices.getFirst() instanceof Vertex);

    Assert.assertEquals(1, projections.size());
    Assert.assertTrue(projections.getFirst() instanceof Map);
  }

  @Test
  public void shouldReturnEmptyListForNoResults() {
    session.getSchema().createVertexClass("Ghost");

    var results = sqlCommand("SELECT FROM Ghost");

    Assert.assertTrue(results.isEmpty());
  }

  private List<Object> sqlCommand(String sql) {
    return graph.traversal().sqlCommand(sql).toList();
  }

  private Map<String, Object> singleProjection(String sql) {
    var results = sqlCommand(sql);

    Assert.assertEquals("Expected exactly 1 result row", 1, results.size());
    return asMap(results.getFirst());
  }

  private static void assertInstanceOf(Object value, Class<?> type) {
    Assert.assertTrue(
        "Expected " + type.getSimpleName() + " but got " + value.getClass().getSimpleName(),
        type.isInstance(value)
    );
  }

  private static void assertAllInstanceOf(List<?> values, Class<?> type) {
    for (var value : values) {
      assertInstanceOf(value, type);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    assertInstanceOf(value, Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object value) {
    assertInstanceOf(value, List.class);
    return (List<Object>) value;
  }
}
