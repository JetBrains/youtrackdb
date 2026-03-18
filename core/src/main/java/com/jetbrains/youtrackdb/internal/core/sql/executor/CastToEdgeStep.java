package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/** Execution step that casts upstream results to edge records. */
public class CastToEdgeStep extends AbstractExecutionStep {

  public CastToEdgeStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var upstream = prev.start(ctx);
    return upstream.map(CastToEdgeStep::mapResult);
  }

  private static Result mapResult(Result result, CommandContext ctx) {
    if (result.isEdge()) {
      ((ResultInternal) result).setIdentifiable(result.asEdge());
    } else {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Current entity is not an edge: " + result);
    }

    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = ExecutionStepInternal.getIndent(depth, indent) + "+ CAST TO EDGE";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CastToEdgeStep(ctx, profilingEnabled);
  }

  @Override
  public boolean canBeCached() {
    return true;
  }
}
