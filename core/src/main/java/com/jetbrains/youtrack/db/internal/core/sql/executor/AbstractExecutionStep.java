package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.text.DecimalFormat;
import javax.annotation.Nullable;

/**
 *
 */
public abstract class AbstractExecutionStep implements ExecutionStepInternal {

  final CommandContext ctx;
  @Nullable
  protected ExecutionStepInternal prev = null;
  @Nullable
  ExecutionStepInternal next = null;
  protected boolean profilingEnabled;

  public AbstractExecutionStep(CommandContext ctx, boolean profilingEnabled) {
    this.ctx = ctx;
    this.profilingEnabled = profilingEnabled;
  }

  @Override
  public void setPrevious(ExecutionStepInternal step) {
    this.prev = step;
  }

  @Override
  public void setNext(@Nullable ExecutionStepInternal step) {
    this.next = step;
  }

  @Override
  public void sendTimeout() {
    if (prev != null) {
      prev.sendTimeout();
    }
  }

  private boolean alreadyClosed = false;

  @Override
  public void close() {
    if (alreadyClosed) {
      return;
    }
    alreadyClosed = true;

    if (prev != null) {
      prev.close();
    }
  }

  public boolean isProfilingEnabled() {
    return profilingEnabled;
  }

  public void setProfilingEnabled(boolean profilingEnabled) {
    this.profilingEnabled = profilingEnabled;
  }

  public ExecutionStream start(CommandContext ctx) throws TimeoutException {
    if (profilingEnabled) {
      ctx.startProfiling(this);
      try {
        return internalStart(ctx).profile(this);
      } finally {
        ctx.endProfiling(this);
      }
    } else {
      return internalStart(ctx);
    }
  }

  protected abstract ExecutionStream internalStart(CommandContext ctx) throws TimeoutException;

  @Override
  public long getCost() {
    var stats = this.ctx.getStats(this);
    if (stats != null) {
      return stats.getCost();
    } else {
      return ExecutionStepInternal.super.getCost();
    }
  }

  protected String getCostFormatted() {
    return new DecimalFormat().format(getCost() / 1000) + "μs";
  }
}
