package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.parser.WhileStep;

/** Execution plan that iterates over a collection and executes a body of statements for each element. */
public class ForEachExecutionPlan extends UpdateExecutionPlan {

  public ForEachExecutionPlan(CommandContext ctx) {
    super(ctx);
  }

  public boolean containsReturn() {
    for (var step : getSteps()) {
      if (step instanceof ForEachStep forEachStep) {
        return forEachStep.containsReturn();
      }
      if (step instanceof WhileStep whileStep) {
        return whileStep.containsReturn();
      }
    }

    return false;
  }
}
