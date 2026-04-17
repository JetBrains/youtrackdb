package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class SQLFunctionModeTest {

  private SQLFunctionMode mode;

  @Before
  public void setup() {
    mode =
        new SQLFunctionMode();
  }

  @Test
  public void testEmpty() {
    var result = mode.getResult();
    assertNull(result);
  }

  @Test
  public void testSingleMode() {
    var scores = new int[] {1, 2, 3, 3, 3, 2};

    for (var s : scores) {
      mode.execute(null, null, null, new Object[] {s}, null);
    }

    var result = mode.getResult();
    assertEquals(3, (int) ((List<Integer>) result).get(0));
  }

  @Test
  public void testMultiMode() {
    var scores = new int[] {1, 2, 3, 3, 3, 2, 2};

    for (var s : scores) {
      mode.execute(null, null, null, new Object[] {s}, null);
    }

    var result = mode.getResult();
    var modes = (List<Integer>) result;
    assertEquals(2, modes.size());
    assertTrue(modes.contains(2));
    assertTrue(modes.contains(3));
  }

  @Test
  public void testMultiValue() {
    var scores = new List[2];
    scores[0] = Arrays.asList(1, 2, null, 3, 4);
    scores[1] = Arrays.asList(1, 1, 1, 2, null);

    for (var s : scores) {
      mode.execute(null, null, null, new Object[] {s}, null);
    }

    var result = mode.getResult();
    assertEquals(1, (int) ((List<Integer>) result).get(0));
  }

  @Test
  public void testSingleNullParameterLeavesResultNull() {
    // Direct single-null param hits the non-collection evaluate() path, where the
    // `value != null` guard skips the occurrence counter. Without buffered values
    // the result is null.
    mode.execute(null, null, null, new Object[] {null}, null);
    assertNull(mode.getResult());
  }

  @Test
  public void testEmptyCollectionLeavesResultNull() {
    // MultiValue branch, zero-iteration loop — no occurrences recorded → result null.
    mode.execute(null, null, null, new Object[] {Collections.emptyList()}, null);
    assertNull(mode.getResult());
  }

  @Test
  public void testAggregateResultsIsAlwaysTrue() {
    assertTrue(mode.aggregateResults());
  }

  @Test
  public void testGetSyntaxAdvertisesFunctionShape() {
    var syntax = mode.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'mode(' prefix, got: " + syntax, syntax.startsWith("mode("));
  }
}
