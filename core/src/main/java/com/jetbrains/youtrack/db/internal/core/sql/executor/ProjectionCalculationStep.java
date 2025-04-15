package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLProjection;

/**
 *
 */
public class ProjectionCalculationStep extends AbstractExecutionStep {

  protected final SQLProjection projection;

  public ProjectionCalculationStep(
      SQLProjection projection, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.projection = projection;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("Cannot calculate projections without a previous source");
    }

    var parentRs = prev.start(ctx);
    return parentRs.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    var oldCurrent = ctx.getVariable("$current");
    ctx.setVariable("$current", result);
    var newResult = calculateProjections(ctx, result);
    ctx.setVariable("$current", oldCurrent);
    return newResult;
  }

  private Result calculateProjections(CommandContext ctx, Result next) {
    return this.projection.calculateSingle(ctx, next);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);

    var result = spaces + "+ CALCULATE PROJECTIONS";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    result += ("\n" + spaces + "  " + projection.toString());
    return result;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ProjectionCalculationStep(projection.copy(), ctx, profilingEnabled);
  }
}
