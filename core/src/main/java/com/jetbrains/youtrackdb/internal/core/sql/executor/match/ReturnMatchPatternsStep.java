package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Projection step for `RETURN $patterns` — strips auto-generated (default) aliases from
 * MATCH result rows, leaving only the user-defined aliases.
 * <p>
 * During pattern construction, the planner assigns auto-generated aliases (prefixed with
 * {@link MatchExecutionPlanner#DEFAULT_ALIAS_PREFIX}) to pattern nodes the user did not
 * name. These internal aliases are needed for the traversal machinery but should not
 * appear in the final query output. This step removes them.
 * <p>
 * ### Difference from `$paths`
 * <p>
 * - `$paths` ({@link ReturnMatchPathsStep}): returns **all** aliases, including
 *   auto-generated ones.
 * - `$patterns` (this step): returns only **user-defined** aliases.
 *
 * @see ReturnMatchPathsStep
 * @see ReturnMatchElementsStep
 */
public class ReturnMatchPatternsStep extends AbstractExecutionStep {

  public ReturnMatchPatternsStep(CommandContext context, boolean profilingEnabled) {
    super(context, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var upstream = prev.start(ctx);
    return upstream.map(ReturnMatchPatternsStep::mapResult);
  }

  /**
   * Removes all properties whose name starts with the default alias prefix, leaving
   * only user-defined aliases in the result row.
   */
  private static Result mapResult(Result next, CommandContext ctx) {
    next.getPropertyNames().stream()
        .filter(s -> s.startsWith(MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX))
        .forEach(((ResultInternal) next)::removeProperty);
    return next;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ RETURN $patterns";
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ReturnMatchPatternsStep(ctx, profilingEnabled);
  }
}
