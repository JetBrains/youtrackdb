package com.youtrack.db.gremlin.internal;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Assert;
import org.junit.Test;

public class TransactionIdsTest extends GraphBaseTest {
  @Test
  public void withTransaction() {
    final var labelVertex = "VertexLabel";
     graph.addVertex(labelVertex);
    graph.tx().commit();

    graph.traversal().V().next();

    var v2 = graph.addVertex(labelVertex);
    graph.tx().commit();

    GraphTraversal<Vertex, Edge> traversal =
        graph.traversal().V(v2.id()).outE().as("edge").otherV().hasId(v2).select("edge");
    Assert.assertTrue(traversal.hasNext());
  }
}
