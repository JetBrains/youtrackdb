package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.ExecutionThreadLocal;
import com.jetbrains.youtrack.db.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrack.db.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrack.db.internal.core.sql.executor.EmptyStep;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ScriptExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;

public class WhileStep extends AbstractExecutionStep {

  private final SQLBooleanExpression condition;
  private final List<SQLStatement> statements;

  public WhileStep(
      SQLBooleanExpression condition,
      List<SQLStatement> statements,
      CommandContext ctx,
      boolean enableProfiling) {
    super(ctx, enableProfiling);
    this.condition = condition;
    this.statements = statements;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var session = ctx.getDatabaseSession();
    while (condition.evaluate(new ResultInternal(session), ctx)) {
      if (ExecutionThreadLocal.isInterruptCurrentOperation()) {
        throw new CommandInterruptedException(session.getDatabaseName(),
            "The command has been interrupted");
      }

      var plan = initPlan(ctx);
      var result = plan.executeFull();
      if (result != null) {
        return result.start(ctx);
      }
    }
    return new EmptyStep(ctx, false).start(ctx);
  }

  public ScriptExecutionPlan initPlan(CommandContext ctx) {
    var subCtx1 = new BasicCommandContext();
    subCtx1.setParent(ctx);
    var plan = new ScriptExecutionPlan(subCtx1);
    for (var stm : statements) {
      if (stm.originalStatement == null) {
        stm.originalStatement = stm.toString();
      }
      InternalExecutionPlan subPlan;
      if (stm.originalStatement.contains("?")) {
        // cannot cache execution plans with positional parameters inside scripts
        subPlan = stm.createExecutionPlanNoCache(subCtx1, profilingEnabled);
      } else {
        subPlan = stm.createExecutionPlan(subCtx1, profilingEnabled);
      }
      plan.chain(subPlan, profilingEnabled);
    }
    return plan;
  }

  public boolean containsReturn() {
    for (var stm : this.statements) {
      if (stm instanceof SQLReturnStatement) {
        return true;
      }
      if (stm instanceof SQLForEachBlock && ((SQLForEachBlock) stm).containsReturn()) {
        return true;
      }
      if (stm instanceof SQLIfStatement && ((SQLIfStatement) stm).containsReturn()) {
        return true;
      }
      if (stm instanceof SQLWhileBlock && ((SQLWhileBlock) stm).containsReturn()) {
        return true;
      }
    }
    return false;
  }
}
