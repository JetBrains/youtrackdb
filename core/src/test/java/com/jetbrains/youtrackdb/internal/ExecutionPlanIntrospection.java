package com.jetbrains.youtrackdb.internal;

import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import java.util.List;

/**
 * One home for the recursive plan walk that test assertions use to introspect an execution plan.
 *
 * <p>Before this class the walk was copied into four test classes with two mutually incompatible
 * root-inclusion rules, so a reader moving between them got a different answer for the same call
 * name. It is one contract, so it lives in one place.
 *
 * <p><b>The walk recurses {@code getSubSteps()} and never {@code getSubExecutionPlans()}.</b> That
 * is deliberate and load-bearing. A MATCH plan publishes its nested fetch steps through
 * {@code getSubSteps()} only; publishing through both accessors would make every tally here
 * double-count, so a change that starts publishing a nested plan through
 * {@code getSubExecutionPlans()} must revisit this class rather than the call sites. Two older
 * walks in the tree — {@code CommandExecutorSQLSelectTest.indexUsages} and
 * {@code BaseDBJUnit5Test.indexesUsed} — recurse both accessors and therefore encode the opposite
 * convention; they are not interchangeable with these methods.
 *
 * <p>All three entry points take {@code List<ExecutionStep>} because every call site can express
 * that shape, and the list form makes the root-inclusion rule unambiguous: the steps in the list
 * are themselves tested, as are their descendants.
 */
public final class ExecutionPlanIntrospection {

  private ExecutionPlanIntrospection() {
  }

  /** Reports whether any step in the list, or any descendant of one, has the given type. */
  public static boolean containsStepOfType(List<ExecutionStep> steps, Class<?> stepType) {
    return findStepOfType(steps, stepType) != null;
  }

  /**
   * Single-step adapter over {@link #containsStepOfType(List, Class)}, for callers holding one root
   * step rather than a plan's step list. The root is tested along with its descendants.
   */
  public static boolean containsStepOfType(ExecutionStep step, Class<?> stepType) {
    return containsStepOfType(List.of(step), stepType);
  }

  /**
   * Counts steps of the given type across the list and every nesting level below it. Mirrors the
   * accumulating index-usage helpers in the SELECT tests, which are the callers a double-published
   * sub-plan would mislead.
   */
  public static long countStepsOfType(List<ExecutionStep> steps, Class<?> stepType) {
    var count = 0L;
    for (var step : steps) {
      if (stepType.isInstance(step)) {
        count++;
      }
      count += countStepsOfType(step.getSubSteps(), stepType);
    }
    return count;
  }

  /**
   * Returns the first step of the given type in depth-first order, or {@code null} when the list and
   * its descendants hold none. "First" is the list's own order at each level, root before children.
   */
  public static ExecutionStep findStepOfType(List<ExecutionStep> steps, Class<?> stepType) {
    for (var step : steps) {
      if (stepType.isInstance(step)) {
        return step;
      }
      var nested = findStepOfType(step.getSubSteps(), stepType);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }
}
