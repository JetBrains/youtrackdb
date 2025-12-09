package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;

public final class FlatMapExecutionStream implements ExecutionStream {

  private final ExecutionStream base;
  private final MapExecutionStream map;
  private ExecutionStream currentResultSet;

  public FlatMapExecutionStream(ExecutionStream base, MapExecutionStream map) {
    this.base = base;
    this.map = map;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    while (currentResultSet == null || !currentResultSet.hasNext(ctx)) {
      if (currentResultSet != null) {
        currentResultSet.close(ctx);
      }
      if (!base.hasNext(ctx)) {
        return false;
      }
      currentResultSet = map.flatMap(base.next(ctx), ctx);
    }
    return true;
  }

  @Override
  public Result next(CommandContext ctx) {
    if (!hasNext(ctx)) {
      throw new IllegalStateException();
    }
    return currentResultSet.next(ctx);
  }

  @Override
  public void close(CommandContext ctx) {
    if (currentResultSet != null) {
      currentResultSet.close(ctx);
    }
    base.close(ctx);
  }
}
