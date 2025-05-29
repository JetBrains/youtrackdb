package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;

import javax.annotation.Nullable;

public interface YTDBGraphBaseQuery {

  ResultSet execute(YTDBGraphInternal graph);

  @Nullable
  ExecutionPlan explain(YTDBGraphInternal graph);

  int usedIndexes(YTDBGraphInternal graph);
}
