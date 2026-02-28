package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import javax.annotation.Nullable;

/**
 * Intermediate step that converts {@link UpdatableResult} instances back to immutable
 * {@link ResultInternal} instances.
 *
 * <p>This is the inverse of {@code ConvertToUpdatableResultStep}. It is used after
 * DML operations (UPDATE, DELETE) to convert the mutable result rows back into
 * the standard read-only format expected by downstream projection and filtering steps.
 *
 * <p>Conversion rules:
 * <ul>
 *   <li>{@link UpdatableResult} with an entity -- unwrapped to a new
 *       {@link ResultInternal} wrapping the entity</li>
 *   <li>{@link UpdatableResult} without an entity -- passed through as-is</li>
 *   <li>Any other {@link com.jetbrains.youtrackdb.internal.core.query.Result} type --
 *       silently discarded (filtered out) because it was not produced by the
 *       DML step and should not appear in the output</li>
 * </ul>
 */
public class ConvertToResultInternalStep extends AbstractExecutionStep {

  public ConvertToResultInternalStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new IllegalStateException("ConvertToResultInternalStep requires a previous step");
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

  /** Cacheable: this step is stateless -- it only converts result types per record. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ConvertToResultInternalStep(ctx, profilingEnabled);
  }
}
