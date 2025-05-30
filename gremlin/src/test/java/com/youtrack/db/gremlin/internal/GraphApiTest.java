package com.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.internal.StreamUtils;
import com.jetbrains.youtrack.db.api.record.RID;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;


public class GraphApiTest extends GraphBaseTest {

  @Test
  public void shouldGetEmptyEdges() {
    var vertex = graph.addVertex(T.label, "Person", "name", "Foo");
    var edges = vertex.edges(Direction.OUT, "HasFriend");
    graph.tx().commit();

    var collected = StreamUtils.asStream(edges).toList();
    Assert.assertEquals(0, collected.size());
  }

  @Test
  public void testLinklistProperty() {
    var vertex = graph.addVertex(T.label, "Person", "name", "Foo");
    var vertex2 = graph.addVertex(T.label, "Person", "name", "Bar");
    var vertex3 = graph.addVertex(T.label, "Person", "name", "Baz");
    graph.tx().commit();

    var listProp = new ArrayList<>();
    listProp.add(vertex2.id());
    listProp.add(vertex3.id());

    vertex.property("links", listProp);

    var retrieved = vertex.value("links");
    Assert.assertTrue(retrieved instanceof List);

    @SuppressWarnings("unchecked")
    var resultList = (List<Object>) retrieved;
    for (var o : resultList) {
      Assert.assertTrue(o instanceof RID);
    }
  }
}
