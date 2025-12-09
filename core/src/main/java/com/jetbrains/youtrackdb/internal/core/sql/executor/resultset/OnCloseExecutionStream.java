package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;

public class OnCloseExecutionStream implements ExecutionStream {

  private final ExecutionStream source;
  private final OnClose onClose;

  public OnCloseExecutionStream(ExecutionStream source, OnClose onClose) {
    this.source = source;
    this.onClose = onClose;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    return source.hasNext(ctx);
  }

  @Override
  public Result next(CommandContext ctx) {
    return source.next(ctx);
  }

  @Override
  public void close(CommandContext ctx) {
    onClose.close(ctx);
    source.close(ctx);
  }
}
