package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLCreateVertexStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class CreateVertexExecutionPlanner extends InsertExecutionPlanner {

  public CreateVertexExecutionPlanner(SQLCreateVertexStatement statement) {
    this.targetClass =
        statement.getTargetClass() == null ? null : statement.getTargetClass().copy();
    if (this.targetClass == null) {
      this.targetClass = new SQLIdentifier("V");
    }
    this.insertBody = statement.getInsertBody() == null ? null : statement.getInsertBody().copy();
    this.returnStatement =
        statement.getReturnStatement() == null ? null : statement.getReturnStatement().copy();
  }

  @Override
  public InsertExecutionPlan createExecutionPlan(CommandContext ctx, boolean enableProfiling) {
    var prev = super.createExecutionPlan(ctx, enableProfiling);
    List<ExecutionStep> steps = new ArrayList<>(prev.getSteps());
    var result = new InsertExecutionPlan(ctx);

    handleCheckType(result, ctx, enableProfiling);
    for (var step : steps) {
      result.chain((ExecutionStepInternal) step);
    }
    return result;
  }

  private void handleCheckType(
      InsertExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {
    if (targetClass != null) {
      result.chain(
          new CheckClassTypeStep(targetClass.getStringValue(), "V", ctx, profilingEnabled));
    }
  }
}
