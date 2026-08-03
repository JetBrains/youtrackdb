package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Standalone tests for {@link SQLFunctionMean}, the floating-point counterpart of {@link
 * SQLFunctionAverage}.
 *
 * <p>The cases that matter most are the ones that tell the two functions apart, so every numeric
 * fixture below is chosen not to divide evenly: on an evenly-dividing input {@code mean} and
 * {@code avg} agree and the test would pass against either implementation. The rest cover the empty
 * aggregate, null skipping, the multi-value and multi-argument entry shapes, and the {@link
 * BigDecimal} branch that stays in exact arithmetic.
 */
public class SQLFunctionMeanTest {

  private SQLFunctionMean mean;

  @Before
  public void setUp() {
    mean = new SQLFunctionMean();
  }

  @Test
  public void emptyAggregateIsNullRatherThanZero() {
    // A mean over no contributor has no value. Returning 0 would be indistinguishable from a
    // genuine zero mean, and the Gremlin terminator relies on the null to emit no traverser.
    assertNull(mean.getResult());
  }

  @Test
  public void integerInputDividesInFloatingPoint() {
    // The discriminating case against avg: 29 + 27 + 32 + 35 = 123 over 4 contributors. avg
    // answers 30 in integer arithmetic; mean answers 30.75.
    mean.execute(null, null, null, new Object[] {29}, null);
    mean.execute(null, null, null, new Object[] {27}, null);
    mean.execute(null, null, null, new Object[] {32}, null);
    mean.execute(null, null, null, new Object[] {35}, null);

    Object result = mean.getResult();
    assertTrue("Expected Double, got " + result.getClass(), result instanceof Double);
    assertEquals(30.75, (double) result, 1.0e-15);
  }

  @Test
  public void longInputAlsoDividesInFloatingPoint() {
    // Long has its own truncating branch in avg, so it needs its own uneven fixture: 7 / 2 = 3.5.
    mean.execute(null, null, null, new Object[] {3L}, null);
    mean.execute(null, null, null, new Object[] {4L}, null);

    Object result = mean.getResult();
    assertTrue("Expected Double, got " + result.getClass(), result instanceof Double);
    assertEquals(3.5, (double) result, 1.0e-15);
  }

  @Test
  public void mixedIntegerAndLongPromotesThenDividesInFloatingPoint() {
    // PropertyTypeInternal.increment promotes Integer + Long to Long; the division is still
    // floating point, so 1 + 4 over two contributors is 2.5 rather than 2.
    mean.execute(null, null, null, new Object[] {1}, null);
    mean.execute(null, null, null, new Object[] {4L}, null);

    assertEquals(2.5, (double) mean.getResult(), 1.0e-15);
  }

  @Test
  public void doubleInputStaysDouble() {
    mean.execute(null, null, null, new Object[] {1.5}, null);
    mean.execute(null, null, null, new Object[] {2.0}, null);

    assertEquals(1.75, (double) mean.getResult(), 1.0e-15);
  }

  @Test
  public void bigDecimalInputKeepsExactArithmetic() {
    // BigDecimal is the one type that does not go through doubleValue(): it divides under
    // DECIMAL128 so an exact decimal input does not acquire binary floating-point error.
    mean.execute(null, null, null, new Object[] {new BigDecimal("1.00")}, null);
    mean.execute(null, null, null, new Object[] {new BigDecimal("2.00")}, null);

    Object result = mean.getResult();
    assertTrue("Expected BigDecimal, got " + result.getClass(), result instanceof BigDecimal);
    assertEquals(0, new BigDecimal("1.50").compareTo((BigDecimal) result));
  }

  @Test
  public void collectionArgumentIsUnwrappedAndNullsAreSkipped() {
    // A Collection takes the MultiValue branch; the null entry must not count towards the divisor,
    // so this is 2 + 3 over two contributors and not over three.
    List<Integer> row = Arrays.asList(2, null, 3);
    mean.execute(null, null, null, new Object[] {row}, null);

    assertEquals(2.5, (double) mean.getResult(), 1.0e-15);
  }

  @Test
  public void nonNumericArgumentLeavesTheAggregateEmpty() {
    // Neither a Number nor a MultiValue: the aggregate must stay untouched rather than throw.
    mean.execute(null, null, null, new Object[] {"abc"}, null);

    assertNull(mean.getResult());
  }

  @Test
  public void multiArgumentFormIsRowWiseAndResetsBothSumAndCount() {
    // The multi-argument form is a mean of one row's arguments, not a running aggregate, so each
    // call starts over. avg resets only the sum and lets the divisor accumulate across rows, which
    // pollutes every row after the first; mean resets both, so the second row is 60 / 3 = 20 and
    // not 60 / 6 = 10.
    assertEquals(2.0, (double) mean.execute(null, null, null, new Object[] {1, 2, 3}, null), 1e-15);
    assertEquals(
        20.0, (double) mean.execute(null, null, null, new Object[] {10, 20, 30}, null), 1e-15);
  }

  @Test
  public void multiArgumentFormSkipsNullEntries() {
    Object row = mean.execute(null, null, null, new Object[] {null, 2, null, 5}, null);

    assertEquals(3.5, (double) row, 1.0e-15);
  }

  @Test
  public void aggregateResultsIsTrueIffExactlyOneConfiguredParameter() {
    mean.config(new Object[] {"score"});
    assertTrue(mean.aggregateResults());

    mean.config(new Object[] {"a", "b"});
    assertFalse(mean.aggregateResults());
  }

  @Test
  public void getSyntaxAdvertisesFunctionShape() {
    String syntax = mean.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'mean(' prefix: " + syntax, syntax.startsWith("mean("));
  }
}
