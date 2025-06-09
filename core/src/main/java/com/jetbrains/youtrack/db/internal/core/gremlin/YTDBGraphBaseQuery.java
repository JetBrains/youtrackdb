package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import javax.annotation.Nullable;

public interface YTDBGraphBaseQuery {
  ResultSet execute(YTDBGraphInternal graph);

  @Nullable
  ExecutionPlan explain(YTDBGraphInternal graph);

  int usedIndexes(YTDBGraphInternal graph);
}
