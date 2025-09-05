package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

public class CastToVertexStep extends AbstractExecutionStep {

  public CastToVertexStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var upstream = prev.start(ctx);
    return upstream.map(CastToVertexStep::mapResult);
  }

  private static Result mapResult(Result result, CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    if (result.isVertex()) {
      if (result instanceof ResultInternal) {
        ((ResultInternal) result).setIdentifiable(result.asVertex());
      } else {
        result = new ResultInternal(db, result.asVertex());
      }
    } else {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Current entity is not a vertex: " + result);
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = ExecutionStepInternal.getIndent(depth, indent) + "+ CAST TO VERTEX";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CastToVertexStep(ctx, profilingEnabled);
  }
}
