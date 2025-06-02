package com.jetbrains.youtrack.db.api.gremlin;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;

public interface YTDBStatefulEdge extends YTDBEdge {

  @Override
  RID id();

  StatefulEdge getUnderlyingEdge();
}
