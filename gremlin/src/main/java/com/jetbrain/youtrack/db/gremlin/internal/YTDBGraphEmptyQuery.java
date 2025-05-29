package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.IteratorResultSet;


import java.util.Collections;

public class YTDBGraphEmptyQuery implements YTDBGraphBaseQuery {

  @Override
  public ResultSet execute(YTDBGraphInternal graph) {
    return new IteratorResultSet(graph.getUnderlyingSession(),
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
