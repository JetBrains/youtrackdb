package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;

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

  /**
   * Validates all preconditions for {@link EdgeTraversal} construction: the edge itself,
   * its source node ({@code edge.out}), and its target node ({@code edge.in}) must all
   * be non-null.
   *
   * <p>Consolidating three checks into a single method reduces the number of
   * {@code assert} call sites in EdgeTraversal from three to one, which minimises
   * JaCoCo phantom-branch noise while keeping full branch coverage on the checks
   * themselves.
   *
   * @param edge the pattern edge to validate
   * @return always {@code true}
   * @throws AssertionError if any precondition is violated
   */
  static boolean validateEdgeTraversalArgs(PatternEdge edge) {
    checkNotNull(edge, "pattern edge");
    checkNotNull(edge.out, "edge source node");
    checkNotNull(edge.in, "edge target node");
    return true;
  }

  /**
   * Validates preconditions for {@link PatternNode#addEdge}: both the path item and the
   * target node must be non-null.
   *
   * <p>Consolidating two null-checks into a single method reduces the number of
   * {@code assert} call sites in {@code addEdge} from two to one, which minimises
   * JaCoCo phantom-branch noise while keeping full branch coverage on the checks
   * themselves.
   *
   * @param item the parsed path item describing the traversal
   * @param to   the target node
   * @return always {@code true}
   * @throws AssertionError if either argument is null
   */
  static boolean validateAddEdgeArgs(SQLMatchPathItem item, PatternNode to) {
    checkNotNull(item, "path item");
    checkNotNull(to, "target node");
    return true;
  }
}
