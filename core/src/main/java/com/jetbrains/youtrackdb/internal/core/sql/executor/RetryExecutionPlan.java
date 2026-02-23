package com.jetbrains.youtrackdb.internal.core.sql.executor;

/**
 *
 */

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.parser.WhileStep;

/** Execution plan that retries its steps on transient failures. */
public class RetryExecutionPlan extends UpdateExecutionPlan {

  public RetryExecutionPlan(CommandContext ctx) {
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
