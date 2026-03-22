package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;

public interface YTDBEdgeInternal extends YTDBEdge {

  Edge getRawEntity();
}
