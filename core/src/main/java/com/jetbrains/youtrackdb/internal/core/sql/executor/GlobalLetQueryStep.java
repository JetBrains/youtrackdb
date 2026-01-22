package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SubQueryCollector;
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

  private GlobalLetQueryStep(
      SQLIdentifier varName,
      InternalExecutionPlan subExecutionPlan, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);

    this.varName = varName;
    this.subExecutionPlan = subExecutionPlan;
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
    final var varName = this.varName.getStringValue();
    var convertToList = varName.startsWith(SubQueryCollector.GENERATED_ALIAS_PREFIX);
    if (!convertToList) {
      for (var expr : ctx.getParentWhereExpressions()) {
        if (expr.varMightBeInUse(varName)) {
          convertToList = true;
          break;
        }
      }
    }
    final var rs = new LocalResultSet(ctx.getDatabaseSession(), subExecutionPlan);
    ctx.setVariable(varName, convertToList ? toList(rs) : rs);
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

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    SQLIdentifier varNameCopy = null;
    if (varName != null) {
      varNameCopy = varName.copy();
    }
    InternalExecutionPlan subExecutionPlanCopy = null;
    if (subExecutionPlan != null) {
      subExecutionPlanCopy = subExecutionPlan.copy(ctx);
    }

    return new GlobalLetQueryStep(varNameCopy, subExecutionPlanCopy, ctx, profilingEnabled);
  }
}
