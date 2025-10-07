package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

public class GraphComplexIndexTest extends GraphBaseTest {

  @Test
  public void compositeIndexSingleSecondFieldTest() {
    var foo = session.getMetadata().getSlowMutableSchema().createVertexClass("Foo");
    foo.createProperty("prop1", PropertyType.LONG);
    foo.createProperty("prop2", PropertyType.STRING);

    foo.createIndex("V_Foo", IndexType.UNIQUE, "prop1", "prop2");

    graph.addVertex(T.label, "Foo", "prop1", 1, "prop2", "4ab25da0-3602-4f4a-bc5e-28bfefa5ca4c");
    graph.tx().commit();

    var traversal =
        graph.traversal().V().has("Foo", "prop2", "4ab25da0-3602-4f4a-bc5e-28bfefa5ca4c");

    Assert.assertEquals(0, usedIndexes(session, traversal));

    var vertices = traversal.toList();
    Assert.assertEquals(1, vertices.size());
  }

  @Test
  public void compositeIndexSingleFirstFieldTest() {
    var foo = session.getSchema().createVertexClass("Foo");
    foo.createProperty("prop1", PropertyType.LONG);
    foo.createProperty("prop2", PropertyType.STRING);

    foo.createIndex("V_Foo", IndexType.UNIQUE, "prop1", "prop2");

    graph.addVertex(T.label, "Foo", "prop1", 1, "prop2", "4ab25da0-3602-4f4a-bc5e-28bfefa5ca4c");
    var traversal = graph.traversal().V().has("Foo", "prop1", 1);

    Assert.assertEquals(1, usedIndexes(session, traversal));
    var vertices = traversal.toList();
    Assert.assertEquals(1, vertices.size());
  }

  @Test
  public void compositeIndexTest() {
    var foo = session.getSchema().createVertexClass("Foo");
    foo.createProperty("prop1", PropertyType.LONG);
    foo.createProperty("prop2", PropertyType.STRING);

    foo.createIndex("V_Foo", IndexType.UNIQUE, "prop1", "prop2");
    graph.addVertex(T.label, "Foo", "prop1", 1,
        "prop2", "4ab25da0-3602-4f4a-bc5e-28bfefa5ca4c");

    var traversal =
        graph.traversal()
            .V()
            .hasLabel("Foo")
            .has("prop1", 1)
            .has("prop2", "4ab25da0-3602-4f4a-bc5e-28bfefa5ca4c");

    Assert.assertEquals(1, usedIndexes(session, traversal));

    var vertices = traversal.toList();
    Assert.assertEquals(1, vertices.size());
  }
}
