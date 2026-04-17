package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link SQLFunctionMedian}. The median is defined as the 50th percentile with no
 * user-supplied quantile — so this test focuses on:
 *
 * <ul>
 *   <li>the empty-aggregate sentinel (null),
 *   <li>odd-sized, even-sized, and single-element streams (each exercises a different branch of
 *       the underlying percentile evaluator),
 *   <li>null values in a multi-value collection being skipped,
 *   <li>the syntax string advertising {@code median(}.
 * </ul>
 *
 * <p>Median delegates to {@link SQLFunctionPercentile} with an implicit {@code 0.5} quantile —
 * the test verifies that users do NOT need to supply a percentile parameter.
 */
public class SQLFunctionMedianTest {

  private SQLFunctionMedian median;

  @Before
  public void setUp() {
    median = new SQLFunctionMedian();
  }

  @Test
  public void emptyInputReturnsNull() {
    assertNull(median.getResult());
  }

  @Test
  public void medianOfSingleValueReturnsThatValue() {
    median.execute(null, null, null, new Object[] {42}, null);
    assertEquals(42, median.getResult());
  }

  @Test
  public void medianOfOddCountReturnsMiddle() {
    // Sorted: 1, 2, 3, 4, 5 → median = 3
    for (int v : new int[] {1, 2, 3, 4, 5}) {
      median.execute(null, null, null, new Object[] {v}, null);
    }
    assertEquals(3.0, median.getResult());
  }

  @Test
  public void medianOfEvenCountInterpolatesBetweenTwoMiddleValues() {
    // Sorted: 1, 2, 4, 5 → percentile evaluator interpolates at pos = 0.5 * (4+1) = 2.5
    // → 2 + 0.5 * (4 - 2) = 3.0
    for (int v : new int[] {1, 2, 4, 5}) {
      median.execute(null, null, null, new Object[] {v}, null);
    }
    assertEquals(3.0, median.getResult());
  }

  @Test
  public void medianOfMultiValueCollectionSkipsNulls() {
    // The underlying addValue() guards against null; nulls inside a collection must be skipped.
    var row = Arrays.asList(null, 1, null, 2, 3, 4, 5);
    median.execute(null, null, null, new Object[] {row}, null);
    assertEquals(3.0, median.getResult());
  }

  @Test
  public void medianIsDirtiedAcrossMultipleRows() {
    // Sort key: percentile stores values into an internal list, and each execute() appends.
    median.execute(null, null, null, new Object[] {10}, null);
    median.execute(null, null, null, new Object[] {20}, null);
    median.execute(null, null, null, new Object[] {30}, null);
    // Even pos = 0.5 * (3+1) = 2.0 — falls into the pos >= n branch only if 2.0 >= 3 (it doesn't).
    // Interpolation: floor(2.0) = 2, dif = 0 → values[1] + 0 * (values[2] - values[1]) = 20.
    assertEquals(20.0, median.getResult());
  }

  @Test
  public void getSyntaxAdvertisesFunctionShape() {
    var syntax = median.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'median(' prefix, got: " + syntax, syntax.startsWith("median("));
  }

  @Test
  public void aggregateResultsIsTrue() {
    // Median inherits from Percentile, which always aggregates.
    assertTrue(median.aggregateResults());
  }
}
