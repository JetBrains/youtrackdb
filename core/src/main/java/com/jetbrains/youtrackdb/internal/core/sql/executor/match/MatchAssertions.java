package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

/**
 * Assertion helper methods for MATCH execution classes.
 *
 * <p>These methods are designed to be called inside Java {@code assert} statements
 * so they have <b>zero runtime cost</b> when assertions are disabled (production).
 * Each method validates an invariant, throws {@link AssertionError} on violation,
 * and returns {@code true} on success — making it compatible with {@code assert}.
 *
 * <pre>
 *   assert MatchAssertions.checkNotNull(item, "path item");
 * </pre>
 *
 * <p>Extracting the checks into methods (rather than inlining conditions) allows
 * both branches of each check to be covered by unit tests, avoiding JaCoCo's
 * phantom uncovered-branch issue with inline {@code assert condition} statements.
 */
final class MatchAssertions {

  private MatchAssertions() {
  }

  /**
   * Asserts that {@code value} is not null.
   *
   * @param value the value to check
   * @param label a short description for the error message (e.g. "path item")
   * @return always {@code true}
   * @throws AssertionError if {@code value} is null
   */
  static boolean checkNotNull(Object value, String label) {
    if (value == null) {
      throw new AssertionError(label + " must not be null");
    }
    return true;
  }

  /**
   * Asserts that {@code value} is not null and not empty.
   *
   * @param value the string to check
   * @param label a short description for the error message
   * @return always {@code true}
   * @throws AssertionError if {@code value} is null or empty
   */
  static boolean checkNotEmpty(String value, String label) {
    if (value == null || value.isEmpty()) {
      throw new AssertionError(label + " must not be null or empty");
    }
    return true;
  }
}
