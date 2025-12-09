package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Execution Steps are the building blocks of a query execution plan
 *
 * <p>Typically an execution plan is made of a chain of steps. The execution is pull-based, meaning
 * that the result set that the client iterates is conceptually the one returned by <i>last</i> step
 * of the execution plan
 *
 * <p>At each `next()` invocation, the step typically fetches a record from the previous (upstream)
 * step, does its elaboration (eg. for a filtering step, it can discard the record and fetch another
 * one if it doesn't match the conditions) and returns the elaborated step
 *
 * <p>
 *
 * <p>The invocation of <code>syncPull(ctx, nResults)</code> has to return a result set of at most
 * nResults records. If the upstream (the previous steps) return more records, they have to be
 * returned by next call of <code>syncPull()</code>. The returned result set can have less than
 * nResults records ONLY if current step cannot produce any more records (eg. the upstream does not
 * have any more records)
 */
public interface ExecutionStepInternal extends ExecutionStep {

  ExecutionStream start(CommandContext ctx) throws TimeoutException;

  void sendTimeout();

  void setPrevious(ExecutionStepInternal step);

  void setNext(ExecutionStepInternal step);

  void close();

  static String getIndent(int depth, int indent) {
    var result = new StringBuilder();
    for (var i = 0; i < depth; i++) {
      for (var j = 0; j < indent; j++) {
        result.append(" ");
      }
    }
    return result.toString();
  }

  default String prettyPrint(int depth, int indent) {
    var spaces = getIndent(depth, indent);
    return spaces + getClass().getSimpleName();
  }

  @Override
  default @Nonnull String getName() {
    return getClass().getSimpleName();
  }

  @Override
  default @Nonnull String getType() {
    return getClass().getSimpleName();
  }

  @Override
  default String getDescription() {
    return prettyPrint(0, 3);
  }

  default String getTargetNode() {
    return "<local>";
  }

  @Override
  @Nonnull
  default List<ExecutionStep> getSubSteps() {
    return Collections.emptyList();
  }

  default List<ExecutionPlan> getSubExecutionPlans() {
    return Collections.emptyList();
  }

  default void reset() {
    // do nothing
  }

  default Result serialize(DatabaseSessionEmbedded session) {
    throw new UnsupportedOperationException();
  }

  default void deserialize(Result fromResult, DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  static ResultInternal basicSerialize(DatabaseSessionEmbedded session,
      ExecutionStepInternal step) {
    var result = new ResultInternal(session);
    result.setProperty(InternalExecutionPlan.JAVA_TYPE, step.getClass().getName());
    if (!step.getSubSteps().isEmpty()) {
      List<Result> serializedSubsteps = new ArrayList<>();
      for (var substep : step.getSubSteps()) {
        serializedSubsteps.add(((ExecutionStepInternal) substep).serialize(session));
      }
      result.setProperty("subSteps", serializedSubsteps);
    }

    if (step.getSubExecutionPlans() != null && !step.getSubExecutionPlans().isEmpty()) {
      List<Result> serializedSubPlans = new ArrayList<>();
      for (var substep : step.getSubExecutionPlans()) {
        serializedSubPlans.add(((InternalExecutionPlan) substep).serialize(session));
      }
      result.setProperty("subExecutionPlans", serializedSubPlans);
    }
    return result;
  }

  static void basicDeserialize(Result serialized, ExecutionStepInternal step,
      DatabaseSessionInternal session)
      throws ClassNotFoundException, IllegalAccessException, InstantiationException {
    List<Result> serializedSubsteps = serialized.getProperty("subSteps");
    if (serializedSubsteps != null) {
      for (var serializedSub : serializedSubsteps) {
        String className = serializedSub.getProperty(InternalExecutionPlan.JAVA_TYPE);
        var subStep =
            (ExecutionStepInternal) Class.forName(className).newInstance();
        subStep.deserialize(serializedSub, session);
        step.getSubSteps().add(subStep);
      }
    }

    List<Result> serializedPlans = serialized.getProperty("subExecutionPlans");
    if (serializedSubsteps != null) {
      for (var serializedSub : serializedPlans) {
        String className = serializedSub.getProperty(InternalExecutionPlan.JAVA_TYPE);
        var subStep =
            (InternalExecutionPlan) Class.forName(className).newInstance();
        subStep.deserialize(serializedSub, session);
        step.getSubExecutionPlans().add(subStep);
      }
    }
  }

  ExecutionStep copy(CommandContext ctx);

  default boolean canBeCached() {
    return false;
  }
}
