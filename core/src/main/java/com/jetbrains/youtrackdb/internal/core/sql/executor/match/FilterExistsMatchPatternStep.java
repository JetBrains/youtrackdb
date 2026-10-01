package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps each upstream MATCH row once when its detached positive pattern yields at least one path.
 * The sub-plan starts from a copy of that row, so it can bind aliases without changing the row
 * passed downstream. Calling {@code hasNext} stops at the first match instead of counting paths.
 */
public class FilterExistsMatchPatternStep extends AbstractExecutionStep {

  private final List<AbstractExecutionStep> subSteps;

  public FilterExistsMatchPatternStep(
      List<AbstractExecutionStep> steps, CommandContext ctx, boolean enableProfiling) {
    super(ctx, enableProfiling);
    assert MatchAssertions.checkNotNull(steps, "sub-steps list");
    this.subSteps = steps;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }
    return prev.start(ctx).filter(this::filterMap);
  }

  @Nullable private Result filterMap(Result row, CommandContext ctx) {
    return DetachedMatchPatternProbe.matches(row, subSteps, ctx, profilingEnabled) ? row : null;
  }

  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    //noinspection unchecked,rawtypes
    return (List) subSteps;
  }

  @Override
  public boolean canBeCached() {
    return subSteps.stream().allMatch(ExecutionStepInternal::canBeCached);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder(spaces).append("+ EXISTS (\n");
    subSteps.forEach(step -> result.append(step.prettyPrint(depth + 1, indent)).append("\n"));
    return result.append(spaces).append("  )").toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    var copiedSteps = subSteps.stream().map(step -> (AbstractExecutionStep) step.copy(ctx))
        .toList();
    return new FilterExistsMatchPatternStep(copiedSteps, ctx, profilingEnabled);
  }

}
