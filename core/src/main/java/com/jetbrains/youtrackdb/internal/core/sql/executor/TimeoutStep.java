package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExpireResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLTimeout;

/**
 *
 */
public class TimeoutStep extends AbstractExecutionStep {

  private final SQLTimeout timeout;

  public TimeoutStep(SQLTimeout timeout, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.timeout = timeout;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    return new ExpireResultSet(prev.start(ctx), timeout.getVal().longValue(), this::fail);
  }

  private void fail() {
    sendTimeout();
    if (SQLTimeout.RETURN.equals(this.timeout.getFailureStrategy())) {
    } else {
      throw new TimeoutException("Timeout expired");
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ TIMEOUT ("
        + timeout.getVal().toString()
        + " millis)";
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new TimeoutStep(this.timeout.copy(), ctx, profilingEnabled);
  }
}
