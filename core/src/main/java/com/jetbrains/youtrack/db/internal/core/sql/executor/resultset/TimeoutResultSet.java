package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.concurrent.atomic.AtomicLong;

public final class TimeoutResultSet implements ExecutionStream {

  private final AtomicLong totalTime = new AtomicLong(0);
  private final TimedOut timedout;
  private final ExecutionStream internal;
  private final long timeoutMillis;
  private boolean timedOut = false;

  public interface TimedOut {

    void timeout();
  }

  public TimeoutResultSet(ExecutionStream internal, long timeoutMillis, TimedOut timedout) {
    this.internal = internal;
    this.timedout = timedout;
    this.timeoutMillis = timeoutMillis;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    if (timedOut) {
      return false;
    }
    var begin = System.nanoTime();

    try {
      return internal.hasNext(ctx);
    } finally {
      totalTime.addAndGet(System.nanoTime() - begin);
    }
  }

  @Override
  public Result next(CommandContext ctx) {
    if (totalTime.get() / 1_000_000 > timeoutMillis) {
      fail();
      if (timedOut) {
        return new ResultInternal(ctx.getDatabaseSession());
      }
    }
    var begin = System.nanoTime();
    try {
      return internal.next(ctx);
    } finally {
      totalTime.addAndGet(System.nanoTime() - begin);
    }
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
