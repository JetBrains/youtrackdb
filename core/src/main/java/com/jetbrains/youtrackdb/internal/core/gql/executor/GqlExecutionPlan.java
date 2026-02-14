package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import javax.annotation.Nullable;


@SuppressWarnings("unused")
/// Execution plan for GQL queries.
public class GqlExecutionPlan {

  @Nullable
  private GqlExecutionStep lastStep = null;

  /// Add a step to the execution plan chain.
  public void chain(GqlExecutionStep step) {
    if (lastStep != null) {
      step.setPrevious(lastStep);
    }
    lastStep = step;
  }

  /// Start execution and return a stream of results.
  public GqlExecutionStream start(GqlExecutionContext ctx) {
    if (lastStep == null) {
      return GqlExecutionStream.empty();
    }
    return lastStep.start(ctx);
  }

  /// Close the execution plan and release resources.
  /// Closing lastStep will cascade to all previous steps.
  public void close() {
    if (lastStep != null) {
      lastStep.close();
    }
  }

  /// Reset the execution plan for re-execution.
  /// Resetting lastStep will cascade to all previous steps.
  public void reset() {
    if (lastStep != null) {
      lastStep.reset();
    }
  }

  /// Create a copy of this execution plan for caching purposes.
  public GqlExecutionPlan copy() {
    var copy = new GqlExecutionPlan();
    if (lastStep != null) {
      copy.lastStep = lastStep.copy();
    }
    return copy;
  }

  /// Check if this execution plan can be cached.
  /// For now, all GQL plans can be cached.
  @SuppressWarnings("SameReturnValue")
  public static boolean canBeCached() {
    return true;
  }
}
