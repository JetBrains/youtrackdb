package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SelectExecutionPlan implements InternalExecutionPlan {
  private String location;

  protected CommandContext ctx;

  protected List<ExecutionStepInternal> steps = new ArrayList<>();

  private ExecutionStepInternal lastStep = null;

  private String statement;
  private String genericStatement;

  public SelectExecutionPlan(CommandContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public CommandContext getContext() {
    return ctx;
  }

  @Override
  public void close() {
    lastStep.close();
  }

  @Override
  public ExecutionStream start() {
    return lastStep.start(ctx);
  }

  @Override
  public @Nonnull String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
    for (var i = 0; i < steps.size(); i++) {
      var step = steps.get(i);
      result.append(step.prettyPrint(depth, indent));
      if (i < steps.size() - 1) {
        result.append("\n");
      }
    }
    return result.toString();
  }

  @Override
  public void reset(CommandContext ctx) {
    steps.forEach(ExecutionStepInternal::reset);
  }

  public void chain(ExecutionStepInternal nextStep) {
    if (lastStep != null) {
      lastStep.setNext(nextStep);
      nextStep.setPrevious(lastStep);
    }
    lastStep = nextStep;
    steps.add(nextStep);
  }

  @Override
  public @Nonnull List<ExecutionStep> getSteps() {
    // TODO do a copy of the steps
    return (List) steps;
  }

  public void setSteps(List<ExecutionStepInternal> steps) {
    this.steps = steps;
    if (!steps.isEmpty()) {
      lastStep = steps.getLast();
    } else {
      lastStep = null;
    }
  }

  @Override
  public @Nonnull Result toResult(@Nullable DatabaseSession db) {
    var session = (DatabaseSessionInternal) db;
    var result = new ResultInternal(session);
    result.setProperty("type", "QueryExecutionPlan");
    result.setProperty(JAVA_TYPE, getClass().getName());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    result.setProperty(
        "steps",
        steps == null ? null
            : steps.stream().map(x ->
                x.toResult(session)).collect(Collectors.toList()));
    return result;
  }

  @Override
  public long getCost() {
    return 0L;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = new ResultInternal(session);
    result.setProperty("type", "QueryExecutionPlan");
    result.setProperty(JAVA_TYPE, getClass().getName());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    result.setProperty(
        "steps",
        steps == null ? null
            : steps.stream().map(x -> x.serialize(session)).collect(Collectors.toList()));
    return result;
  }

  @Override
  public void deserialize(Result serializedExecutionPlan, DatabaseSessionInternal session) {
    List<Result> serializedSteps = serializedExecutionPlan.getProperty("steps");
    for (var serializedStep : serializedSteps) {
      try {
        String className = serializedStep.getProperty(JAVA_TYPE);
        var step =
            (ExecutionStepInternal) Class.forName(className).newInstance();
        step.deserialize(serializedStep, session);
        chain(step);
      } catch (Exception e) {
        throw BaseException.wrapException(
            new CommandExecutionException(session,
                "Cannot deserialize execution step:" + serializedStep),
            e, session);
      }
    }
  }

  @Override
  public InternalExecutionPlan copy(CommandContext ctx) {
    var copy = new SelectExecutionPlan(ctx);
    copyOn(copy, ctx);
    return copy;
  }

  protected void copyOn(SelectExecutionPlan copy, CommandContext ctx) {
    ExecutionStepInternal lastStep = null;

    for (var step : this.steps) {
      var newStep =
          (ExecutionStepInternal) step.copy(ctx);
      newStep.setPrevious(lastStep);
      if (lastStep != null) {
        lastStep.setNext(newStep);
      }
      lastStep = newStep;
      copy.getSteps().add(newStep);
    }

    copy.lastStep = copy.steps.isEmpty() ? null : copy.steps.getLast();
    copy.location = this.location;
    copy.statement = this.statement;
  }

  @Override
  public boolean canBeCached() {
    for (var step : steps) {
      if (!step.canBeCached()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String getStatement() {
    return statement;
  }

  @Override
  public void setStatement(String statement) {
    this.statement = statement;
  }

  @Override
  public String getGenericStatement() {
    return this.genericStatement;
  }

  @Override
  public void setGenericStatement(String stm) {
    this.genericStatement = stm;
  }
}
