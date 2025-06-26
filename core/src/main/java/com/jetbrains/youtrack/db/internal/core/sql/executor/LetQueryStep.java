package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.LocalResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class LetQueryStep extends AbstractExecutionStep {

  private final SQLIdentifier varName;
  private final SQLStatement query;

  public LetQueryStep(
      SQLIdentifier varName, SQLStatement query, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.varName = varName;
    this.query = query;
  }

  private ResultInternal calculate(ResultInternal result, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    var subCtx = new BasicCommandContext();
    subCtx.setDatabaseSession(session);
    subCtx.setParentWithoutOverridingChild(ctx);
    InternalExecutionPlan subExecutionPlan;
    if (query.toString().contains("?")) {
      // with positional parameters, you cannot know if a parameter has the same ordinal as the
      // one cached
      subExecutionPlan = query.createExecutionPlanNoCache(subCtx, profilingEnabled);
    } else {
      subExecutionPlan = query.createExecutionPlan(subCtx, profilingEnabled);
    }
    result.setMetadata(varName.getStringValue(),
        toList(new LocalResultSet(session, subExecutionPlan)));
    return result;
  }

  private List<Result> toList(LocalResultSet oLocalResultSet) {
    List<Result> result = new ArrayList<>();
    while (oLocalResultSet.hasNext()) {
      result.add(oLocalResultSet.next());
    }
    oLocalResultSet.close();
    return result;
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
    return calculate((ResultInternal) result, ctx);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ LET (for each record)\n" + spaces + "  " + varName + " = (" + query + ")";
  }
}
