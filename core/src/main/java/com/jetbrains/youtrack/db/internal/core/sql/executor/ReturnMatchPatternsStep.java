package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;

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
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result next, CommandContext ctx) {
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
}
