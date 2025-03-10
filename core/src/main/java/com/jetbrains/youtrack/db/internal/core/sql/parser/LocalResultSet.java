package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class LocalResultSet implements ResultSet {

  private ExecutionStream stream = null;
  private final InternalExecutionPlan executionPlan;
  @Nullable
  private DatabaseSessionInternal session;

  public LocalResultSet(InternalExecutionPlan executionPlan) {
    this.executionPlan = executionPlan;
    this.session = session;
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
    assert session == null || session.assertIfNotActive();
    if (!hasNext()) {
      throw new IllegalStateException();
    }
    return stream.next(executionPlan.getContext());
  }

  @Override
  public void close() {
    stream.close(executionPlan.getContext());
    executionPlan.close();
    session = null;
  }

  @Override
  @Nullable
  public ExecutionPlan getExecutionPlan() {
    return executionPlan;
  }

  @Override
  public Map<String, Long> getQueryStats() {
    return new HashMap<>(); // TODO
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }
}
