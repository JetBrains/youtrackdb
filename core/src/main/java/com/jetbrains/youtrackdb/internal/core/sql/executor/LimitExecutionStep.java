package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;

public class LimitExecutionStep extends AbstractExecutionStep {
  private final SQLLimit limit;

  public LimitExecutionStep(SQLLimit limit, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.limit = limit;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var limitVal = limit.getValue(ctx);
    if (limitVal == -1) {
      return prev.start(ctx);
    }
    var result = prev.start(ctx);
    return result.limit(limitVal);
  }

  @Override
  public void sendTimeout() {
  }

  @Override
  public void close() {
    if (prev != null) {
      prev.close();
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent) + "+ LIMIT (" + limit.toString() + ")";
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    SQLLimit limitCopy = null;
    if (limit != null) {
      limitCopy = limit.copy();
    }

    return new LimitExecutionStep(limitCopy, ctx, profilingEnabled);
  }
}
