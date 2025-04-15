package com.jetbrains.youtrack.db.internal.core.sql.executor.resultset;

import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;

public class CostMeasureExecutionStream implements ExecutionStream {

  private final ExecutionStream set;
  private final ExecutionStep step;
  private long cost;

  public CostMeasureExecutionStream(ExecutionStream set, ExecutionStep step) {
    this.set = set;
    this.cost = 0;
    this.step = step;
  }

  @Override
  public boolean hasNext(CommandContext ctx) {
    var begin = System.nanoTime();
    ctx.startProfiling(this.step);
    try {
      return set.hasNext(ctx);
    } finally {
      ctx.endProfiling(this.step);
      cost += (System.nanoTime() - begin);
    }
  }

  @Override
  public Result next(CommandContext ctx) {
    var begin = System.nanoTime();
    ctx.startProfiling(this.step);
    try {
      return set.next(ctx);
    } finally {
      ctx.endProfiling(this.step);
      cost += (System.nanoTime() - begin);
    }
  }

  @Override
  public void close(CommandContext ctx) {
    set.close(ctx);
  }

  public long getCost() {
    return cost;
  }
}
