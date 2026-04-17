package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionInterval}. {@code interval(x, b1, b2, ..., bN)} returns the index
 * (0-based, relative to the bounds starting at position 1) of the first bound strictly greater
 * than {@code x}, or {@code -1} when no such bound exists or the first argument is null.
 */
public class SQLFunctionIntervalTest {

  private SQLFunctionInterval function;

  @Before
  public void setup() {
    function = new SQLFunctionInterval();
  }

  @Test
  public void testNull() {
    // should throw exception - minimum 2 arguments
    doTest(-1, (Object[]) null);
  }

  @Test
  public void testSingleArgument() {
    // should throw exception - minimum 2
    doTest(-1, 53);
  }

  @Test
  public void testMultiple() {
    doTest(3, 43, 35, 5, 15, 50);
    doTest(-1, 54, 25, 35, 45);
    doTest(-1, null, 5, 50);
    doTest(-1, 6, 6);
    doTest(0, 58, 60, 30, 65);
    doTest(1, 103, 54, 106, 98, 119);
  }

  @Test
  public void testAggregateResultsIsAlwaysFalse() {
    // interval is a per-row function — aggregateResults must always be false.
    assertFalse(function.aggregateResults());
  }

  @Test
  public void testGetResultAlwaysReturnsNull() {
    // interval does not buffer state between calls; getResult() must return null
    // even after execute() has been invoked.
    function.execute(null, null, null, new Object[] {5, 1, 10}, null);
    assertNull(function.getResult());
  }

  @Test
  public void testGetSyntaxAdvertisesFunctionShape() {
    final var syntax = function.getSyntax(null);
    assertTrue("Expected 'interval(' prefix, got: " + syntax, syntax.startsWith("interval("));
  }

  private void doTest(int expectedResult, Object... params) {
    final var result = function.execute(null, null, null, params, null);
    assertEquals(expectedResult, result);
  }
}
