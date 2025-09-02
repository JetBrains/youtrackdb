package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.record.RID;

public interface YTDBStatefulEdge extends YTDBEdge {

  @Override
  RID id();
}
