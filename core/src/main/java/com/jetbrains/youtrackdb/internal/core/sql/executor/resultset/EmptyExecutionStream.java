package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;

public class EmptyExecutionStream implements ExecutionStream {

  protected static final ExecutionStream EMPTY = new EmptyExecutionStream();

  @Override
  public boolean hasNext(CommandContext ctx) {
    return false;
  }

  @Override
  public Result next(CommandContext ctx) {
    throw new IllegalStateException();
  }

  @Override
  public void close(CommandContext ctx) {
  }
}
