package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionPercentile} — accumulates samples and at evaluation time returns
 * the requested quantile(s) with linear interpolation between the bracketing values.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Empty accumulator → null
 *   <li>{@code n == 1}: lower / upper short-circuit returns the only value
 *   <li>{@code n >= 2}: 50th percentile odd / even, first / third quartiles (interpolation),
 *       multi-quantile request returning a List
 *   <li>{@code pos >= n} for {@code n >= 2}: quantile near 1.0 clamps to last element (the
 *       upper short-circuit at the boundary, not just for n==1)
 *   <li>Number / MultiValue / non-Number input dispatch; null elements skipped
 *   <li>aggregateResults() is true; getSyntax() prefix
 * </ul>
 */
public class SQLFunctionPercentileTest {

  private SQLFunctionPercentile percentile;

  @Before
  public void beforeMethod() {
    percentile =
        new SQLFunctionPercentile();
  }

  @Test
  public void testEmpty() {
    var result = percentile.getResult();
    assertNull(result);
  }

  @Test
  public void testSingleValueLower() {
    percentile.execute(null, null, null, new Object[] {10, .25}, null);
    assertEquals(10, percentile.getResult());
  }

  @Test
  public void testSingleValueUpper() {
    percentile.execute(null, null, null, new Object[] {10, .75}, null);
    assertEquals(10, percentile.getResult());
  }

  @Test
  public void test50thPercentileOdd() {
    var scores = new int[] {1, 2, 3, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[] {s, .5}, null);
    }

    var result = percentile.getResult();
    assertEquals(3.0, result);
  }

  @Test
  public void test50thPercentileOddWithNulls() {
    Integer[] scores = {null, 1, 2, null, 3, 4, null, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[] {s, .5}, null);
    }

    var result = percentile.getResult();
    assertEquals(3.0, result);
  }

  @Test
  public void test50thPercentileEven() {
    var scores = new int[] {1, 2, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[] {s, .5}, null);
    }

    var result = percentile.getResult();
    assertEquals(3.0, result);
  }

  @Test
  public void testFirstQuartile() {
    var scores = new int[] {1, 2, 3, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[] {s, .25}, null);
    }

    var result = percentile.getResult();
    assertEquals(1.5, result);
  }

  @Test
  public void testThirdQuartile() {
    var scores = new int[] {1, 2, 3, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[] {s, .75}, null);
    }

    var result = percentile.getResult();
    assertEquals(4.5, result);
  }

  @Test
  public void quantileNearOneClampsToLastElementForNonTrivialList() {
    // Pos = 0.99 * (4+1) = 4.95, which is >= n (4) → exercises the upper short-circuit
    // branch for a non-trivial list. The existing testSingleValueUpper only hits this
    // boundary for n==1, where every quantile collapses trivially. A refactor to
    // `pos > n` would silently break large-quantile edge cases but pass every other test.
    var scores = new int[] {10, 20, 30, 40};
    for (var s : scores) {
      percentile.execute(null, null, null, new Object[] {s, .99}, null);
    }
    assertEquals(40, percentile.getResult());
  }

  @Test
  public void testMultiQuartile() {
    var scores = new int[] {1, 2, 3, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[] {s, .25, .75}, null);
    }

    var result = (List<Number>) percentile.getResult();
    assertEquals(1.5, result.get(0).doubleValue(), 0);
    assertEquals(4.5, result.get(1).doubleValue(), 0);
  }

  @Test
  public void testMultiValueInputIsUnwrapped() {
    // A Collection parameter takes the MultiValue branch; nulls inside must be skipped.
    var row = Arrays.asList(null, 1, 2, null, 3, 4, 5);
    percentile.execute(null, null, null, new Object[] {row, .5}, null);
    assertEquals(3.0, percentile.getResult());
  }

  @Test
  public void testNonNumberNonMultiValueInputIsIgnored() {
    // A String input hits the non-Number, non-MultiValue path: quantile gets set but no
    // values are buffered, so getResult() returns null (empty-values sentinel).
    percentile.execute(null, null, null, new Object[] {"not a number", .5}, null);
    assertNull(percentile.getResult());
  }

  @Test
  public void testEmptyMultiValueInputWithMultipleQuantilesReturnsNull() {
    // Forces the interplay between the "quantiles get set once" code (line 60 of the
    // source) and the values.isEmpty() early-return — quantiles is sized 2 but no
    // values are buffered. Guards against a future refactor that moves the empty-check
    // inside the `quantiles.size() > 1` branch.
    percentile.execute(null, null, null,
        new Object[] {Collections.emptyList(), .25, .75}, null);
    assertNull(percentile.getResult());
  }

  @Test
  public void testAggregateResultsIsAlwaysTrue() {
    assertTrue(percentile.aggregateResults());
  }

  @Test
  public void testGetSyntaxAdvertisesFunctionShape() {
    var syntax = percentile.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'percentile(' prefix, got: " + syntax,
        syntax.startsWith("percentile("));
  }
}
