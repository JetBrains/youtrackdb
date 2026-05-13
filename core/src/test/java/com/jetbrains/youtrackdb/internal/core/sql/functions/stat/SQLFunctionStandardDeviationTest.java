package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link SQLFunctionStandardDeviation}. stddev is implemented as {@code sqrt(variance)}:
 *
 * <ul>
 *   <li>empty/single-value input → {@code null} (because variance is null for n ≤ 1),
 *   <li>multi-value input → {@code sqrt(Welford-variance)},
 *   <li>{@code getSyntax} advertises {@code stddev(},
 *   <li>{@code aggregateResults} is {@code true} (inherited from variance).
 * </ul>
 */
public class SQLFunctionStandardDeviationTest {

  private SQLFunctionStandardDeviation stddev;

  @Before
  public void setUp() {
    stddev = new SQLFunctionStandardDeviation();
  }

  @Test
  public void emptyInputReturnsNull() {
    // Variance is null when n <= 1 → sqrt(null) branch returns null.
    // Directly covers the `variance == null` guard in evaluate().
    assertNull(stddev.getResult());
  }

  @Test
  public void singleValueAloneReturnsNullBecauseVarianceIsUndefined() {
    stddev.execute(null, null, null, new Object[] {5}, null);
    assertNull("Variance of 1 sample is undefined → stddev must also be null",
        stddev.getResult());
  }

  @Test
  public void standardDeviationOfKnownSampleMatchesSqrtOfVariance() {
    // Matches SQLFunctionVarianceTest.testVariance: values {4, 7, 15, 3} → variance 22.1875.
    // stddev = sqrt(22.1875) ≈ 4.7103077...
    Integer[] scores = {4, 7, 15, 3};
    for (Integer s : scores) {
      stddev.execute(null, null, null, new Object[] {s}, null);
    }
    Object result = stddev.getResult();
    assertTrue("Expected Double, got " + result.getClass(), result instanceof Double);
    assertEquals(Math.sqrt(22.1875), (double) result, 1e-12);
  }

  @Test
  public void standardDeviationOfTwoSamplesIsFiniteAndPositive() {
    // Two samples {4, 7} → variance = 2.25 → stddev = 1.5
    stddev.execute(null, null, null, new Object[] {4}, null);
    stddev.execute(null, null, null, new Object[] {7}, null);
    Object result = stddev.getResult();
    assertEquals(Math.sqrt(2.25), (double) result, 1e-12);
  }

  @Test
  public void getSyntaxAdvertisesFunctionShape() {
    var syntax = stddev.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'stddev(' prefix, got: " + syntax, syntax.startsWith("stddev("));
  }

  @Test
  public void aggregateResultsIsTrue() {
    assertTrue(stddev.aggregateResults());
  }
}
