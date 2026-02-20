package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLProjectionItem;

/**
 * Per-record LET step that evaluates an expression for each row flowing through
 * the pipeline and attaches the result as metadata on the record.
 *
 * <pre>
 *  SQL:  SELECT *, $fullName FROM Person
 *        LET $fullName = firstName + ' ' + lastName
 *
 *  For each record:
 *    1. Evaluate expression(firstName + ' ' + lastName) against the record
 *    2. Store result as record.metadata("fullName")
 *    3. Pass the record downstream (now carrying the $fullName metadata)
 * </pre>
 *
 * <p>Unlike {@link GlobalLetExpressionStep}, this step executes once per record
 * and the result may differ for each row.
 *
 * @see SelectExecutionPlanner#handleLet
 * @see GlobalLetExpressionStep
 */
public class LetExpressionStep extends AbstractExecutionStep {

  /** The variable name to store per-record values under. */
  private SQLIdentifier varname;

  /** The expression to evaluate against each record. */
  private SQLExpression expression;

  public LetExpressionStep(
      SQLIdentifier varName, SQLExpression expression, CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.varname = varName;
    this.expression = expression;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot execute a local LET on a query without a target");
    }

    return prev.start(ctx).map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    var varName = varname.getStringValue();
    var value = expression.execute(result, ctx);
    // Only set per-record metadata if no global variable with the same name exists.
    // Global LETs (evaluated once before the fetch) take precedence over per-record LETs.
    // Without this guard, a per-record LET with the same name would shadow the global
    // value, which would be incorrect because global LETs are expected to be immutable
    // throughout the query execution.
    if (ctx.getVariable(varName) == null) {
      ((ResultInternal) result)
          .setMetadata(varname.getStringValue(), SQLProjectionItem.convert(value, ctx));
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ LET (for each record)\n" + spaces + "  " + varname + " = " + expression;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    if (varname != null) {
      result.setProperty("varname", varname.serialize(session));
    }
    if (expression != null) {
      result.setProperty("expression", expression.serialize(session));
    }
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      if (fromResult.getProperty("varname") != null) {
        varname = SQLIdentifier.deserialize(fromResult.getProperty("varname"));
      }
      if (fromResult.getProperty("expression") != null) {
        expression = new SQLExpression(-1);
        expression.deserialize(fromResult.getProperty("expression"));
      }
      reset();
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  /** Cacheable: expression AST is deep-copied per execution via {@link #copy}. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    SQLIdentifier varnameCopy = null;
    if (varname != null) {
      varnameCopy = varname.copy();
    }
    SQLExpression expressionCopy = null;
    if (expression != null) {
      expressionCopy = expression.copy();
    }

    return new LetExpressionStep(varnameCopy, expressionCopy, ctx, profilingEnabled);
  }
}
