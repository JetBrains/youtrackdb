package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLReturnStatement;

/**
 * <p>This step represents the execution plan of an instruciton instide a batch script
 */
public class ScriptLineStep extends AbstractExecutionStep {

  protected final InternalExecutionPlan plan;

  public ScriptLineStep(
      InternalExecutionPlan nextPlan, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.plan = nextPlan;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (plan instanceof InsertExecutionPlan insertPlan) {
      insertPlan.executeInternal();
    } else if (plan instanceof DeleteExecutionPlan deletePlan) {
      deletePlan.executeInternal();
    } else if (plan instanceof UpdateExecutionPlan updatePlan) {
      updatePlan.executeInternal();
    } else if (plan instanceof DDLExecutionPlan ddlPlan) {
      ddlPlan.executeInternal((BasicCommandContext) ctx);
    } else if (plan instanceof SingleOpExecutionPlan singleOpPlan) {
      singleOpPlan.executeInternal((BasicCommandContext) ctx);
    }
    return plan.start();
  }

  public boolean containsReturn() {
    if (plan instanceof ScriptExecutionPlan scriptPlan) {
      return scriptPlan.containsReturn();
    }
    if (plan instanceof SingleOpExecutionPlan singleOpPlan) {
      if (singleOpPlan.statement instanceof SQLReturnStatement) {
        return true;
      }
    }
    if (plan instanceof IfExecutionPlan ifPlan) {
      if (ifPlan.containsReturn()) {
        return true;
      }
    }

    if (plan instanceof ForEachExecutionPlan forEachPlan) {
      return forEachPlan.containsReturn();
    }
    return false;
  }

  public ExecutionStepInternal executeUntilReturn(CommandContext ctx) {
    if (plan instanceof ScriptExecutionPlan scriptPlan) {
      return scriptPlan.executeUntilReturn();
    }
    if (plan instanceof SingleOpExecutionPlan singleOpPlan) {
      if (singleOpPlan.statement instanceof SQLReturnStatement) {
        return new ReturnStep(singleOpPlan.statement, ctx, profilingEnabled);
      }
    }
    if (plan instanceof IfExecutionPlan ifPlan) {
      return ifPlan.executeUntilReturn();
    }
    throw new IllegalStateException();
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    if (plan == null) {
      return "Script Line";
    }
    return plan.prettyPrint(depth, indent);
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    var planCopy = plan.copy(ctx);
    return new ScriptLineStep(planCopy, ctx, profilingEnabled);
  }
}
