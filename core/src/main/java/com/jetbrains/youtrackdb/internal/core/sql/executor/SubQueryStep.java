package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Source step that executes a subquery as the FROM target of the outer SELECT.
 *
 * <pre>
 *  SQL:   SELECT * FROM (SELECT name, age FROM Person WHERE age &gt; 30)
 *
 *  Pipeline:
 *    SubQueryStep(inner plan) -&gt; [outer FilterStep] -&gt; [outer ProjectionStep] -&gt; ...
 * </pre>
 *
 * <p>The step starts the sub-plan's execution, sets each result as the
 * {@code $current} context variable, and passes it downstream.
 *
 * @see SelectExecutionPlanner#handleSubqueryAsTarget
 */
public class SubQueryStep extends AbstractExecutionStep {

  /** The pre-built execution plan for the subquery. */
  private final InternalExecutionPlan subExecutionPlan;

  /** True if the subquery shares the parent's context (no child context was created). */
  private final boolean sameContextAsParent;

  /**
   * Creates a step that executes a subquery as the FROM target.
   *
   * @param subExecutionPlan the pre-built execution plan for the subquery
   * @param ctx              the context of the outer (parent) execution plan
   * @param subCtx           the context of the subquery execution plan; when
   *                         {@code ctx == subCtx}, the subquery shares the parent's
   *                         context (enabling plan caching)
   * @param profilingEnabled true to enable profiling instrumentation
   */
  public SubQueryStep(
      InternalExecutionPlan subExecutionPlan,
      CommandContext ctx,
      CommandContext subCtx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);

    this.subExecutionPlan = subExecutionPlan;
    this.sameContextAsParent = (ctx == subCtx);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before executing subquery.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var parentRs = subExecutionPlan.start();
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
    builder.append(subExecutionPlan.prettyPrint(depth + 1, indent));
    return builder.toString();
  }

  /**
   * Cacheable only if the subquery shares the parent context (otherwise the child
   * context would be stale) AND the sub-plan itself is cacheable.
   */
  @Override
  public boolean canBeCached() {
    return sameContextAsParent && subExecutionPlan.canBeCached();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new SubQueryStep(subExecutionPlan.copy(ctx), ctx, ctx, profilingEnabled);
  }
}
