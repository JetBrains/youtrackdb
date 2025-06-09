package com.jetbrains.youtrack.db.internal.core.gremlin;

import java.util.Date;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

public class GraphLabelTest extends GraphBaseTest {
  @Test
  public void testLabel() {
    var vertex = graph.addVertex(T.label, "Person", "name", "John");
    var vertex1 = graph.addVertex(T.label, "Person", "name", "Luke");

    vertex.addEdge("Friend", vertex1, "from", new Date());
    graph.tx().commit();

    Assert.assertEquals(2, session.countClass("Person"));
    Assert.assertEquals(1, session.countClass("Friend"));
  }
}
