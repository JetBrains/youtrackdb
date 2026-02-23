package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link SQLMatchAssertions} helper methods, covering both the success
 * (return true) and failure (throw AssertionError) branches of each check.
 */
public class SQLMatchAssertionsTest {

  // -- allAliasesAssigned tests --

  /**
   * Verifies that allAliasesAssigned returns true when every origin and path-item
   * filter has a non-null alias.
   */
  @Test
  public void testAllAliasesAssignedReturnsTrue() throws ParseException {
    var query = "MATCH {as:a, class:V}.out(){as:b} RETURN a";
    var parser = new YouTrackDBSql(
        new java.io.ByteArrayInputStream(query.getBytes()));
    var stm = (SQLMatchStatement) parser.parse();

    // Assign defaults first so everything has an alias
    SQLMatchStatement.assignDefaultAliases(stm.getMatchExpressions());

    assertTrue(SQLMatchAssertions.allAliasesAssigned(stm.getMatchExpressions()));
  }

  /**
   * Verifies that allAliasesAssigned throws AssertionError when an origin node
   * has a null alias.
   */
  @Test(expected = AssertionError.class)
  public void testAllAliasesAssignedThrowsForNullOriginAlias() {
    var expression = new SQLMatchExpression(-1);
    expression.origin = new SQLMatchFilter(-1);
    // origin has no alias → should fail
    expression.items = new ArrayList<>();

    SQLMatchAssertions.allAliasesAssigned(List.of(expression));
  }

  /**
   * Verifies that allAliasesAssigned throws AssertionError when a path item
   * has a null filter.
   */
  @Test(expected = AssertionError.class)
  public void testAllAliasesAssignedThrowsForNullItemFilter() {
    var expression = new SQLMatchExpression(-1);
    expression.origin = new SQLMatchFilter(-1);
    expression.origin.setAlias("a");
    expression.items = new ArrayList<>();

    var item = new SQLMatchPathItem(-1);
    item.filter = null; // null filter → should fail
    expression.items.add(item);

    SQLMatchAssertions.allAliasesAssigned(List.of(expression));
  }

  // -- classNamesNotNull tests --

  /**
   * Verifies that classNamesNotNull returns true when both class names are non-null.
   */
  @Test
  public void testClassNamesNotNullReturnsTrue() {
    assertTrue(SQLMatchAssertions.classNamesNotNull("Person", "Animal"));
  }

  /**
   * Verifies that classNamesNotNull throws AssertionError when the first class name
   * is null.
   */
  @Test(expected = AssertionError.class)
  public void testClassNamesNotNullThrowsForNullFirst() {
    SQLMatchAssertions.classNamesNotNull(null, "Animal");
  }

  /**
   * Verifies that classNamesNotNull throws AssertionError when the second class name
   * is null.
   */
  @Test(expected = AssertionError.class)
  public void testClassNamesNotNullThrowsForNullSecond() {
    SQLMatchAssertions.classNamesNotNull("Person", null);
  }
}
