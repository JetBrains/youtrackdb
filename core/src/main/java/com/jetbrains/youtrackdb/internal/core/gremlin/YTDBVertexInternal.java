package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;


public interface YTDBVertexInternal extends YTDBVertex {

  com.jetbrains.youtrackdb.api.record.Vertex getRawEntity();
}
