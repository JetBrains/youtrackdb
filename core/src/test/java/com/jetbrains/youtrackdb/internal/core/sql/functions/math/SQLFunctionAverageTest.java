package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Standalone tests for {@link SQLFunctionAverage}. Covers:
 *
 * <ul>
 *   <li>Empty / null-only input returns {@code null} (division by zero sentinel),
 *   <li>Single-arg execute with a plain Number vs. a multi-value collection,
 *   <li>Multi-arg execute resetting the running sum on every row,
 *   <li>Per-type division in {@code computeAverage}: Integer/Long/Float/Double/BigDecimal,
 *   <li>Null values skipped by the {@code sum(Number)} guard,
 *   <li>{@code aggregateResults} contract (true only when exactly one configured parameter),
 *   <li>{@code getSyntax} advertises the function name.
 * </ul>
 */
public class SQLFunctionAverageTest {

  private SQLFunctionAverage avg;

  @Before
  public void setUp() {
    avg = new SQLFunctionAverage();
  }

  @Test
  public void getResultOnEmptyReturnsNullBecauseTotalIsZero() {
    // computeAverage returns null when sum is null → average of an empty aggregate is null,
    // not 0 — tests that divide-by-zero is not attempted.
    assertNull(avg.getResult());
  }

  @Test
  public void averageOfThreeIntegersTruncatesDivision() {
    avg.execute(null, null, null, new Object[] {1}, null);
    avg.execute(null, null, null, new Object[] {2}, null);
    avg.execute(null, null, null, new Object[] {4}, null);
    // (1 + 2 + 4) = 7, 7/3 → 2 (integer truncation, not 2.33)
    assertEquals(2, avg.getResult());
  }

  @Test
  public void averageOfLongsReturnsLong() {
    avg.execute(null, null, null, new Object[] {10L}, null);
    avg.execute(null, null, null, new Object[] {20L}, null);
    Object result = avg.getResult();
    assertTrue(result instanceof Long);
    assertEquals(15L, result);
  }

  @Test
  public void mixedIntegerLongResultFollowsPrecisionPromotion() {
    // PropertyTypeInternal.increment promotes Integer + Long → Long;
    // computeAverage then picks the Long branch.
    avg.execute(null, null, null, new Object[] {1}, null);
    avg.execute(null, null, null, new Object[] {3L}, null);
    Object result = avg.getResult();
    assertTrue("Expected Long, got " + result.getClass(), result instanceof Long);
    assertEquals(2L, result);
  }

  @Test
  public void averageOfFloatsReturnsFloat() {
    avg.execute(null, null, null, new Object[] {1.0f}, null);
    avg.execute(null, null, null, new Object[] {3.0f}, null);
    Object result = avg.getResult();
    assertTrue(result instanceof Float);
    assertEquals(2.0f, (float) result, 0.0001f);
  }

  @Test
  public void averageOfDoublesReturnsDouble() {
    avg.execute(null, null, null, new Object[] {1.5}, null);
    avg.execute(null, null, null, new Object[] {2.5}, null);
    Object result = avg.getResult();
    assertTrue(result instanceof Double);
    assertEquals(2.0, (double) result, 0.0001);
  }

  @Test
  public void averageOfBigDecimalsUsesHalfUpRounding() {
    avg.execute(null, null, null, new Object[] {new BigDecimal("1.0")}, null);
    avg.execute(null, null, null, new Object[] {new BigDecimal("2.0")}, null);
    avg.execute(null, null, null, new Object[] {new BigDecimal("4.0")}, null);
    Object result = avg.getResult();
    assertTrue(result instanceof BigDecimal);
    // sum 7.0, scale 1 / 3 with HALF_UP → 2.3
    BigDecimal expected = new BigDecimal("7.0").divide(new BigDecimal(3), RoundingMode.HALF_UP);
    assertEquals(0, expected.compareTo((BigDecimal) result));
  }

  @Test
  public void singleArgExecuteUnwrapsCollectionAndIgnoresNulls() {
    // A Collection takes the MultiValue branch; null entries are skipped by sum()'s null guard.
    List<Integer> row = Arrays.asList(2, 4, null, 6);
    avg.execute(null, null, null, new Object[] {row}, null);
    // (2+4+6)/3 = 4
    assertEquals(4, avg.getResult());
  }

  @Test
  public void singleArgExecuteWithNonNumberAndNonMultiValueIsIgnored() {
    // Passing a String (neither Number nor MultiValue) must not update the aggregate —
    // getResult() stays at the empty sentinel null.
    avg.execute(null, null, null, new Object[] {"abc"}, null);
    assertNull(avg.getResult());
  }

  @Test
  public void multiArgExecuteResetsSumButNotTotalBetweenCalls() {
    // Multi-arg branch resets `sum = null` before iterating each call, but `total` is
    // never reset — it accumulates across all execute() calls. This is the observed
    // behavior; callers of the multi-arg form should therefore call getResult() within a
    // single row's execute() (which does: sum returned; getResult is called once per row).
    // First call: total=3, sum=6 → 6/3 = 2.
    // Second call: total=6, sum=60 (reset then re-summed) → 60/6 = 10 (NOT 20).
    Object row1 = avg.execute(null, null, null, new Object[] {1, 2, 3}, null);
    assertEquals(2, row1);
    Object row2 = avg.execute(null, null, null, new Object[] {10, 20, 30}, null);
    assertEquals(10, row2);
  }

  @Test
  public void multiArgExecuteSkipsNullEntries() {
    // Multi-arg execute iterates via sum(Number), which skips nulls. Running average
    // only counts non-null values: total=2, sum=6 → 6/2 = 3.
    Object row = avg.execute(null, null, null, new Object[] {null, 2, null, 4}, null);
    assertEquals(3, row);
  }

  @Test
  public void aggregateResultsIsTrueIffExactlyOneConfiguredParameter() {
    avg.config(new Object[] {"score"});
    assertTrue(avg.aggregateResults());

    avg.config(new Object[] {"a", "b"});
    assertFalse(avg.aggregateResults());
  }

  @Test
  public void getSyntaxAdvertisesFunctionShape() {
    String syntax = avg.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'avg(' prefix: " + syntax, syntax.startsWith("avg("));
  }
}
