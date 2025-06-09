package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;

public class YTDBElementImplFactory implements YTDBElementFactory {

  public static final YTDBElementImplFactory INSTANCE = new YTDBElementImplFactory();

  @Override
  public YTDBEdgeInternal wrapEdge(YTDBGraphInternal graph, StatefulEdge edge) {
    return new YTDBStatefulEdgeImpl(graph, edge);
  }

  @Override
  public YTDBVertexInternal wrapVertex(YTDBGraphInternal graph, Vertex vertex) {
    return new YTDBVertexImpl(graph, vertex);
  }
}
