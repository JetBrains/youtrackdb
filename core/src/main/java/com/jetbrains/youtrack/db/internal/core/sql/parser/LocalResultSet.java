package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LocalResultSet implements ResultSet {

  private ExecutionStream stream = null;
  private final InternalExecutionPlan executionPlan;
  @Nullable
  private DatabaseSessionInternal session;

  private boolean closed = false;

  public LocalResultSet(DatabaseSessionInternal session, InternalExecutionPlan executionPlan) {
    this.executionPlan = executionPlan;
    this.session = session;

    start();
  }

  private void start() {
    assert session == null || session.assertIfNotActive();
    checkClosed();

    stream = executionPlan.start();
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
    if (closed) {
      return false;
    }

    return stream.hasNext(executionPlan.getContext());
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();

    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    return stream.next(executionPlan.getContext());
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }

    assert session == null || session.assertIfNotActive();

    stream.close(executionPlan.getContext());
    executionPlan.close();
    session = null;
    closed = true;
  }

  @Override
  public @Nullable ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();

    return executionPlan;
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return session;
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

  private void checkClosed() {
    if (closed) {
      throw new IllegalStateException("ResultSet is closed and can not be used");
    }
  }
}
