package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import javax.annotation.Nullable;

public interface YTDBGraphBaseQuery {

  ResultSet execute(DatabaseSessionEmbedded session);

  @Nullable
  ExecutionPlan explain(DatabaseSessionEmbedded session);

  int usedIndexes(DatabaseSessionEmbedded session);
}
