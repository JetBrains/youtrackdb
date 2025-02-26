package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;

/**
 *
 */
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
        ((ResultInternal) result).setIdentifiable(result.castToVertex());
      } else {
        result = new ResultInternal(db, result.castToVertex());
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
}
