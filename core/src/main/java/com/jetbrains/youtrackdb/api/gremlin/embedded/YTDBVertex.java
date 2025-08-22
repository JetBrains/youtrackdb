package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.record.RID;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public interface YTDBVertex extends Vertex, YTDBElement {

  @Override
  RID id();

  @Override
  YTDBGraph graph();

  @Override
  YTDBEdge addEdge(String label, Vertex inVertex, Object... keyValues);

  com.jetbrains.youtrackdb.api.record.Vertex getUnderlyingVertex();
}