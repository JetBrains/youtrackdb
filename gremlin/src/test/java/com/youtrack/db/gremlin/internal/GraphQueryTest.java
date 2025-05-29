package com.youtrack.db.gremlin.internal;

import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;


public class GraphQueryTest extends GraphBaseTest {

  @Test
  public void shouldCountVerticesEdges() {
    initGraph(graph);

    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();
    Assert.assertEquals(4L, count.longValue());

    count = graph.traversal().V().hasLabel("Person").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().V().hasLabel("Animal").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().V().hasLabel("Animal", "Person").count().toList().getFirst();
    Assert.assertEquals(4L, count.longValue());

    // Count on E

    count = graph.traversal().E().count().toList().getFirst();
    Assert.assertEquals(3L, count.longValue());

    count = graph.traversal().E().hasLabel("HasFriend").count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().E().hasLabel("HasAnimal").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().E().hasLabel("HasAnimal", "HasFriend").count().toList().getFirst();
    Assert.assertEquals(3L, count.longValue());

    // Inverted Count

    count = graph.traversal().V().hasLabel("HasFriend").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    count = graph.traversal().E().hasLabel("Person").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    // More Complex Count

    count =
        graph
            .traversal()
            .V()
            .has("Person", "name", "Jon")
            .out("HasFriend", "HasAnimal")
            .count()
            .toList()
            .getFirst();
    Assert.assertEquals(2L, count.longValue());

    // With Polymorphism

    count = graph.traversal().V().has("Person", "name", "Jon").
        out("E").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    // With Base Class V/E

    count = graph.traversal().V().has("name", "Jon").count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().E().has("name", "Jon").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    count = graph.traversal().V().has("name", "Jon").out("E").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().E().has("marker", 10).count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().V().has("marker", 10).count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldCountVerticesEdgesOnTXRollback() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("name", "Jon");

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().rollback();

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldExecuteTraversalWithSpecialCharacters() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("identifier", 1);

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().commit();

    count = graph.traversal().V().has("~identifier", 1).count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldNotBlowWithWrongClass() {
    initGraph(graph);

    var count = graph.traversal().V().hasLabel("Wrong").toList().size();

    Assert.assertEquals(0, count);

    // Count on Person + Wrong Class

    count = graph.traversal().V().hasLabel("Person", "Wrong").toList().size();

    Assert.assertEquals(2, count);
  }

  @Test
  public void hasIdWithString() {
    final var labelVertex = "VertexLabel";
    var v1 = graph.addVertex(labelVertex);

    graph.tx().commit();

    Assert.assertEquals(1, graph.traversal().V().hasId(v1.id()).toList().size());
  }

  @Test
  @Ignore
  public void hasIdWithVertex() {
    final var labelVertex = "VertexLabel";
    var v1 = graph.addVertex(labelVertex);

    graph.tx().commit();

    Assert.assertEquals(1, graph.traversal().V().hasId(v1).toList().size());
  }

  @Test
  public void shouldCountVerticesEdgesOnTXCommit() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("name", "Jon");

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().commit();

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());
  }

  @Test
  public void shouldWorkWithTwoLabels() {
    session.getSchema().createVertexClass("Person");

    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex(T.label, "Person", "name", "Jon");

    count =
        graph
            .traversal()
            .V()
            .hasLabel("Person")
            .has("name", "Jon")
            .hasLabel("Person")
            .has("name", "Jon")
            .count()
            .toList()
            .getFirst();

    Assert.assertEquals(1L, count.longValue());
  }

  protected void initGraph(Graph graph) {
    var schema = session.getSchema();

    schema.createVertexClass("Person");
    schema.createVertexClass("Animal");
    schema.createEdgeClass("HasFriend");
    session.createEdgeClass("HasAnimal");

    var v1 = graph.addVertex(T.label, "Person", "name", "Jon");
    var v2 = graph.addVertex(T.label, "Person", "name", "Frank");

    v1.addEdge("HasFriend", v2);

    var v3 = graph.addVertex(T.label, "Animal", "name", "Foo");
    var v4 = graph.addVertex(T.label, "Animal", "name", "Bar");

    v1.addEdge("HasAnimal", v3, "marker", 10);
    v2.addEdge("HasAnimal", v4);
  }
}
