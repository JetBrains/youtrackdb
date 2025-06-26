package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;

/**
 *
 */
public class RemoveEmptyOptionalsStep extends AbstractExecutionStep {

  public RemoveEmptyOptionalsStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    for (var s : result.getPropertyNames()) {
      if (OptionalMatchEdgeTraverser.isEmptyOptional(result.getProperty(s))) {
        ((ResultInternal) result).setProperty(s, null);
      }
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ REMOVE EMPTY OPTIONALS";
  }
}
