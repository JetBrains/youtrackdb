package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;


public interface YTDBVertexInternal extends YTDBVertex {

  Vertex getRawEntity();
}
