package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

public class SQLFunctionVarianceTest {

  private SQLFunctionVariance variance;

  @Before
  public void setup() {
    variance =
        new SQLFunctionVariance() {
        };
  }

  @Test
  public void testEmpty() {
    var result = variance.getResult();
    assertNull(result);
  }

  @Test
  public void testVariance() {
    Integer[] scores = {4, 7, 15, 3};

    for (var s : scores) {
      variance.execute(null, null, null, new Object[] {s}, null);
    }

    var result = variance.getResult();
    assertEquals(22.1875, result);
  }

  @Test
  public void testVariance1() {
    Integer[] scores = {4, 7};

    for (var s : scores) {
      variance.execute(null, null, null, new Object[] {s}, null);
    }

    var result = variance.getResult();
    assertEquals(2.25, result);
  }

  @Test
  public void testVariance2() {
    Integer[] scores = {15, 3};

    for (var s : scores) {
      variance.execute(null, null, null, new Object[] {s}, null);
    }

    var result = variance.getResult();
    assertEquals(36.0, result);
  }

  @Test
  public void testMultiValueInputIsUnwrapped() {
    // A Collection parameter takes the MultiValue branch in execute(); null elements are
    // skipped by the addValue() guard. Verifies both the branch and the null filter.
    var samples = Arrays.asList(4, 7, null, 15, 3, null);
    variance.execute(null, null, null, new Object[] {samples}, null);
    // Same underlying data set as testVariance → same population variance 22.1875.
    assertEquals(22.1875, variance.getResult());
  }

  @Test
  public void testEmptyMultiValueInputLeavesResultNull() {
    // Empty collection → addValue() never called → n stays 0 → variance stays null.
    variance.execute(null, null, null, new Object[] {Collections.emptyList()}, null);
    assertNull(variance.getResult());
  }

  @Test
  public void testNonNumberNonMultiValueInputIsIgnored() {
    // A String is neither Number nor MultiValue → execute() silently ignores it.
    // Without any numbers, variance stays null.
    variance.execute(null, null, null, new Object[] {"not a number"}, null);
    assertNull(variance.getResult());
  }

  @Test
  public void testAggregateResultsIsAlwaysTrue() {
    assertTrue(variance.aggregateResults());
  }

  @Test
  public void testGetSyntaxAdvertisesFunctionShape() {
    var syntax = variance.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'variance(' prefix, got: " + syntax, syntax.startsWith("variance("));
  }
}
