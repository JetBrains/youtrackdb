package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;

public interface YTDBStatefulEdge extends YTDBEdge {

  @Override
  RID id();
}
