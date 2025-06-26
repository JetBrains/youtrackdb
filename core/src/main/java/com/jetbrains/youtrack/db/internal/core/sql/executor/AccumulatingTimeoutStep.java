package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.TimeoutResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLTimeout;

/**
 *
 */
public class AccumulatingTimeoutStep extends AbstractExecutionStep {

  private final SQLTimeout timeout;

  public AccumulatingTimeoutStep(SQLTimeout timeout, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.timeout = timeout;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    final var internal = prev.start(ctx);
    return new TimeoutResultSet(internal, this.timeout.getVal().longValue(), this::fail);
  }

  private void fail() {
    if (SQLTimeout.RETURN.equals(this.timeout.getFailureStrategy())) {
      // do nothing
    } else {
      sendTimeout();
      throw new TimeoutException("Timeout expired");
    }
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new AccumulatingTimeoutStep(timeout.copy(), ctx, profilingEnabled);
  }

  @Override
  public void reset() {
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ TIMEOUT ("
        + timeout.getVal().toString()
        + "ms)";
  }
}
