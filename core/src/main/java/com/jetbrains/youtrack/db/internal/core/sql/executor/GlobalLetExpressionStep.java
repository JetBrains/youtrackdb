package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;

/**
 *
 */
public class GlobalLetExpressionStep extends AbstractExecutionStep {

  private final SQLIdentifier varname;
  private final SQLExpression expression;

  private boolean executed = false;

  public GlobalLetExpressionStep(
      SQLIdentifier varName, SQLExpression expression, CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.varname = varName;
    this.expression = expression;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    calculate(ctx);
    return ExecutionStream.empty();
  }

  private void calculate(CommandContext ctx) {
    if (executed) {
      return;
    }
    var value = expression.execute((Result) null, ctx);
    ctx.setVariable(varname.getStringValue(), value);
    executed = true;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ LET (once)\n" + spaces + "  " + varname + " = " + expression;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new GlobalLetExpressionStep(varname.copy(), expression.copy(), ctx, profilingEnabled);
  }

  @Override
  public boolean canBeCached() {
    return true;
  }
}
