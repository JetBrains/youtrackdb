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
  public void nullParamsArrayReturnsMinusOneSentinel() {
    // Despite the function declaring a minimum of 2 arguments, the implementation does not
    // throw on a null params array — the for-loop body is never entered and the -1 sentinel
    // returns. Pin the observed behaviour.
    doTest(-1, (Object[]) null);
  }

  @Test
  public void singleArgumentReturnsMinusOneSentinel() {
    // Same observation as the null-params case: with only the comparand and no bounds, the
    // bounds loop is empty and -1 is returned.
    doTest(-1, 53);
  }

  @Test
  public void xBetweenBoundsReturnsIndexOfFirstStrictlyGreaterBound() {
    // x=43 against bounds {35, 5, 15, 50}: first bound strictly greater than 43 is 50 at
    // index 4 of the params array (i=4) → returns i-1 = 3.
    doTest(3, 43, 35, 5, 15, 50);
  }

  @Test
  public void xGreaterThanAllBoundsReturnsMinusOne() {
    // x=54 against bounds {25, 35, 45}: no bound is strictly greater → -1.
    doTest(-1, 54, 25, 35, 45);
  }

  @Test
  public void nullXReturnsMinusOne() {
    // x=null short-circuits before the comparison loop.
    doTest(-1, null, 5, 50);
  }

  @Test
  public void xEqualToSoleBoundReturnsMinusOneBecauseComparisonIsStrict() {
    // strict-> comparison: x=6 against bound 6 does not match → -1.
    doTest(-1, 6, 6);
  }

  @Test
  public void xLessThanFirstBoundReturnsZero() {
    // x=58 against bounds {60, 30, 65}: first bound (60) is strictly greater → returns 0.
    doTest(0, 58, 60, 30, 65);
  }

  @Test
  public void xInFirstIntervalAfterMultipleSmallerBoundsReturnsCorrectIndex() {
    // x=103 against bounds {54, 106, 98, 119}: first strictly-greater bound is 106 at i=2
    // → returns i-1 = 1.
    doTest(1, 103, 54, 106, 98, 119);
  }

  @Test
  public void testAggregateResultsIsAlwaysFalse() {
    // interval is a per-row function — aggregateResults must always be false.
    assertFalse(function.aggregateResults());
  }

  @Test
  public void testGetResultAlwaysReturnsNull() {
    // interval does not buffer state between calls: the contract is carried by the
    // return value of execute(), not getResult() (which always returns null). Assert
    // both to prove the split clearly.
    var executeResult = function.execute(null, null, null, new Object[] {5, 1, 10}, null);
    assertEquals(1, executeResult); // first bound strictly greater than 5 is at index 1 (→ i-1 = 1)
    assertNull(function.getResult());
  }

  @Test
  public void testXEqualToBoundIsNotCountedBecauseComparisonIsStrict() {
    // interval uses strict `>` against the first argument, so a bound equal to x does
    // NOT match — the next strictly-greater bound is returned instead.
    doTest(1, 10, 10, 20);
  }

  @Test(expected = NullPointerException.class)
  public void testNullBoundThrowsBecauseCompareToDereferencesIt() {
    // Pins: null bounds are not supported — the loop reaches the null element (x is
    // larger than the preceding non-null bounds) and `null.compareTo(first)` NPEs.
    // WHEN-FIXED: if future code skips null bounds, update this to assert the
    // expected index instead.
    function.execute(null, null, null, new Object[] {100, 10, null, 200}, null);
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
