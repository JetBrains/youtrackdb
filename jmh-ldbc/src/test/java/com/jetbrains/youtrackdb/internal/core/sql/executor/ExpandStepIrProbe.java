package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;

/**
 * Test-only probe living in the same package as {@link ExpandStep} so it can reach the
 * package-private {@link ExpandStep#getAnalyzed()} and the package-private
 * {@link SubQueryStep#subExecutionPlan}. This lets a benchmark-package guard test verify that the
 * IR branch of {@code ExpandStep.filterMap} is genuinely taken — i.e. the pushed-down filter carries
 * a non-null lowered IR — rather than only checking that the predicate lowers in isolation (a broken
 * {@code ExpandStep.tryLower} returning null would leave {@code analyzed == null} and silently use
 * the AST fallback while producing identical functional results).
 *
 * <p>Not a JUnit test itself; a pure static navigator over the live execution-plan step graph
 * returned by {@code ResultSet.getExecutionPlan()}.
 */
public final class ExpandStepIrProbe {

  private ExpandStepIrProbe() {
  }

  /**
   * Navigates the plan (descending into subquery plans) to the single {@link ExpandStep} and
   * reports whether its lowered IR ({@code analyzed}) is non-null — i.e. the IR branch is active.
   *
   * @throws IllegalStateException if no {@link ExpandStep} is present in the plan
   */
  public static boolean innerExpandAnalyzedNonNull(ExecutionPlan plan) {
    ExpandStep expand = findExpand(plan);
    if (expand == null) {
      throw new IllegalStateException("no ExpandStep found in execution plan");
    }
    return expand.getAnalyzed() != null;
  }

  private static ExpandStep findExpand(ExecutionPlan plan) {
    for (ExecutionStep step : plan.getSteps()) {
      ExpandStep found = findInStep(step);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static ExpandStep findInStep(ExecutionStep step) {
    if (step instanceof ExpandStep expand) {
      return expand;
    }
    // The push-down query shape wraps the expand in a subquery; descend into its plan.
    if (step instanceof SubQueryStep subQuery) {
      for (ExecutionStep inner : subQuery.subExecutionPlan.getSteps()) {
        ExpandStep found = findInStep(inner);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
