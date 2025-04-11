package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.internal.core.command.BasicServerCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.ServerCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.core.sql.executor.SingleOpServerExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Superclass for SQL statements that are too simple to deserve an execution planner. All the
 * execution is delegated to the statement itself, with the execute(ctx) method.
 */
public abstract class SQLSimpleExecServerStatement extends SQLServerStatement {

  public SQLSimpleExecServerStatement(int id) {
    super(id);
  }

  public SQLSimpleExecServerStatement(YouTrackDBSql p, int id) {
    super(p, id);
  }

  public abstract ExecutionStream executeSimple(ServerCommandContext ctx);

  public ResultSet execute(
      YouTrackDBInternal db,
      Object[] args,
      ServerCommandContext parentContext,
      boolean usePlanCache) {
    var ctx = new BasicServerCommandContext();
    if (parentContext != null) {
      ctx.setParentWithoutOverridingChild(parentContext);
    }
    ctx.setServer(db);
    Map<Object, Object> params = new HashMap<>();
    if (args != null) {
      for (var i = 0; i < args.length; i++) {
        params.put(i, args[i]);
      }
    }
    ctx.setInputParameters(params);
    var executionPlan =
        (SingleOpServerExecutionPlan) createExecutionPlan(ctx, false);
    return new ExecutionResultSet(executionPlan.executeInternal(), ctx, executionPlan);
  }

  public ResultSet execute(
      YouTrackDBInternal db, Map params, ServerCommandContext parentContext,
      boolean usePlanCache) {
    var ctx = new BasicServerCommandContext();
    if (parentContext != null) {
      ctx.setParentWithoutOverridingChild(parentContext);
    }
    ctx.setServer(db);
    ctx.setInputParameters(params);
    var executionPlan =
        (SingleOpServerExecutionPlan) createExecutionPlan(ctx, false);
    return new ExecutionResultSet(executionPlan.executeInternal(), ctx, executionPlan);
  }

  public InternalExecutionPlan createExecutionPlan(
      ServerCommandContext ctx, boolean enableProfiling) {
    return new SingleOpServerExecutionPlan(ctx, this);
  }
}
