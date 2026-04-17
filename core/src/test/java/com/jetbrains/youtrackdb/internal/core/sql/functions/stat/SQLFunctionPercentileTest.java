package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

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
