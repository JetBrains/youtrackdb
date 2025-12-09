package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.ExecutionThreadLocal;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.query.Result;

public class InterruptResultSet implements ExecutionStream {

  private final ExecutionStream source;

  public InterruptResultSet(ExecutionStream source) {
    this.source = source;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    if (ExecutionThreadLocal.isInterruptCurrentOperation()) {
      throw new CommandInterruptedException(ctx.getDatabaseSession(),
          "The command has been interrupted");
    }
    return source.hasNext(ctx);
  }

  @Override
  public Result next(CommandContext ctx) {
    if (ExecutionThreadLocal.isInterruptCurrentOperation()) {
      throw new CommandInterruptedException(ctx.getDatabaseSession(),
          "The command has been interrupted");
    }
    return source.next(ctx);
  }

  @Override
  public void close(CommandContext ctx) {
    source.close(ctx);
  }
}
