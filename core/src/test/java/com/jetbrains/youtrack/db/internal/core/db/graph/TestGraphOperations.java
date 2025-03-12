package com.jetbrains.youtrack.db.internal.core.db.graph;

import com.jetbrains.youtrack.db.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
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
      edge = session.bindToSession(vertex)
          .addStateFulEdge(session.bindToSession(vertex1), "TestLabel");
      edge.setProperty("key", "unique");
      session.commit();
      Assert.fail("It should not be inserted  a duplicated edge");
    } catch (RecordDuplicatedException e) {

    }

    session.begin();
    edge = session.bindToSession(vertex)
        .addStateFulEdge(session.bindToSession(vertex1), "TestLabel");
    edge.setProperty("key", "notunique");
    session.commit();
  }
}
