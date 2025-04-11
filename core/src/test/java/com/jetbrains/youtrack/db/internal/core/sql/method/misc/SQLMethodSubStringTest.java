package com.jetbrains.youtrack.db.internal.core.sql.method.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodSubString;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the "asList()" method implemented by the SQLMethodAsList class. Note that the only input to
 * the execute() method from the SQLMethod interface that is used is the ioResult argument (the 4th
 * argument).
 */
public class SQLMethodSubStringTest {

  private SQLMethodSubString function;

  @Before
  public void setup() {
    function = new SQLMethodSubString();
  }

  @Test
  public void testRange() {

    var result = function.execute("foobar", null, null, null, new Object[]{1, 3});
    assertEquals(result, "foobar".substring(1, 3));

    result = function.execute("foobar", null, null, null, new Object[]{0, 0});
    assertEquals(result, "foobar".substring(0, 0));

    result = function.execute("foobar", null, null, null, new Object[]{0, 1000});
    assertEquals(result, "foobar");

    result = function.execute("foobar", null, null, null, new Object[]{0, -1});
    assertEquals(result, "");

    result = function.execute("foobar", null, null, null, new Object[]{6, 6});
    assertEquals(result, "foobar".substring(6, 6));

    result = function.execute("foobar", null, null, null, new Object[]{1, 9});
    assertEquals(result, "foobar".substring(1, 6));

    result = function.execute("foobar", null, null, null, new Object[]{-7, 4});
    assertEquals(result, "foobar".substring(0, 4));
  }

  @Test
  public void testFrom() {
    var result = function.execute("foobar", null, null, null, new Object[]{1});
    assertEquals(result, "foobar".substring(1));

    result = function.execute("foobar", null, null, null, new Object[]{0});
    assertEquals(result, "foobar");

    result = function.execute("foobar", null, null, null, new Object[]{6});
    assertEquals(result, "foobar".substring(6));

    result = function.execute("foobar", null, null, null, new Object[]{12});
    assertEquals(result, "");

    result = function.execute("foobar", null, null, null, new Object[]{-7});
    assertEquals(result, "foobar");
  }

  @Test
  public void testNull() {

    var result = function.execute(null, null, null, null, null);
    assertNull(result);
  }
}
