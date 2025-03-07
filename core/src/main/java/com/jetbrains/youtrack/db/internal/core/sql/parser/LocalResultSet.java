package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 *
 */
public class LocalResultSet implements ResultSet {

  private ExecutionStream stream = null;
  private final InternalExecutionPlan executionPlan;

  public LocalResultSet(InternalExecutionPlan executionPlan) {
    this.executionPlan = executionPlan;
    start();
  }

  private void start() {
    stream = executionPlan.start();
  }

  @Override
  public boolean hasNext() {
    return stream.hasNext(executionPlan.getContext());
  }

  @Override
  public Result next() {
    if (!hasNext()) {
      throw new IllegalStateException();
    }
    return stream.next(executionPlan.getContext());
  }

  @Override
  public void close() {
    stream.close(executionPlan.getContext());
    executionPlan.close();
  }

  @Override
  public Optional<ExecutionPlan> getExecutionPlan() {
    return Optional.of(executionPlan);
  }

  @Override
  public Map<String, Long> getQueryStats() {
    return new HashMap<>(); // TODO
  }
}
