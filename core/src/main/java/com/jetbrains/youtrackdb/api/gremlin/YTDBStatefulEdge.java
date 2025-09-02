package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.StatefulEdge;

public interface YTDBStatefulEdge extends YTDBEdge {

  @Override
  RID id();

  StatefulEdge getUnderlyingEdge();
}
