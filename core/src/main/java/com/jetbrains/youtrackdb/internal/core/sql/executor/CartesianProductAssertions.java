package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.stream.Stream;

/**
 * Assertion helper methods for {@link CartesianProductStep}.
 *
 * <p>These methods are designed to be called inside Java {@code assert} statements
 * so they have <b>zero runtime cost</b> when assertions are disabled (production).
 * Each method validates an invariant, throws {@link AssertionError} on violation,
 * and returns {@code true} on success -- making it compatible with {@code assert}.
 *
 * <pre>
 *   assert CartesianProductAssertions.checkStreamBuilt(stream);
 * </pre>
 *
 * <p>Extracting the checks into methods (rather than inlining conditions) allows
 * both branches of each check to be covered by unit tests, avoiding JaCoCo's
 * phantom uncovered-branch issue with inline {@code assert condition} statements.
 */
final class CartesianProductAssertions {

  private CartesianProductAssertions() {
  }

  /**
   * Asserts that the Cartesian product stream was successfully built from the
   * sub-plans. A null stream indicates that the sub-plan list was empty, which
   * would be a programming error in the planner.
   *
   * @param stream the composed stream (may be null if no sub-plans were added)
   * @return always {@code true}
   * @throws AssertionError if {@code stream} is null
   */
  static boolean checkStreamBuilt(Stream<Result[]> stream) {
    if (stream == null) {
      throw new AssertionError(
          "Cartesian product stream was not built -- sub-plan list must not be empty");
    }
    return true;
  }

  /**
   * Asserts that a sub-plan being added to the Cartesian product is not null.
   *
   * @param subPlan the sub-plan to validate
   * @return always {@code true}
   * @throws AssertionError if {@code subPlan} is null
   */
  static boolean checkSubPlanNotNull(InternalExecutionPlan subPlan) {
    if (subPlan == null) {
      throw new AssertionError("sub-plan must not be null");
    }
    return true;
  }
}
