package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Deletes records coming from upstream steps
 */
public class DeleteStep extends AbstractExecutionStep {

  public DeleteStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var upstream = prev.start(ctx);
    return upstream.map(DeleteStep::mapResult);
  }

  private static Result mapResult(Result result, CommandContext ctx) {
    if (result.isIdentifiable()) {
      ctx.getDatabaseSession().delete(result.asRecord());
    } else {
      throw new DatabaseException("Can not delete non-record result: " + result);
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ DELETE");
    if (profilingEnabled) {
      result.append(" (" + getCostFormatted() + ")");
    }
    return result.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new DeleteStep(ctx, this.profilingEnabled);
  }

  @Override
  public boolean canBeCached() {
    return true;
  }
}
