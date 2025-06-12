package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;

public class YTDBElementWrapperFactory implements YTDBElementFactory {

  public static final YTDBElementWrapperFactory INSTANCE = new YTDBElementWrapperFactory();

  @Override
  public YTDBEdgeInternal wrapEdge(YTDBGraphInternal graph, StatefulEdge edge) {
    return new YTDBStatefulEdgeWrapper(graph, edge);
  }

  @Override
  public YTDBVertexInternal wrapVertex(YTDBGraphInternal graph, Vertex vertex) {
    return new YTDBVertexWrapper(graph, vertex);
  }
}
