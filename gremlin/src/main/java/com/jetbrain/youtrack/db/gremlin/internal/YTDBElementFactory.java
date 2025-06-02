package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;

public class YTDBElementFactory {

  private final YTDBGraphInternal graph;

  public YTDBElementFactory(YTDBGraphInternal graph) {
    this.graph = graph;
  }

  public YTDBEdgeImpl wrapEdge(StatefulEdge edge) {
    return new YTDBEdgeImpl(graph, edge);
  }

  public YTDBVertexImpl wrapVertex(Vertex vertex) {
    return new YTDBVertexImpl(graph, vertex);
  }
}
