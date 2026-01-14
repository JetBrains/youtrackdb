package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites;


import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import org.apache.tinkerpop.gremlin.structure.PropertyTest;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.junit.Test;

/// BasicPropertyTest with corrected "empty" properties logic.
public class YTDBBasicPropertyStructureTest extends PropertyTest.BasicPropertyTest {

  @Test
  @Override
  public void shouldReturnEmptyPropertyIfKeyNonExistent() {
    final Vertex v = graph.addVertex("name", "marko");
    tryCommit(graph, (graph) -> {
      final Vertex v1 = graph.vertices(v.id()).next();
      final VertexProperty p = v1.property("nonexistentkey");
      // using YTDBVertexProperty.empty() instead of VertexProperty.empty()
      assertEquals(YTDBVertexProperty.empty(), p);
    });
  }
}
