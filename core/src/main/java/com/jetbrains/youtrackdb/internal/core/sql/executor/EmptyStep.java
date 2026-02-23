package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/** Execution step that produces an empty result stream. */
public class EmptyStep extends AbstractExecutionStep {

  public EmptyStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return ExecutionStream.empty();
  }

  @Override
  public boolean canBeCached() {
    return false;
    // DON'T TOUCH!
    // This step is there most of the cases because the query was early optimized based on DATA, eg.
    // an empty collection,
    // so this execution plan cannot be cached!!!
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new EmptyStep(ctx, profilingEnabled);
  }
}
