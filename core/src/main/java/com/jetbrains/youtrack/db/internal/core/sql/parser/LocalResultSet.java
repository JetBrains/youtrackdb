package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
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

  long totalExecutionTime = 0;
  long startTime = 0;

  public LocalResultSet(@Nullable DatabaseSessionInternal session,
      InternalExecutionPlan executionPlan) {
    this.executionPlan = executionPlan;
    this.session = session;
    start();
  }

  private void start() {
    assert session == null || session.assertIfNotActive();
    var begin = System.currentTimeMillis();
    try {
      if (stream == null) {
        startTime = begin;
      }
      stream = executionPlan.start();
      if (!stream.hasNext(executionPlan.getContext())) {
        logProfiling();
      }
    } finally {
      totalExecutionTime += (System.currentTimeMillis() - begin);
    }
  }

  @Override
  public boolean hasNext() {
    var next = stream.hasNext(executionPlan.getContext());
    if (!next) {
      logProfiling();
    }
    return next;
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();
    if (!hasNext()) {
      throw new IllegalStateException();
    }
    return stream.next(executionPlan.getContext());
  }

  private void logProfiling() {
    if (executionPlan.getStatement() != null && YouTrackDBEnginesManager.instance().getProfiler()
        .isRecording()) {
      if (session != null) {
        final var user = session.geCurrentUser();
        final var userString = user != null ? user.toString() : null;
        YouTrackDBEnginesManager.instance()
            .getProfiler()
            .stopChrono(
                "db."
                    + session.getDatabaseName()
                    + ".command.sql."
                    + executionPlan.getStatement(),
                "Command executed against the database",
                System.currentTimeMillis() - totalExecutionTime,
                "db.*.command.*",
                null,
                userString);
      }
    }
  }

  public long getTotalExecutionTime() {
    return totalExecutionTime;
  }

  public long getStartTime() {
    return startTime;
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
}
