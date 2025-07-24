package com.jetbrains.youtrack.db.api.gremlin.embedded;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.api.record.RID;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public interface YTDBVertex extends Vertex, YTDBElement {
  @Override
  RID id();

  @Override
  YTDBGraph graph();

  @Override
  YTDBEdge addEdge(String label, Vertex inVertex, Object... keyValues);
}