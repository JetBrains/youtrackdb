package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import javax.annotation.Nullable;

public interface YTDBGraphBaseQuery {

  ResultSet execute(DatabaseSessionEmbedded session);

  @Nullable
  ExecutionPlan explain(DatabaseSessionEmbedded session);

  int usedIndexes(DatabaseSessionEmbedded session);
}
