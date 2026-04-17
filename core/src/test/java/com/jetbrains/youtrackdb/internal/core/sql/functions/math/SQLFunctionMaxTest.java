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
 * Standalone tests for {@link SQLFunctionMax}. Exercises:
 *
 * <ul>
 *   <li>aggregate path (single configured param) vs. per-row path (multiple params),
 *   <li>Collection parameters (iterated with a compareTo scan),
 *   <li>cross-type numeric promotion through {@code castComparableNumber},
 *   <li>null-input handling (aggregate-accumulator stays null, per-row returns null),
 *   <li>Date comparison, showing that Max works for any {@link Comparable}, not only numbers,
 *   <li>the LET-definition guard that disables aggregation when the configured parameter
 *       references {@code $current},
 *   <li>{@code getSyntax} advertising the function name.
 * </ul>
 */
public class SQLFunctionMaxTest {

  private SQLFunctionMax max;

  @Before
  public void setUp() {
    max = new SQLFunctionMax();
  }

  @Test
  public void getResultOnEmptyReturnsNull() {
    assertNull(max.getResult());
  }

  @Test
  public void aggregateAcrossMultipleRowsKeepsBiggest() {
    // One configured parameter → aggregateResults() == true.
    // execute() must return null per row and expose the running max via getResult().
    max.config(new Object[] {"score"});
    assertNull(max.execute(null, null, null, new Object[] {3}, null));
    assertNull(max.execute(null, null, null, new Object[] {7}, null));
    assertNull(max.execute(null, null, null, new Object[] {5}, null));
    assertEquals(7, max.getResult());
  }

  @Test
  public void aggregateWithIntegerThenLongPromotesContext() {
    // castComparableNumber promotes Integer + Long → Long comparison.
    max.config(new Object[] {"score"});
    max.execute(null, null, null, new Object[] {10}, null);
    max.execute(null, null, null, new Object[] {20L}, null);
    Object result = max.getResult();
    assertEquals(20L, result);
  }

  @Test
  public void aggregateWithAllNullsLeavesResultNull() {
    // Null row must not seed the context; result stays null.
    max.config(new Object[] {"score"});
    max.execute(null, null, null, new Object[] {(Object) null}, null);
    max.execute(null, null, null, new Object[] {(Object) null}, null);
    assertNull(max.getResult());
  }

  @Test
  public void perRowModeReturnsMaxForThatRowWithoutAggregating() {
    // No config() call → configuredParameters.length != 1 → aggregateResults() returns false.
    // Every execute() must return the row-local maximum directly.
    max.config(new Object[] {"a", "b", "c"});
    assertFalse(max.aggregateResults());
    Object row1 = max.execute(null, null, null, new Object[] {3, 7, 5}, null);
    assertEquals(7, row1);
    Object row2 = max.execute(null, null, null, new Object[] {10, 1}, null);
    assertEquals(10, row2);
    // Per-row mode: getResult() stays null because context was never set.
    assertNull(max.getResult());
  }

  @Test
  public void collectionParamIsScannedForInternalMaximum() {
    // aggregateResults() reads configuredParameters.length, so config() is mandatory.
    // A Collection parameter is walked element-by-element, not compared as a whole.
    max.config(new Object[] {"scores"});
    max.execute(null, null, null, new Object[] {Arrays.asList(2, 9, 4)}, null);
    assertEquals(9, max.getResult());
  }

  @Test
  public void collectionWithNullsTakesMaxIgnoringNulls() {
    // compareTo would NPE on a null element; the loop guards item != null.
    max.config(new Object[] {"scores"});
    max.execute(null, null, null, new Object[] {Arrays.asList(3, null, 5, null, 1)}, null);
    assertEquals(5, max.getResult());
  }

  @Test
  public void datesAreCompared() {
    // Configured as two-parameter form so the per-row branch is taken.
    max.config(new Object[] {"a", "b"});
    Date early = new Date(1000L);
    Date late = new Date(5000L);
    Object result = max.execute(null, null, null, new Object[] {early, late}, null);
    assertEquals(late, result);
  }

  @Test
  public void emptyCollectionReturnsNull() {
    max.config(new Object[] {"scores"});
    max.execute(null, null, null, new Object[] {Collections.emptyList()}, null);
    assertNull(max.getResult());
  }

  @Test
  public void letDefinitionWithCurrentReferenceDisablesAggregation() {
    // LET definitions (configured parameter containing `$current`) must NOT aggregate —
    // per-row results should be returned directly, not buffered.
    max.config(new Object[] {"$current.score"});
    assertFalse(max.aggregateResults());
  }

  @Test
  public void aggregateWithCollectionInputStillTracksGlobalMaximum() {
    max.config(new Object[] {"scores"});
    List<Integer> row1 = Arrays.asList(3, 7, 5);
    List<Integer> row2 = Arrays.asList(1, 8, 2);
    max.execute(null, null, null, new Object[] {row1}, null);
    max.execute(null, null, null, new Object[] {row2}, null);
    assertEquals(8, max.getResult());
  }

  @Test
  public void getSyntaxAdvertisesFunctionShape() {
    String syntax = max.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'max(' prefix: " + syntax, syntax.startsWith("max("));
  }
}
