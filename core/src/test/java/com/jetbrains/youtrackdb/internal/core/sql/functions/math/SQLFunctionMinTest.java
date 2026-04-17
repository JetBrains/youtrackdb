package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Standalone tests for {@link SQLFunctionMin}. Mirrors the {@link SQLFunctionMax} test to catch
 * symmetry bugs (copy-paste errors between max/min are a common pitfall). Covers:
 *
 * <ul>
 *   <li>aggregate path (single configured param) vs. per-row path (multiple params),
 *   <li>Collection parameter scanned for the internal minimum,
 *   <li>cross-type numeric promotion via {@code castComparableNumber},
 *   <li>null handling in collections and aggregate mode,
 *   <li>Date comparisons (min delegates to Comparable, not Number),
 *   <li>the {@code $current} LET-definition guard,
 *   <li>{@code getSyntax} advertising the function name.
 * </ul>
 */
public class SQLFunctionMinTest {

  private SQLFunctionMin min;

  @Before
  public void setUp() {
    min = new SQLFunctionMin();
  }

  @Test
  public void getResultOnEmptyReturnsNull() {
    assertNull(min.getResult());
  }

  @Test
  public void aggregateAcrossMultipleRowsKeepsSmallest() {
    // One configured parameter → aggregateResults() == true.
    // execute() must return null per row and expose the running min via getResult().
    min.config(new Object[] {"score"});
    assertNull(min.execute(null, null, null, new Object[] {3}, null));
    assertNull(min.execute(null, null, null, new Object[] {7}, null));
    assertNull(min.execute(null, null, null, new Object[] {2}, null));
    assertEquals(2, min.getResult());
  }

  @Test
  public void aggregateWithIntegerThenLongPromotesContext() {
    min.config(new Object[] {"score"});
    min.execute(null, null, null, new Object[] {20}, null);
    min.execute(null, null, null, new Object[] {10L}, null);
    Object result = min.getResult();
    assertEquals(10L, result);
  }

  @Test
  public void aggregateWithAllNullsLeavesResultNull() {
    min.config(new Object[] {"score"});
    min.execute(null, null, null, new Object[] {(Object) null}, null);
    min.execute(null, null, null, new Object[] {(Object) null}, null);
    assertNull(min.getResult());
  }

  @Test
  public void perRowModeReturnsMinForThatRowWithoutAggregating() {
    // configuredParameters.length != 1 → aggregateResults() returns false.
    min.config(new Object[] {"a", "b", "c"});
    assertFalse(min.aggregateResults());
    Object row1 = min.execute(null, null, null, new Object[] {3, 7, 5}, null);
    assertEquals(3, row1);
    Object row2 = min.execute(null, null, null, new Object[] {10, 1}, null);
    assertEquals(1, row2);
    // Per-row mode: getResult() stays null because context was never set.
    assertNull(min.getResult());
  }

  @Test
  public void collectionParamIsScannedForInternalMinimum() {
    // aggregateResults() reads configuredParameters.length, so config() is mandatory.
    min.config(new Object[] {"scores"});
    min.execute(null, null, null, new Object[] {Arrays.asList(2, 9, 4)}, null);
    assertEquals(2, min.getResult());
  }

  @Test
  public void collectionWithNullsTakesMinIgnoringNulls() {
    min.config(new Object[] {"scores"});
    min.execute(null, null, null, new Object[] {Arrays.asList(3, null, 5, null, 1)}, null);
    assertEquals(1, min.getResult());
  }

  @Test
  public void datesAreCompared() {
    // Configured as two-parameter form so the per-row branch is taken.
    min.config(new Object[] {"a", "b"});
    Date early = new Date(1000L);
    Date late = new Date(5000L);
    Object result = min.execute(null, null, null, new Object[] {early, late}, null);
    assertEquals(early, result);
  }

  @Test
  public void emptyCollectionReturnsNull() {
    min.config(new Object[] {"scores"});
    min.execute(null, null, null, new Object[] {Collections.emptyList()}, null);
    assertNull(min.getResult());
  }

  @Test
  public void letDefinitionWithCurrentReferenceDisablesAggregation() {
    min.config(new Object[] {"$current.score"});
    assertFalse(min.aggregateResults());
  }

  @Test
  public void aggregateWithCollectionInputStillTracksGlobalMinimum() {
    min.config(new Object[] {"scores"});
    List<Integer> row1 = Arrays.asList(3, 7, 5);
    List<Integer> row2 = Arrays.asList(1, 8, 2);
    min.execute(null, null, null, new Object[] {row1}, null);
    min.execute(null, null, null, new Object[] {row2}, null);
    assertEquals(1, min.getResult());
  }

  @Test
  public void getSyntaxAdvertisesFunctionShape() {
    String syntax = min.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'min(' prefix: " + syntax, syntax.startsWith("min("));
  }
}
