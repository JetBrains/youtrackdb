package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Source step that produces zero records (an empty stream).
 *
 * <p>Used when the planner determines at plan-time that no results are possible,
 * e.g. when the FROM target resolves to {@code null} or an empty collection.
 *
 * <p><b>Cannot be cached</b> because the empty-ness decision is typically based
 * on current data state (e.g. an empty collection that may later contain records).
 *
 * @see SelectExecutionPlanner#handleInputParamAsTarget
 */
public class EmptyStep extends AbstractExecutionStep {

  /**
   * @param ctx              the query context
   * @param profilingEnabled true to enable the profiling of the execution (for SQL PROFILE)
   */
  public EmptyStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before returning the empty stream.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return ExecutionStream.empty();
  }

  /**
   * Returns {@code false} -- this step must NOT be cached.
   *
   * <p>An EmptyStep is typically inserted because the planner detected an empty data
   * source at planning time (e.g. an empty collection or a null input parameter).
   * If the plan were cached, a later execution with a non-empty source would
   * incorrectly return zero rows. Always re-plan to check the current data state.
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new EmptyStep(ctx, profilingEnabled);
  }
}
