package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;

public class SingletonExecutionStream implements ExecutionStream {

  private boolean executed = false;
  private final Result result;

  public SingletonExecutionStream(Result result) {
    this.result = result;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    return !executed;
  }

  @Override
  public Result next(CommandContext ctx) {
    if (executed) {
      throw new IllegalStateException();
    }
    executed = true;
    return result;
  }

  @Override
  public void close(CommandContext ctx) {
  }
}
