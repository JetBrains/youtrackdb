package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.StatefulEdge;

public interface YTDBEdgeInternal extends YTDBEdge {

  StatefulEdge getRawEntity();
}
