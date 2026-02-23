package com.jetbrains.youtrackdb.internal.core.sql.parser;

import java.util.List;

/**
 * Assertion helper methods for {@link SQLMatchStatement} invariants.
 *
 * <p>These methods are designed to be called inside Java {@code assert} statements
 * so they have <b>zero runtime cost</b> when assertions are disabled (production).
 * Each method validates an invariant, throws {@link AssertionError} on violation,
 * and returns {@code true} on success — making it compatible with {@code assert}.
 *
 * <pre>
 *   assert SQLMatchAssertions.allAliasesAssigned(expressions);
 * </pre>
 *
 * <p>Extracting the checks into methods (rather than inlining conditions) allows
 * both branches of each check to be covered by unit tests, avoiding JaCoCo's
 * phantom uncovered-branch issue with inline {@code assert condition} statements.
 */
final class SQLMatchAssertions {

  private SQLMatchAssertions() {
  }

  /**
   * Verifies the post-condition of
   * {@link SQLMatchStatement#assignDefaultAliases}: every origin
   * node and every path-item filter must have a non-null alias after assignment.
   *
   * @param expressions the match expressions to validate
   * @return always {@code true}
   * @throws AssertionError if any alias is null
   */
  static boolean allAliasesAssigned(List<SQLMatchExpression> expressions) {
    for (var expression : expressions) {
      if (expression.origin.getAlias() == null) {
        throw new AssertionError(
            "origin alias must not be null after assignDefaultAliases");
      }
      for (var item : expression.items) {
        if (item.filter == null || item.filter.getAlias() == null) {
          throw new AssertionError(
              "path item filter/alias must not be null after assignDefaultAliases");
        }
      }
    }
    return true;
  }

  /**
   * Verifies that both class name parameters are non-null before schema
   * lookup in {@link SQLMatchStatement#getLowerSubclass}.
   *
   * @param className1 the first class name
   * @param className2 the second class name
   * @return always {@code true}
   * @throws AssertionError if either class name is null
   */
  static boolean classNamesNotNull(String className1, String className2) {
    if (className1 == null) {
      throw new AssertionError("className1 must not be null");
    }
    if (className2 == null) {
      throw new AssertionError("className2 must not be null");
    }
    return true;
  }
}
