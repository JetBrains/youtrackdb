package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/// Execution plan for GQL queries.
///
/// Contains a chain of execution steps that are executed in order.
/// The last step produces the final results.
public class GqlExecutionPlan {

  private final List<GqlExecutionStep> steps = new ArrayList<>();
  @Nullable
  private GqlExecutionStep lastStep = null;

  /// Add a step to the execution plan chain.
  public void chain(GqlExecutionStep step) {
    if (lastStep != null) {
      step.setPrevious(lastStep);
    }
    steps.add(step);
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
  public void close() {
    if (lastStep != null) {
      lastStep.close();
    }
  }

  /// Reset the execution plan for re-execution.
  public void reset() {
    for (var step : steps) {
      step.reset();
    }
  }

  /// Get all steps in the plan.
  public List<GqlExecutionStep> getSteps() {
    return steps;
  }

  /// Get a human-readable representation of the execution plan.
  public String prettyPrint() {
    var sb = new StringBuilder();
    sb.append("GqlExecutionPlan {\n");
    for (int i = 0; i < steps.size(); i++) {
      sb.append("  ").append(i + 1).append(". ");
      sb.append(steps.get(i).prettyPrint(0, 2));
      sb.append("\n");
    }
    sb.append("}");
    return sb.toString();
  }
}
