package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import org.junit.Test;

public class VertexAndEdgeTest extends DbTestBase {

  @Test
  public void testAddEdgeAndDeleteVertex() {

    final var p = session.computeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();

      return new Pair<>(v1, v2);
    });

    session.executeInTx(tx -> {
      var v1 = tx.loadVertex(p.getKey());
      var v2 = tx.loadVertex(p.getValue());

      v1.addEdge(v2, "E");
      v2.delete();

    });
  }
}
