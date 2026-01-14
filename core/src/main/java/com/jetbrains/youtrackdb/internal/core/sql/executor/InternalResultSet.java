package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class InternalResultSet implements ResultSet {

  private final List<Result> content;

  private int next = 0;
  protected ExecutionPlan plan;

  @Nullable
  private DatabaseSessionInternal session;

  private boolean closed = false;

  public InternalResultSet(@Nullable DatabaseSessionInternal session) {
    this.session = session;
    this.content = new ArrayList<>();
  }

  public InternalResultSet(@Nullable DatabaseSessionInternal session,
      @Nonnull List<Result> content) {
    this.session = session;
    this.content = content;
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
    if (closed) {
      return false;
    }

    return content.size() > next;
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();
    if (closed) {
      throw new NoSuchElementException();
    }

    return content.get(next++);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }

    assert session == null || session.assertIfNotActive();
    this.content.clear();
    this.session = null;
    this.closed = true;
  }

  @Override
  public @Nullable ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();

    return plan;
  }

  public void setPlan(ExecutionPlan plan) {
    assert session == null || session.assertIfNotActive();
    checkClosed();

    this.plan = plan;
  }

  public void add(Result nextResult) {
    assert session == null || session.assertIfNotActive();
    checkClosed();

    content.add(nextResult);
  }

  public int size() {
    assert session == null || session.assertIfNotActive();
    checkClosed();

    return content.size();
  }

  @Nonnull
  public InternalResultSet copy(@Nullable DatabaseSessionInternal session) {
    assert this.session == null || this.session.assertIfNotActive();
    assert session == null || session.assertIfNotActive();

    return new InternalResultSet(session, this.content);
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

  @Nullable
  @Override
  public ResultSet trySplit() {
    return null;
  }

  @Override
  public long estimateSize() {
    return content.size() - next;
  }

  @Override
  public int characteristics() {
    return ORDERED | SIZED;
  }

  private void checkClosed() {
    if (closed) {
      throw new IllegalStateException("ResultSet is closed and can not be used");
    }
  }
}
