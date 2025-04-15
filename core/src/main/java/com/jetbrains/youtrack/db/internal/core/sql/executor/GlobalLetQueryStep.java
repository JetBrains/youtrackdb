package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public class GlobalLetQueryStep extends AbstractExecutionStep {

  private final SQLIdentifier varName;
  private final InternalExecutionPlan subExecutionPlan;

  public GlobalLetQueryStep(
      SQLIdentifier varName,
      SQLStatement query,
      CommandContext ctx,
      boolean profilingEnabled,
      List<String> scriptVars) {
    super(ctx, profilingEnabled);
    this.varName = varName;

    var subCtx = new BasicCommandContext();
    if (scriptVars != null) {
      scriptVars.forEach(subCtx::declareScriptVariable);
    }
    subCtx.setDatabaseSession(ctx.getDatabaseSession());
    subCtx.setParent(ctx);
    if (query.toString().contains("?")) {
      // with positional parameters, you cannot know if a parameter has the same ordinal as the one
      // cached
      subExecutionPlan = query.createExecutionPlanNoCache(subCtx, profilingEnabled);
    } else {
      subExecutionPlan = query.createExecutionPlan(subCtx, profilingEnabled);
    }
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    calculate(ctx);
    return ExecutionStream.empty();
  }

  private void calculate(CommandContext ctx) {
    ctx.setVariable(varName.getStringValue(),
        toList(new LocalResultSet(ctx.getDatabaseSession(), subExecutionPlan)));
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
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces
        + "+ LET (once)\n"
        + spaces
        + "  "
        + varName
        + " = \n"
        + box(spaces + "    ", this.subExecutionPlan.prettyPrint(0, indent));
  }

  @Override
  public List<ExecutionPlan> getSubExecutionPlans() {
    return Collections.singletonList(this.subExecutionPlan);
  }

  private String box(String spaces, String s) {
    var rows = s.split("\n");
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+-------------------------\n");
    for (var row : rows) {
      result.append(spaces);
      result.append("| ");
      result.append(row);
      result.append("\n");
    }
    result.append(spaces);
    result.append("+-------------------------");
    return result.toString();
  }
}
