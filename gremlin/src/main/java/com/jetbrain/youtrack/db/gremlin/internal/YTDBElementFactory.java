package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;

public class YTDBElementFactory {

  private final YTDBGraphInternal graph;

  public YTDBElementFactory(YTDBGraphInternal graph) {
    this.graph = graph;
  }

  public YTDBStatefulEdge wrapEdge(StatefulEdge edge) {
    return new YTDBStatefulEdge(graph, edge);
  }

  public YTDBVertex wrapVertex(Vertex vertex) {
    return new YTDBVertex(graph, vertex);
  }
}
