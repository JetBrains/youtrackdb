package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseStats;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 *
 */
public class ExplainResultSet implements ResultSet {

  private final ExecutionPlan executionPlan;
  private final DatabaseStats dbStats;
  boolean hasNext = true;
  @Nullable
  private DatabaseSessionInternal session;

  public ExplainResultSet(@Nullable DatabaseSessionInternal session, ExecutionPlan executionPlan,
      DatabaseStats dbStats) {
    this.executionPlan = executionPlan;
    this.dbStats = dbStats;
    this.session = session;

    assert session == null || session.assertIfNotActive();
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
    return hasNext;
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();
    if (!hasNext) {
      throw new IllegalStateException();
    }

    var result = new ResultInternal(session);
    if (executionPlan != null) {
      result.setProperty("executionPlan", executionPlan.toResult(session));
      result.setProperty("executionPlanAsString", executionPlan.prettyPrint(0, 3));
    }

    hasNext = false;
    return result;
  }

  @Override
  public void close() {
    assert session == null || session.assertIfNotActive();
    this.session = null;
  }

  @Override
  public ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();
    return executionPlan;
  }

  @Override
  public Map<String, Long> getQueryStats() {
    assert session == null || session.assertIfNotActive();
    return new HashMap<>();
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }
}
