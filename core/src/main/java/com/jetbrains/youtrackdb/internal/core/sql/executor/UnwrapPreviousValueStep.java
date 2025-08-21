package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * for UPDATE, unwraps the current result set to return the previous value
 */
public class UnwrapPreviousValueStep extends AbstractExecutionStep {

  public UnwrapPreviousValueStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var upstream = prev.start(ctx);
    return upstream.map(UnwrapPreviousValueStep::mapResult);
  }

  private static Result mapResult(Result result, CommandContext ctx) {
    if (result instanceof UpdatableResult) {
      result = ((UpdatableResult) result).previousValue;
      if (result == null) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Invalid status of record: no previous value available");
      }
      return result;
    } else {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Invalid status of record: no previous value available");
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = ExecutionStepInternal.getIndent(depth, indent) + "+ UNWRAP PREVIOUS VALUE";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new UnwrapPreviousValueStep(ctx, profilingEnabled);
  }
}
