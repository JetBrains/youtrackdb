package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.IteratorResultSet;
import java.util.Collections;

public class YTDBGraphEmptyQuery implements YTDBGraphBaseQuery {
  @Override
  public ResultSet execute(DatabaseSessionEmbedded session) {
    return new IteratorResultSet(session,
        Collections.emptyIterator());
  }

  @Override
  public ExecutionPlan explain(DatabaseSessionEmbedded session) {
    return null;
  }

  @Override
  public int usedIndexes(DatabaseSessionEmbedded session) {
    return 0;
  }
}
