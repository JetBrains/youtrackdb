package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSimpleExecStatement;

/** Execution step that evaluates and returns the result of a RETURN statement. */
public class ReturnStep extends AbstractExecutionStep {

  private final SQLSimpleExecStatement statement;

  public ReturnStep(SQLSimpleExecStatement statement, CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.statement = statement;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    return statement.executeSimple(ctx);
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ReturnStep((SQLSimpleExecStatement) statement.copy(), ctx, profilingEnabled);
  }
}
