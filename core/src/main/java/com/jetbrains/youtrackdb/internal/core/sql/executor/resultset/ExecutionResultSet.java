package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ExecutionResultSet implements ResultSet {

  private final ExecutionStream stream;
  private final CommandContext context;
  private final ExecutionPlan plan;
  @Nullable
  private DatabaseSessionEmbedded session;

  private boolean closed = false;

  public ExecutionResultSet(
      ExecutionStream stream, CommandContext context, ExecutionPlan plan) {
    super();
    this.stream = stream;
    this.context = context;
    this.session = context.getDatabaseSession();
    this.plan = plan;
  }

  @Override
  public boolean hasNext() {
    assert session == null || session.assertIfNotActive();
    if (closed) {
      return false;
    }

    return stream.hasNext(context);
  }

  @Override
  public Result next() {
    assert session == null || session.assertIfNotActive();

    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    return stream.next(context);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }

    assert session == null || session.assertIfNotActive();
    stream.close(context);

    this.session = null;
    this.closed = true;
  }

  @Override
  public @Nullable ExecutionPlan getExecutionPlan() {
    assert session == null || session.assertIfNotActive();
    return plan;
  }

  @Nullable
  @Override
  public DatabaseSessionEmbedded getBoundToSession() {
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
