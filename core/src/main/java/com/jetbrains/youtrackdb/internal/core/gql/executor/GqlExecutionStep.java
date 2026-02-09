package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import javax.annotation.Nullable;

/// Execution step for GQL queries.
///
/// Steps are chained together to form an execution plan.
/// Each step pulls data from the previous step and processes it.
///
@SuppressWarnings("unused")
public interface GqlExecutionStep {

  /// Start execution and return a stream of results.
  GqlExecutionStream start(GqlExecutionContext ctx);

  /// Set the previous step in the chain.
  void setPrevious(@Nullable GqlExecutionStep step);

  /// Get the previous step in the chain.
  @Nullable
  GqlExecutionStep getPrevious();

  /// Close the step and release resources.
  void close();

  /// Reset the step for re-execution.
  void reset();

  /// Get a human-readable description of this step.
  default String prettyPrint(int depth, int indent) {
    return "  ".repeat(depth * indent) + getClass().getSimpleName();
  }

  /// Create a copy of this step for caching purposes.
  GqlExecutionStep copy();
}
