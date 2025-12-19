package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SingleOpExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Superclass for SQL statements that are too simple to deserve an execution planner. All the
 * execution is delegated to the statement itself, with the execute(ctx) method.
 */
public abstract class SQLSimpleExecStatement extends SQLStatement {

  public SQLSimpleExecStatement(int id) {
    super(id);
  }

  public SQLSimpleExecStatement(YouTrackDBSql p, int id) {
    super(p, id);
  }

  public abstract ExecutionStream executeSimple(CommandContext ctx);

  @Override
  public ResultSet execute(
      DatabaseSessionEmbedded session,
      Object[] args,
      CommandContext parentContext,
      boolean usePlanCache) {
    var ctx = new BasicCommandContext();
    if (parentContext != null) {
      ctx.setParentWithoutOverridingChild(parentContext);
    }
    ctx.setDatabaseSession(session);
    Map<Object, Object> params = new HashMap<>();
    if (args != null) {
      for (var i = 0; i < args.length; i++) {
        params.put(i, args[i]);
      }
    }
    ctx.setInputParameters(params);
    var executionPlan = (SingleOpExecutionPlan) createExecutionPlan(ctx, false);
    return new ExecutionResultSet(executionPlan.executeInternal(ctx), ctx, executionPlan);
  }

  @Override
  public ResultSet execute(
      DatabaseSessionEmbedded session,
      Map<Object, Object> params,
      CommandContext parentContext,
      boolean usePlanCache) {
    var ctx = new BasicCommandContext();
    if (parentContext != null) {
      ctx.setParentWithoutOverridingChild(parentContext);
    }
    ctx.setDatabaseSession(session);
    ctx.setInputParameters(params);
    var executionPlan = (SingleOpExecutionPlan) createExecutionPlan(ctx, false);
    return new ExecutionResultSet(executionPlan.executeInternal(ctx), ctx, executionPlan);
  }

  @Override
  public InternalExecutionPlan createExecutionPlan(CommandContext ctx, boolean enableProfiling) {
    return new SingleOpExecutionPlan(ctx, this);
  }
}
