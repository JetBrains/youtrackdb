package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Collection;

/**
 * unwinds a result-set.
 */
public abstract class AbstractUnrollStep extends AbstractExecutionStep {

  public AbstractUnrollStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession().getDatabaseName(),
          "Cannot expand without a target");
    }
    var resultSet = prev.start(ctx);
    return resultSet.flatMap(this::fetchNextResults);
  }

  private ExecutionStream fetchNextResults(Result res, CommandContext ctx) {
    return ExecutionStream.resultIterator(unroll(res, ctx).iterator());
  }

  protected abstract Collection<Result> unroll(final Result result,
      final CommandContext iContext);
}
