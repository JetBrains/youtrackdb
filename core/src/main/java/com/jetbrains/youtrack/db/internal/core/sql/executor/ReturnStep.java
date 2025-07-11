package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSimpleExecStatement;

/**
 *
 */
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
