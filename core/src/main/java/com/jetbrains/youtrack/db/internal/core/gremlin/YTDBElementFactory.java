package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;

public class YTDBElementFactory {

  private final YTDBGraphInternal graph;

  public YTDBElementFactory(YTDBGraphInternal graph) {
    this.graph = graph;
  }

  public YTDBStatefulEdgeImpl wrapEdge(StatefulEdge edge) {
    return new YTDBStatefulEdgeImpl(graph, edge);
  }

  public YTDBVertexImpl wrapVertex(Vertex vertex) {
    return new YTDBVertexImpl(graph, vertex);
  }
}
