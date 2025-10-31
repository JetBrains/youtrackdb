package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.record.StatefulEdge;

public interface YTDBStatefulEdgeInternal extends YTDBEdge {

  StatefulEdge getRawEntity();
}
