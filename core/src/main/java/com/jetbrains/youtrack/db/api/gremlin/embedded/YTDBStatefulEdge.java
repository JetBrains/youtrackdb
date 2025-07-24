package com.jetbrains.youtrack.db.api.gremlin.embedded;

import com.jetbrains.youtrack.db.api.record.RID;

public interface YTDBStatefulEdge extends YTDBEdge {

  @Override
  RID id();
}
