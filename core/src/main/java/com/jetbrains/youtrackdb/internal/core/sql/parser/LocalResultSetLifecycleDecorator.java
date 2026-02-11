package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.QueryLifecycleListener;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class LocalResultSetLifecycleDecorator implements ResultSet {

  private static final AtomicLong counter = new AtomicLong(0);

  private final ResultSet underlyingResultSet;
  private final List<QueryLifecycleListener> lifecycleListeners = new ArrayList<>();
  private final String queryId;

  public LocalResultSetLifecycleDecorator(ResultSet underlyingResultSet) {
    this.underlyingResultSet = underlyingResultSet;
    queryId = System.currentTimeMillis() + "_" + counter.incrementAndGet();
  }

  public void addLifecycleListener(QueryLifecycleListener queryLifecycleListener) {
    this.lifecycleListeners.add(queryLifecycleListener);
  }

  @Override
  public boolean hasNext() {
    return underlyingResultSet.hasNext();
  }

  @Override
  public Result next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    return underlyingResultSet.next();
  }

  @Override
  public void close() {
    underlyingResultSet.close();
    this.lifecycleListeners.forEach(x -> x.queryClosed(this.queryId));
    this.lifecycleListeners.clear();
  }

  @Override
  public DatabaseSessionEmbedded getBoundToSession() {
    return underlyingResultSet.getBoundToSession();
  }

  @Override
  public @Nullable ExecutionPlan getExecutionPlan() {
    return underlyingResultSet.getExecutionPlan();
  }

  public String getQueryId() {
    return queryId;
  }

  public boolean isDetached() {
    return underlyingResultSet instanceof InternalResultSet;
  }

  public ResultSet getUnderlying() {
    return underlyingResultSet;
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
    var result = underlyingResultSet.trySplit();
    if (result == null) {
      return null;
    }

    return new LocalResultSetLifecycleDecorator((ResultSet) result);
  }

  @Override
  public long estimateSize() {
    return underlyingResultSet.estimateSize();
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
    return underlyingResultSet.isClosed();
  }
}
