package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Standalone tests for {@link SQLFunctionSum}. Verifies:
 *
 * <ul>
 *   <li>Zero-sum default (empty stream returns {@code 0}, not {@code null}),
 *   <li>Single-arg execute with a plain {@link Number} vs. a multi-value param,
 *   <li>Multi-arg execute resetting the accumulator on every call,
 *   <li>Type promotion via {@code PropertyTypeInternal.increment} (Integer+Long→Long,
 *       Integer+BigDecimal→BigDecimal, Integer overflow→Long),
 *   <li>Null handling (null in the parameters / null inside a collection both skipped),
 *   <li>{@code getSyntax} and {@code aggregateResults} contract (aggregates only
 *       when exactly one configured parameter).
 * </ul>
 */
public class SQLFunctionSumTest {

  private SQLFunctionSum sum;

  @Before
  public void setUp() {
    sum = new SQLFunctionSum();
  }

  @Test
  public void getResultReturnsZeroWhenNoValueWasObserved() {
    // Zero (int literal) is used as a sentinel for "empty aggregate" rather than null
    assertEquals(0, sum.getResult());
  }

  @Test
  public void singleArgExecuteAccumulatesNumbers() {
    sum.execute(null, null, null, new Object[] {1}, null);
    sum.execute(null, null, null, new Object[] {2}, null);
    sum.execute(null, null, null, new Object[] {3}, null);
    assertEquals(6, sum.getResult());
  }

  @Test
  public void singleArgExecuteIgnoresNonNumberNonMultiValue() {
    // A String is neither Number nor a MultiValue: execute() must keep sum null,
    // so getResult() falls through to the zero default.
    sum.execute(null, null, null, new Object[] {"not a number"}, null);
    assertEquals(0, sum.getResult());
  }

  @Test
  public void singleArgExecuteWithNullValueIsIgnored() {
    // Null inside the single-param branch must not increment the accumulator.
    sum.execute(null, null, null, new Object[] {5}, null);
    sum.execute(null, null, null, new Object[] {(Object) null}, null);
    assertEquals(5, sum.getResult());
  }

  @Test
  public void singleArgExecuteUnwrapsCollectionAndSkipsNulls() {
    // A Collection is unwrapped via MultiValue.getMultiValueIterable(...);
    // null entries within the collection must be skipped (guard in sum()).
    List<Integer> scores = Arrays.asList(1, 2, null, 3);
    sum.execute(null, null, null, new Object[] {scores}, null);
    assertEquals(6, sum.getResult());
  }

  @Test
  public void multiArgExecuteResetsAndSumsCurrentRow() {
    // Multi-arg branch resets sum to null at the start, then sums every non-null param.
    // First call yields 6, then a second call must reset and produce 15 (not 21).
    Object firstRow = sum.execute(null, null, null, new Object[] {1, 2, 3}, null);
    assertEquals(6, firstRow);
    Object secondRow = sum.execute(null, null, null, new Object[] {4, 5, 6}, null);
    assertEquals(15, secondRow);
  }

  @Test
  public void multiArgExecuteWithLeadingNullThenNumberPromotesCorrectly() {
    // Multi-arg branch iterates every element with a null-safe guard in sum().
    Object result = sum.execute(null, null, null, new Object[] {null, 7, null, 3}, null);
    assertEquals(10, result);
  }

  @Test
  public void integerOverflowPromotesResultTypeToLongButLosesValue() {
    // PropertyTypeInternal.increment detects Integer+Integer overflow (sum < 0 with both
    // operands positive) and RETURNS the result cast to Long — however the cast happens on
    // the already-overflowed int, so the Long carries the wrong value (Integer.MIN_VALUE).
    // Pinning test: Long *type* promotion happens, but the value is buggy.
    //
    // WHEN-FIXED: if PropertyTypeInternal.increment is fixed to widen operands before
    // adding, update this assertion to expect `(long) Integer.MAX_VALUE + 1L`.
    sum.execute(null, null, null, new Object[] {Integer.MAX_VALUE}, null);
    sum.execute(null, null, null, new Object[] {1}, null);
    Object result = sum.getResult();
    assertTrue("Expected Long promotion on overflow, got " + result.getClass(),
        result instanceof Long);
    // WHEN-FIXED: PropertyTypeInternal.increment Integer-overflow promotion —
    // the cast `(long) (a.intValue() + b.intValue())` applies to an already-overflowed
    // int. Once fixed (widen operands first), change expectation to
    // `(long) Integer.MAX_VALUE + 1L`.
    assertEquals((long) Integer.MIN_VALUE, result);
  }

  @Test
  public void mixedIntegerLongPromotesToLong() {
    sum.execute(null, null, null, new Object[] {10}, null);
    sum.execute(null, null, null, new Object[] {20L}, null);
    Object result = sum.getResult();
    assertTrue(result instanceof Long);
    assertEquals(30L, result);
  }

  @Test
  public void bigDecimalOperandDrivesBigDecimalResult() {
    sum.execute(null, null, null, new Object[] {new BigDecimal("1.5")}, null);
    sum.execute(null, null, null, new Object[] {new BigDecimal("2.25")}, null);
    Object result = sum.getResult();
    assertTrue(result instanceof BigDecimal);
    assertEquals(0, new BigDecimal("3.75").compareTo((BigDecimal) result));
  }

  @Test
  public void aggregateResultsDependsOnConfiguredParameterCount() {
    // configuredParameters is sourced from config() — calling aggregateResults() before
    // config() is a caller error (would NPE on `configuredParameters.length`). Once
    // configured, aggregateResults() reflects the parameter count.
    sum.config(new Object[] {"field"});
    assertTrue(sum.aggregateResults());
    sum.config(new Object[] {"fieldA", "fieldB"});
    assertFalse(sum.aggregateResults());
  }

  @Test
  public void shortOverflowWithinSumPromotesToInteger() {
    // Short.MAX_VALUE + (short) 1 overflows short arithmetic → increment widens to Integer.
    sum.execute(null, null, null, new Object[] {Short.MAX_VALUE}, null);
    sum.execute(null, null, null, new Object[] {(short) 1}, null);
    Object result = sum.getResult();
    assertTrue("Expected Integer promotion after short overflow, got " + result.getClass(),
        result instanceof Integer);
    assertEquals(((int) Short.MAX_VALUE) + 1, result);
  }

  @Test
  public void emptyMultiValueCollectionPreservesZeroSentinel() {
    // Empty collection → sum() never called → sum stays null → getResult returns 0.
    sum.execute(null, null, null, new Object[] {Collections.emptyList()}, null);
    assertEquals(0, sum.getResult());
  }

  @Test
  public void getSyntaxAdvertisesFunctionShape() {
    String syntax = sum.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Syntax should mention 'sum': " + syntax, syntax.startsWith("sum("));
  }
}
