package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import javax.annotation.Nullable;

/**
 * takes a result set made of OUpdatableRecord instances and transforms it in another result set
 * made of normal ResultInternal instances.
 *
 * <p>This is the opposite of ConvertToUpdatableResultStep
 */
public class ConvertToResultInternalStep extends AbstractExecutionStep {

  public ConvertToResultInternalStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }
    var resultSet = prev.start(ctx);
    return resultSet.filter(ConvertToResultInternalStep::filterMap);
  }

  @Nullable
  private static Result filterMap(Result result, CommandContext ctx) {
    if (result instanceof UpdatableResult) {
      if (result.isEntity()) {
        var entity = result.asEntityOrNull();
        return new ResultInternal(ctx.getDatabaseSession(), entity);
      }
      return result;
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result =
        ExecutionStepInternal.getIndent(depth, indent) + "+ CONVERT TO REGULAR RESULT ITEM";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ConvertToResultInternalStep(ctx, profilingEnabled);
  }
}
