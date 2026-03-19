package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;

/**
 * Source step that streams records from a pre-materialized {@code List<Result>}.
 * Used by {@link MaterializedLetGroupStep} to replace a {@link SubQueryStep}
 * when the inner subquery results have already been computed and cached.
 *
 * <p>Each emitted record is set as {@code $current} on the context, matching
 * the contract of {@link SubQueryStep#mapResult}.
 */
public class ListSourceStep extends AbstractExecutionStep {

  private final List<Result> records;

  public ListSourceStep(
      List<Result> records, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.records = records;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }
    return ExecutionStream.resultIterator(records.iterator()).map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    ctx.setSystemVariable(CommandContext.VAR_CURRENT, result);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var ind = ExecutionStepInternal.getIndent(depth, indent);
    return ind + "+ FETCH FROM MATERIALIZED LIST (" + records.size() + " records)";
  }

  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ListSourceStep(records, ctx, profilingEnabled);
  }
}
