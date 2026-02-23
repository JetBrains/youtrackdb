package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLDeleteStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

/** Planner that builds an execution plan for DELETE statements. */
public class DeleteExecutionPlanner {

  private final SQLFromClause fromClause;
  private final SQLWhereClause whereClause;
  private final boolean returnBefore;
  private final SQLLimit limit;
  private final boolean unsafe;

  public DeleteExecutionPlanner(SQLDeleteStatement stm) {
    this.fromClause = stm.getFromClause() == null ? null : stm.getFromClause().copy();
    this.whereClause = stm.getWhereClause() == null ? null : stm.getWhereClause().copy();
    this.returnBefore = stm.isReturnBefore();
    this.limit = stm.getLimit() == null ? null : stm.getLimit();
    this.unsafe = stm.isUnsafe();
  }

  public DeleteExecutionPlan createExecutionPlan(CommandContext ctx, boolean enableProfiling) {
    var result = new DeleteExecutionPlan(ctx);

    handleTarget(result, ctx, this.fromClause, this.whereClause, enableProfiling);
    handleUnsafe(result, ctx, this.unsafe, enableProfiling);
    handleLimit(result, ctx, this.limit, enableProfiling);
    handleDelete(result, ctx, enableProfiling);
    handleReturn(result, ctx, this.returnBefore, enableProfiling);

    return result;
  }

  private static void handleDelete(
      DeleteExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {
    result.chain(new DeleteStep(ctx, profilingEnabled));
  }

  private static void handleUnsafe(
      DeleteExecutionPlan result, CommandContext ctx, boolean unsafe, boolean profilingEnabled) {
    if (!unsafe) {
      result.chain(new CheckSafeDeleteStep(ctx, profilingEnabled));
    }
  }

  private static void handleReturn(
      DeleteExecutionPlan result,
      CommandContext ctx,
      boolean returnBefore,
      boolean profilingEnabled) {
    if (!returnBefore) {
      result.chain(new CountStep(ctx, profilingEnabled));
    }
  }

  private static void handleLimit(
      UpdateExecutionPlan plan, CommandContext ctx, SQLLimit limit, boolean profilingEnabled) {
    if (limit != null) {
      plan.chain(new LimitExecutionStep(limit, ctx, profilingEnabled));
    }
  }

  private static void handleTarget(
      UpdateExecutionPlan result,
      CommandContext ctx,
      SQLFromClause target,
      SQLWhereClause whereClause,
      boolean profilingEnabled) {
    var sourceStatement = new SQLSelectStatement(-1);
    sourceStatement.setTarget(target);
    sourceStatement.setWhereClause(whereClause);
    var planner = new SelectExecutionPlanner(sourceStatement);
    result.chain(
        new SubQueryStep(
            planner.createExecutionPlan(ctx, profilingEnabled, false), ctx, ctx, profilingEnabled));
  }

}
