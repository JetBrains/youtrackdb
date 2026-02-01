package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 *
 */
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

    var db = ctx.getDatabaseSession();
    if (result.isStatefulEdge()) {
      if (result.isStatefulEdge()) {
        ((ResultInternal) result).setIdentifiable(result.asStatefulEdge());
      } else {
        result = new ResultInternal(db, (EdgeInternal) result.asEdge());
      }
    } else {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Current entity is not a stateful edge : " + result);
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
