package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.IteratorResultSet;
import java.util.Collections;

public class YTDBGraphEmptyQuery implements YTDBGraphBaseQuery {

  @Override
  public ResultSet execute(YTDBGraphInternal graph) {
    return new IteratorResultSet(graph.getUnderlyingDatabaseSession(),
        Collections.emptyIterator());
  }

  @Override
  public ExecutionPlan explain(YTDBGraphInternal graph) {
    return null;
  }

  @Override
  public int usedIndexes(YTDBGraphInternal graph) {
    return 0;
  }
}
