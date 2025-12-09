package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;

public final class MultipleExecutionStream implements ExecutionStream {

  private final ExecutionStreamProducer streamsSource;
  private ExecutionStream currentStream;

  public MultipleExecutionStream(ExecutionStreamProducer streamSource) {
    this.streamsSource = streamSource;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    while (currentStream == null || !currentStream.hasNext(ctx)) {
      if (currentStream != null) {
        currentStream.close(ctx);
      }
      if (!streamsSource.hasNext(ctx)) {
        return false;
      }
      currentStream = streamsSource.next(ctx);
    }
    return true;
  }

  @Override
  public Result next(CommandContext ctx) {
    if (!hasNext(ctx)) {
      throw new IllegalStateException();
    }
    return currentStream.next(ctx);
  }

  @Override
  public void close(CommandContext ctx) {
    if (currentStream != null) {
      currentStream.close(ctx);
    }
    streamsSource.close(ctx);
  }
}
