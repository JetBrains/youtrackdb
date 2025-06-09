package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;

public interface YTDBElementFactory {

  YTDBEdgeInternal wrapEdge(YTDBGraphInternal graph, StatefulEdge edge);

  YTDBVertexInternal wrapVertex(YTDBGraphInternal graph, Vertex vertex);
}
