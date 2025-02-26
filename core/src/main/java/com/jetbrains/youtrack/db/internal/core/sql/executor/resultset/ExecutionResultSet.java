package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;
import javax.annotation.Nullable;

public class ExecutionResultSet implements ResultSet {

  private final ExecutionStream stream;
  private final CommandContext context;
  private final ExecutionPlan plan;
  @Nullable
  private DatabaseSessionInternal session;

  public ExecutionResultSet(
      ExecutionStream stream, CommandContext context, ExecutionPlan plan) {
    super();
    this.stream = stream;
    this.context = context;
    this.session = context.getDatabaseSession();
    this.plan = plan;
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
    return stream.hasNext(context);
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();
    return stream.next(context);
  }

  @Override
  public void close() {
    assert session == null || session.assertIfNotActive();
    stream.close(context);
    this.session = null;
  }

  @Override
  public ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();
    return plan;
  }

  @Override
  public Map<String, Long> getQueryStats() {
    assert session == null || session.assertIfNotActive();
    return null;
  }
}
