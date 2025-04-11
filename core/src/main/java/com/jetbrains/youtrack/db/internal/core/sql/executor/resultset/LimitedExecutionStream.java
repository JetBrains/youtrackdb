package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.api.query.Result;

public class LimitedExecutionStream implements ExecutionStream {

  private final ExecutionStream upstream;
  private final long limit;
  private long count = 0;

  public LimitedExecutionStream(ExecutionStream upstream, long limit) {
    this.upstream = upstream;
    this.limit = limit;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    if (count >= limit) {
      return false;
    } else {
      return upstream.hasNext(ctx);
    }
  }

  @Override
  public Result next(CommandContext ctx) {
    if (count >= limit) {
      throw new IllegalStateException();
    } else {
      var read = upstream.next(ctx);
      this.count += 1;
      return read;
    }
  }

  @Override
  public void close(CommandContext ctx) {
    upstream.close(ctx);
  }
}
