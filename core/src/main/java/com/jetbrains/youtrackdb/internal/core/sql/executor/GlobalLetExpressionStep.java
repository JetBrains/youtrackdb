package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;

/**
 * Global LET step that evaluates a simple expression <b>once</b> and stores the
 * result as a context variable for later use by other steps.
 *
 * <pre>
 *  SQL:  LET $threshold = 100
 *        SELECT FROM Product WHERE price &gt; $threshold
 *
 *  This step evaluates "100" once and stores it as ctx.variable("threshold").
 * </pre>
 *
 * <p>The {@code executed} flag ensures the expression is not re-evaluated if the
 * step's {@code start()} is called multiple times during plan copying or retries.
 *
 * @see SelectExecutionPlanner#handleGlobalLet
 */
public class GlobalLetExpressionStep extends AbstractExecutionStep {

  /** The variable name (without '$' prefix). */
  private final SQLIdentifier varname;

  /** The expression to evaluate (e.g. a constant, arithmetic, function call). */
  private final SQLExpression expression;

  /** Guard to ensure the expression is evaluated at most once. */
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
    // Drain the previous step to trigger its side effects (e.g. another global LET storing
    // its value).
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
    // Evaluate without a "current record" -- global LET expressions are constants or
    // reference context variables.
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

  /** Cacheable: expression AST is deep-copied per execution via {@link #copy}. */
  @Override
  public boolean canBeCached() {
    return true;
  }
}
