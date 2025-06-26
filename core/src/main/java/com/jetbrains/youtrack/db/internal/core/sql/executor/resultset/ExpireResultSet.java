package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;

public final class ExpireResultSet implements ExecutionStream {

  private final TimedOut timedout;
  private final ExecutionStream internal;
  private boolean timedOut = false;
  private final long expiryTime;

  public interface TimedOut {

    void timeout();
  }

  public ExpireResultSet(ExecutionStream internal, long timeoutMillis, TimedOut timedout) {
    this.internal = internal;
    this.timedout = timedout;
    this.expiryTime = System.currentTimeMillis() + timeoutMillis;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    if (System.currentTimeMillis() > expiryTime) {
      fail();
    }
    if (timedOut) {
      return false;
    }
    return internal.hasNext(ctx);
  }

  @Override
  public Result next(CommandContext ctx) {
    if (System.currentTimeMillis() > expiryTime) {
      fail();
      if (timedOut) {
        return new ResultInternal(ctx.getDatabaseSession());
      }
    }
    return internal.next(ctx);
  }

  @Override
  public void close(CommandContext ctx) {
    internal.close(ctx);
  }

  private void fail() {
    this.timedOut = true;
    this.timedout.timeout();
  }
}
