package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 *
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
