package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 *
 */
public class ReturnMatchPathsStep extends AbstractExecutionStep {

  public ReturnMatchPathsStep(CommandContext context, boolean profilingEnabled) {
    super(context, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    return prev.start(ctx);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ RETURN $paths";
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ReturnMatchPathsStep(ctx, profilingEnabled);
  }
}
