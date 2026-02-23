package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Execution plan for SQL script blocks containing multiple statements. */
public class ScriptExecutionPlan implements InternalExecutionPlan {

  private String location;
  private final CommandContext ctx;
  private boolean executed = false;
  protected List<ScriptLineStep> steps = new ArrayList<>();
  private ExecutionStepInternal lastStep = null;
  private ExecutionStream finalResult = null;
  private String statement;
  private String genericStatement;

  public ScriptExecutionPlan(CommandContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public void reset(CommandContext ctx) {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public CommandContext getContext() {
    return ctx;
  }

  @Override
  public void close() {
    for (var step : steps) {
      step.close();
    }

    lastStep.close();
  }

  @Override
  public ExecutionStream start() {
    doExecute();
    return finalResult;
  }

  private void doExecute() {
    if (!executed) {
      executeUntilReturn();
      executed = true;
      List<Result> collected = new ArrayList<>();
      var results = lastStep.start(ctx);
      while (results.hasNext(ctx)) {
        collected.add(results.next(ctx));
      }
      results.close(ctx);
      if (lastStep instanceof ScriptLineStep) {
        // collected.setPlan(((ScriptLineStep) lastStep).plan);
      }
      finalResult = ExecutionStream.resultIterator(collected.iterator());
    }
  }

  @Override
  public @Nonnull String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
    for (var i = 0; i < steps.size(); i++) {
      ExecutionStepInternal step = steps.get(i);
      result.append(step.prettyPrint(depth, indent));
      if (i < steps.size() - 1) {
        result.append("\n");
      }
    }
    return result.toString();
  }

  public void chain(InternalExecutionPlan nextPlan, boolean profilingEnabled) {
    var lastStep = steps.size() == 0 ? null : steps.get(steps.size() - 1);
    var nextStep = new ScriptLineStep(nextPlan, ctx, profilingEnabled);
    if (lastStep != null) {
      lastStep.setNext(nextStep);
      nextStep.setPrevious(lastStep);
    }
    steps.add(nextStep);
    this.lastStep = nextStep;
  }

  @Override
  public @Nonnull List<ExecutionStep> getSteps() {
    // TODO do a copy of the steps
    return (List) steps;
  }

  public void setSteps(List<ExecutionStepInternal> steps) {
    this.steps = (List) steps;
  }

  @Override
  public @Nonnull Result toResult(@Nullable DatabaseSessionEmbedded session) {
    var result = new ResultInternal(session);

    result.setProperty("type", "ScriptExecutionPlan");
    result.setProperty("javaType", getClass().getName());
    result.setProperty("cost", getCost());
    result.setProperty("prettyPrint", prettyPrint(0, 2));
    result.setProperty(
        "steps",
        steps == null ? null
            : steps.stream().map(x -> x.toResult(session)).collect(Collectors.toList()));
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

  public boolean containsReturn() {
    for (ExecutionStepInternal step : steps) {
      if (step instanceof ReturnStep) {
        return true;
      }
      if (step instanceof ScriptLineStep) {
        return ((ScriptLineStep) step).containsReturn();
      }
    }

    return false;
  }

  /**
   * executes all the script and returns last statement execution step, so that it can be executed
   * from outside
   *
   * @return
   */
  public ExecutionStepInternal executeUntilReturn() {
    if (steps.size() > 0) {
      lastStep = steps.get(steps.size() - 1);
    }
    for (var i = 0; i < steps.size() - 1; i++) {
      var step = steps.get(i);
      if (step.containsReturn()) {
        var returnStep = step.executeUntilReturn(ctx);
        if (returnStep != null) {
          lastStep = returnStep;
          return lastStep;
        }
      }
      var lastResult = step.start(ctx);

      while (lastResult.hasNext(ctx)) {
        lastResult.next(ctx);
      }
      lastResult.close(ctx);
    }
    this.lastStep = steps.get(steps.size() - 1);
    return lastStep;
  }

  /**
   * executes the whole script and returns last statement ONLY if it's a RETURN, otherwise it
   * returns null;
   *
   * @return
   */
  @Nullable
  public ExecutionStepInternal executeFull() {
    for (var i = 0; i < steps.size(); i++) {
      var step = steps.get(i);
      if (step.containsReturn()) {
        var returnStep = step.executeUntilReturn(ctx);
        if (returnStep != null) {
          return returnStep;
        }
      }
      var lastResult = step.start(ctx);

      while (lastResult.hasNext(ctx)) {
        lastResult.next(ctx);
      }
      lastResult.close(ctx);
    }

    return null;
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
