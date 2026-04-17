package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionVariance} — Welford-style online population variance accumulator.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code n == 0} (no samples) → null
 *   <li>{@code n == 1} (single sample) → null (variance undefined; pins the strict {@code n > 1}
 *       boundary that distinguishes "undefined" from "zero variance of identical samples")
 *   <li>{@code n >= 2} → population variance (m2 / n)
 *   <li>Number / MultiValue / non-Number-non-MultiValue input dispatch
 *   <li>Empty MultiValue → result stays null
 *   <li>aggregateResults() is true; getSyntax() prefix
 * </ul>
 */
public class SQLFunctionVarianceTest {

  private SQLFunctionVariance variance;

  @Before
  public void setup() {
    variance =
        new SQLFunctionVariance() {
        };
  }

  @Test
  public void emptyAccumulatorResultIsNull() {
    var result = variance.getResult();
    assertNull(result);
  }

  @Test
  public void singleValueReturnsNullBecauseVarianceIsUndefined() {
    // Pins the n > 1 strict-greater boundary in evaluator(): a single sample yields m2 == 0,
    // so a refactor to n >= 1 would silently return 0.0 — semantically wrong, since variance
    // of one sample is undefined, not zero.
    variance.execute(null, null, null, new Object[] {42}, null);
    assertNull(variance.getResult());
  }

  @Test
  public void varianceOfFourMixedIntegersMatchesHandComputedValue() {
    // Samples {4, 7, 15, 3}; mean = 7.25; population variance = ((4-7.25)^2 + (7-7.25)^2 +
    // (15-7.25)^2 + (3-7.25)^2) / 4 = 22.1875.
    Integer[] scores = {4, 7, 15, 3};

    for (var s : scores) {
      variance.execute(null, null, null, new Object[] {s}, null);
    }

    var result = variance.getResult();
    assertEquals(22.1875, result);
  }

  @Test
  public void varianceOfTwoCloseIntegersIsSmall() {
    // Samples {4, 7}; mean = 5.5; population variance = ((4-5.5)^2 + (7-5.5)^2) / 2 = 2.25.
    Integer[] scores = {4, 7};

    for (var s : scores) {
      variance.execute(null, null, null, new Object[] {s}, null);
    }

    var result = variance.getResult();
    assertEquals(2.25, result);
  }

  @Test
  public void varianceOfTwoWidelySpacedIntegersIsLarge() {
    // Samples {15, 3}; mean = 9; population variance = ((15-9)^2 + (3-9)^2) / 2 = 36.
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
