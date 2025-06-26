package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class IteratorResultSet implements ResultSet {

  protected final Iterator<?> iterator;
  @Nullable
  protected DatabaseSessionInternal session;

  private boolean closed = false;

  public IteratorResultSet(@Nullable DatabaseSessionInternal session, Iterator<?> iter) {
    this.iterator = iter;
    this.session = session;
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
    if (closed) {
      return false;
    }

    return iterator.hasNext();
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();

    if (!iterator.hasNext()) {
      throw new NoSuchElementException();
    }

    var val = iterator.next();
    return ResultInternal.toResult(val, session, "value");
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
  @Nullable
  public ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();
    return null;
  }

  @Override
  public DatabaseSession getBoundToSession() {
    return session;
  }

  @Override
  public boolean tryAdvance(Consumer<? super Result> action) {
    assert session == null || session.assertIfNotActive();
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
