package com.jetbrains.youtrackdb.internal.core.sql.executor;

/**
 *
 */

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class IfExecutionPlan implements InternalExecutionPlan {

  private String location;

  private final CommandContext ctx;

  @Override
  public CommandContext getContext() {
    return ctx;
  }

  protected IfStep step;

  public IfExecutionPlan(CommandContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public void reset(CommandContext ctx) {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {
    step.close();
  }

  @Override
  public ExecutionStream start() {
    return step.start(ctx);
  }

  @Override
  public @Nonnull String prettyPrint(int depth, int indent) {
    return step.prettyPrint(depth, indent);
  }

  public void chain(IfStep step) {
    this.step = step;
  }

  @Override
  public @Nonnull List<ExecutionStep> getSteps() {
    // TODO do a copy of the steps
    return Collections.singletonList(step);
  }

  public void setSteps(List<ExecutionStepInternal> steps) {
    this.step = (IfStep) steps.get(0);
  }

  @Override
  public @Nonnull Result toResult(@Nullable DatabaseSession db) {
    var session = (DatabaseSessionInternal) db;
    var result = new ResultInternal(session);
    result.setProperty("type", "IfExecutionPlan");
    result.setProperty("javaType", getClass().getName());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    result.setProperty("steps", Collections.singletonList(step.toResult(session)));
    return result;
  }

  @Override
  public long getCost() {
    return 0L;
  }

  @Override
  public boolean canBeCached() {
    return false;
  }

  @Nullable
  public ExecutionStepInternal executeUntilReturn() {
    var plan = step.producePlan(ctx);
    if (plan != null) {
      return plan.executeUntilReturn();
    } else {
      return null;
    }
  }

  public boolean containsReturn() {
    return step.containsReturn();
  }
}
