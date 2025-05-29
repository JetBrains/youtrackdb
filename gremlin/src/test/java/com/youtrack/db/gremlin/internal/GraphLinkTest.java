package com.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.internal.YTDBVertex;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

public class GraphLinkTest extends GraphBaseTest {

  @Test
  public void testLinks() throws Exception {
    var vertex = graph.addVertex(T.label, getClass().getSimpleName(), "name", "John");
    var vertex1 = graph.addVertex(T.label, getClass().getSimpleName(), "name", "Luke");
    vertex.property("friend", vertex1);
    graph.tx().commit();

    graph.close();

    var rid = vertex.id();
    graph = openGraph();

    var v = graph.vertices(rid).next();
    var val = v.value("friend");

    Assert.assertTrue(val instanceof YTDBVertex);
    Assert.assertEquals("Luke", ((YTDBVertex) val).value("name"));
  }
}
