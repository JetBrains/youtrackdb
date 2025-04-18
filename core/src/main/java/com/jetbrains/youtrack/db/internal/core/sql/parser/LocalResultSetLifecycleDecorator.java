package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.QueryLifecycleListener;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class LocalResultSetLifecycleDecorator implements ResultSet {

  private static final AtomicLong counter = new AtomicLong(0);

  private final ResultSet entity;
  private final List<QueryLifecycleListener> lifecycleListeners = new ArrayList<>();
  private final String queryId;

  private boolean hasNextPage;

  public LocalResultSetLifecycleDecorator(ResultSet entity) {
    this.entity = entity;
    queryId = System.currentTimeMillis() + "_" + counter.incrementAndGet();
  }

  public void addLifecycleListener(QueryLifecycleListener queryLifecycleListener) {
    this.lifecycleListeners.add(queryLifecycleListener);
  }

  @Override
  public boolean hasNext() {
    var hasNext = entity.hasNext();
    if (!hasNext) {
      close();
    }
    return hasNext;
  }

  @Override
  public Result next() {
    if (!hasNext()) {
      throw new IllegalStateException();
    }

    return entity.next();
  }

  @Override
  public void close() {
    var session = (DatabaseSessionInternal) entity.getBoundToSession();
    entity.close();
    this.lifecycleListeners.forEach(x -> x.queryClosed(this.queryId));
    this.lifecycleListeners.clear();
    if (session != null) {
      var tx = session.getTransactionInternal();
      //read only transactions are initiated only for queries and only if there is no active transaction
      if (tx.isActive() && tx.isReadOnly()) {
        tx.rollbackInternal();
      }
    }
  }

  @Override
  public DatabaseSession getBoundToSession() {
    return entity.getBoundToSession();
  }

  @Override
  public @Nonnull ExecutionPlan getExecutionPlan() {
    return entity.getExecutionPlan();
  }

  public String getQueryId() {
    return queryId;
  }

  public boolean hasNextPage() {
    return hasNextPage;
  }

  public void setHasNextPage(boolean b) {
    this.hasNextPage = b;
  }

  public boolean isDetached() {
    return entity instanceof InternalResultSet;
  }

  public ResultSet getInternal() {
    return entity;
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
}
