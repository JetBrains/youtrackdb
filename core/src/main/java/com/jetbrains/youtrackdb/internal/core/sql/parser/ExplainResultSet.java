package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.ExecutionPlan;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseStats;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
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

  private boolean closed = false;

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
    if (closed) {
      return false;
    }

    return hasNext;
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();
    if (closed || !hasNext) {
      throw new NoSuchElementException();
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
    if (closed) {
      return;
    }

    assert session == null || session.assertIfNotActive();
    this.session = null;
    this.closed = true;
  }

  @Override
  public @Nonnull ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();

    return executionPlan;
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }

  @Override
  public boolean tryAdvance(Consumer<? super Result> action) {
    if (hasNext()) {
      action.accept(next());
      return true;
    }
    return false;
  }

  @Nullable
  @Override
  public ResultSet trySplit() {
    return null;
  }

  @Override
  public long estimateSize() {
    return Long.MAX_VALUE;
  }

  @Override
  public int characteristics() {
    return ORDERED;
  }

  @Override
  public void forEachRemaining(@Nonnull Consumer<? super Result> action) {
    while (hasNext()) {
      action.accept(next());
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

}
