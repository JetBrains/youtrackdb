package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.common.util.Resettable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class InternalResultSet implements ResultSet, Resettable {

  private List<Result> content = new ArrayList<>();
  private int next = 0;
  protected ExecutionPlan plan;
  @Nullable
  private DatabaseSessionInternal session;

  public InternalResultSet(@Nullable DatabaseSessionInternal session) {
    this.session = session;
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
    return content.size() > next;
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();
    return content.get(next++);
  }

  @Override
  public void close() {
    assert session == null || session.assertIfNotActive();
    this.content.clear();
    this.session = null;
  }

  @Override
  public @Nonnull ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();
    return plan;
  }

  public void setPlan(ExecutionPlan plan) {
    assert session == null || session.assertIfNotActive();
    this.plan = plan;
  }

  public void add(Result nextResult) {
    assert session == null || session.assertIfNotActive();
    content.add(nextResult);
  }

  @Override
  public void reset() {
    assert session == null || session.assertIfNotActive();
    this.next = 0;
  }

  @Override
  public boolean isResetable() {
    return true;
  }

  public int size() {
    assert session == null || session.assertIfNotActive();
    return content.size();
  }

  public InternalResultSet copy() {
    assert session == null || session.assertIfNotActive();
    var result = new InternalResultSet(session);
    result.content = this.content;
    return result;
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

}
