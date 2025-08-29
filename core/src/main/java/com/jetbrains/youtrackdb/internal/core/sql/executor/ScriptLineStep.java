package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
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
    if (plan instanceof InsertExecutionPlan) {
      ((InsertExecutionPlan) plan).executeInternal();
    } else if (plan instanceof DeleteExecutionPlan) {
      ((DeleteExecutionPlan) plan).executeInternal();
    } else if (plan instanceof UpdateExecutionPlan) {
      ((UpdateExecutionPlan) plan).executeInternal();
    } else if (plan instanceof DDLExecutionPlan) {
      ((DDLExecutionPlan) plan).executeInternal((BasicCommandContext) ctx);
    } else if (plan instanceof SingleOpExecutionPlan) {
      ((SingleOpExecutionPlan) plan).executeInternal((BasicCommandContext) ctx);
    }
    return plan.start();
  }

  public boolean containsReturn() {
    if (plan instanceof ScriptExecutionPlan) {
      return ((ScriptExecutionPlan) plan).containsReturn();
    }
    if (plan instanceof SingleOpExecutionPlan) {
      if (((SingleOpExecutionPlan) plan).statement instanceof SQLReturnStatement) {
        return true;
      }
    }
    if (plan instanceof IfExecutionPlan) {
      if (((IfExecutionPlan) plan).containsReturn()) {
        return true;
      }
    }

    if (plan instanceof ForEachExecutionPlan) {
      return ((ForEachExecutionPlan) plan).containsReturn();
    }
    return false;
  }

  public ExecutionStepInternal executeUntilReturn(CommandContext ctx) {
    if (plan instanceof ScriptExecutionPlan) {
      return ((ScriptExecutionPlan) plan).executeUntilReturn();
    }
    if (plan instanceof SingleOpExecutionPlan) {
      if (((SingleOpExecutionPlan) plan).statement instanceof SQLReturnStatement) {
        return new ReturnStep(((SingleOpExecutionPlan) plan).statement, ctx, profilingEnabled);
      }
    }
    if (plan instanceof IfExecutionPlan) {
      return ((IfExecutionPlan) plan).executeUntilReturn();
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
