package com.jetbrains.youtrack.db.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
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
    var scores = new int[]{1, 2, 3, 3, 3, 2};

    for (var s : scores) {
      mode.execute(null, null, null, new Object[]{s}, null);
    }

    var result = mode.getResult();
    assertEquals(3, (int) ((List<Integer>) result).get(0));
  }

  @Test
  public void testMultiMode() {
    var scores = new int[]{1, 2, 3, 3, 3, 2, 2};

    for (var s : scores) {
      mode.execute(null, null, null, new Object[]{s}, null);
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
      mode.execute(null, null, null, new Object[]{s}, null);
    }

    var result = mode.getResult();
    assertEquals(1, (int) ((List<Integer>) result).get(0));
  }
}
