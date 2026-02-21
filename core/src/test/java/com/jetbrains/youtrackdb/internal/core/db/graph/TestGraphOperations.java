package com.jetbrains.youtrackdb.internal.core.db.graph;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class TestGraphOperations extends DbTestBase {

  @Test
  public void testEdgeUniqueConstraint() {

    session.createVertexClass("TestVertex");

    var testLabel = session.createEdgeClass("TestLabel");

    var key = testLabel.createProperty("key", PropertyType.STRING);

    key.createIndex(SchemaClass.INDEX_TYPE.UNIQUE);

    session.begin();
    var vertex = session.newVertex("TestVertex");

    var vertex1 = session.newVertex("TestVertex");

    var edge = vertex.addStateFulEdge(vertex1, "TestLabel");

    edge.setProperty("key", "unique");
    session.commit();

    try {
      session.begin();
      var tx = session.getActiveTransaction();
      edge = tx.<Vertex>load(vertex)
          .addStateFulEdge(tx.load(vertex1), "TestLabel");
      edge.setProperty("key", "unique");
      session.commit();
      Assert.fail("It should not be inserted  a duplicated edge");
    } catch (RecordDuplicatedException e) {
      // Expected: duplicate edge should be rejected
    }

    session.begin();
    var tx2 = session.getActiveTransaction();
    edge = tx2.<Vertex>load(vertex)
        .addStateFulEdge(tx2.load(vertex1), "TestLabel");
    edge.setProperty("key", "notunique");
    session.commit();
  }
}
