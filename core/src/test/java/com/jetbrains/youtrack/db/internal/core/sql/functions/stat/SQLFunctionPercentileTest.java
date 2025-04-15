package com.jetbrains.youtrack.db.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class SQLFunctionPercentileTest {

  private SQLFunctionPercentile percentile;

  @Before
  public void beforeMethod() {
    percentile =
        new SQLFunctionPercentile();
  }

  @Test
  public void testEmpty() {
    var result = percentile.getResult();
    assertNull(result);
  }

  @Test
  public void testSingleValueLower() {
    percentile.execute(null, null, null, new Object[]{10, .25}, null);
    assertEquals(10, percentile.getResult());
  }

  @Test
  public void testSingleValueUpper() {
    percentile.execute(null, null, null, new Object[]{10, .75}, null);
    assertEquals(10, percentile.getResult());
  }

  @Test
  public void test50thPercentileOdd() {
    var scores = new int[]{1, 2, 3, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[]{s, .5}, null);
    }

    var result = percentile.getResult();
    assertEquals(3.0, result);
  }

  @Test
  public void test50thPercentileOddWithNulls() {
    Integer[] scores = {null, 1, 2, null, 3, 4, null, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[]{s, .5}, null);
    }

    var result = percentile.getResult();
    assertEquals(3.0, result);
  }

  @Test
  public void test50thPercentileEven() {
    var scores = new int[]{1, 2, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[]{s, .5}, null);
    }

    var result = percentile.getResult();
    assertEquals(3.0, result);
  }

  @Test
  public void testFirstQuartile() {
    var scores = new int[]{1, 2, 3, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[]{s, .25}, null);
    }

    var result = percentile.getResult();
    assertEquals(1.5, result);
  }

  @Test
  public void testThirdQuartile() {
    var scores = new int[]{1, 2, 3, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[]{s, .75}, null);
    }

    var result = percentile.getResult();
    assertEquals(4.5, result);
  }

  @Test
  public void testMultiQuartile() {
    var scores = new int[]{1, 2, 3, 4, 5};

    for (var s : scores) {
      percentile.execute(null, null, null, new Object[]{s, .25, .75}, null);
    }

    var result = (List<Number>) percentile.getResult();
    assertEquals(1.5, result.get(0).doubleValue(), 0);
    assertEquals(4.5, result.get(1).doubleValue(), 0);
  }
}
