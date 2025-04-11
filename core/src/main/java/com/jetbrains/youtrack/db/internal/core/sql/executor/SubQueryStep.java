package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;

/**
 *
 */
public class SubQueryStep extends AbstractExecutionStep {

  private final InternalExecutionPlan subExecuitonPlan;
  private final boolean sameContextAsParent;

  /**
   * executes a sub-query
   *
   * @param subExecutionPlan the execution plan of the sub-query
   * @param ctx              the context of the current execution plan
   * @param subCtx           the context of the subquery execution plan
   */
  public SubQueryStep(
      InternalExecutionPlan subExecutionPlan,
      CommandContext ctx,
      CommandContext subCtx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);

    this.subExecuitonPlan = subExecutionPlan;
    this.sameContextAsParent = (ctx == subCtx);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var parentRs = subExecuitonPlan.start();
    return parentRs.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    ctx.setVariable("$current", result);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var builder = new StringBuilder();
    var ind = ExecutionStepInternal.getIndent(depth, indent);
    builder.append(ind);
    builder.append("+ FETCH FROM SUBQUERY \n");
    builder.append(subExecuitonPlan.prettyPrint(depth + 1, indent));
    return builder.toString();
  }

  @Override
  public boolean canBeCached() {
    return sameContextAsParent && subExecuitonPlan.canBeCached();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new SubQueryStep(subExecuitonPlan.copy(ctx), ctx, ctx, profilingEnabled);
  }
}
