package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.QueryLifecycleListener;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LocalResultSet implements ResultSet {

  private static final AtomicLong counter = new AtomicLong(0);

  private ExecutionStream stream = null;
  private final InternalExecutionPlan executionPlan;
  @Nullable
  private DatabaseSessionEmbedded session;

  private boolean closed = false;

  // Lifecycle tracking (merged from LocalResultSetLifecycleDecorator)
  private List<QueryLifecycleListener> lifecycleListeners;
  private final String queryId;

  public LocalResultSet(DatabaseSessionEmbedded session, InternalExecutionPlan executionPlan) {
    this.executionPlan = executionPlan;
    this.session = session;
    this.queryId = System.currentTimeMillis() + "_" + counter.incrementAndGet();

    start();
  }

  private void start() {
    assert session == null || session.assertIfNotActive();
    checkClosed();

    stream = executionPlan.start();
  }

  public void addLifecycleListener(QueryLifecycleListener listener) {
    if (lifecycleListeners == null) {
      lifecycleListeners = new ArrayList<>(2);
    }
    lifecycleListeners.add(listener);
  }

  public String getQueryId() {
    return queryId;
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

    // Notify lifecycle listeners (merged from LocalResultSetLifecycleDecorator)
    if (lifecycleListeners != null) {
      lifecycleListeners.forEach(x -> x.queryClosed(this.queryId));
      lifecycleListeners.clear();
    }
  }

  @Override
  public @Nullable ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();

    return executionPlan;
  }

  @Nullable
  @Override
  public DatabaseSessionEmbedded getBoundToSession() {
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
