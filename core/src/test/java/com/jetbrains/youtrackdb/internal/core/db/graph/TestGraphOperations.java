package com.jetbrains.youtrackdb.internal.core.db.graph;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
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

    key.createIndex(SchemaManager.INDEX_TYPE.UNIQUE);

    session.begin();
    var vertex = session.newVertex("TestVertex");

    var vertex1 = session.newVertex("TestVertex");

    var edge = vertex.addStateFulEdge(vertex1, "TestLabel");

    edge.setProperty("key", "unique");
    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      var activeTx1 = session.getActiveTransaction();
      edge = activeTx1.<Vertex>load(vertex)
          .addStateFulEdge(activeTx.load(vertex1), "TestLabel");
      edge.setProperty("key", "unique");
      session.commit();
      Assert.fail("It should not be inserted  a duplicated edge");
    } catch (RecordDuplicatedException e) {

    }

    session.begin();
    var activeTx = session.getActiveTransaction();
    var activeTx1 = session.getActiveTransaction();
    edge = activeTx1.<Vertex>load(vertex)
        .addStateFulEdge(activeTx.load(vertex1), "TestLabel");
    edge.setProperty("key", "notunique");
    session.commit();
  }
}
