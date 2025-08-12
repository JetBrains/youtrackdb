package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.query.ExecutionPlan;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import javax.annotation.Nullable;

public interface YTDBGraphBaseQuery {

  ResultSet execute(DatabaseSessionEmbedded session);

  @Nullable
  ExecutionPlan explain(DatabaseSessionEmbedded session);

  int usedIndexes(DatabaseSessionEmbedded session);
}
