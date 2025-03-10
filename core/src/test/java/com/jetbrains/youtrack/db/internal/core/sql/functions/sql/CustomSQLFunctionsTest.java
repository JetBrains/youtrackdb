package com.jetbrains.youtrack.db.internal.core.sql.functions.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import org.junit.Test;

public class CustomSQLFunctionsTest extends DbTestBase {

  @Test
  public void testRandom() {
    var result = session.query("select math_random() as random");
    assertTrue((Double) result.next().getProperty("random") > 0);
  }

  @Test
  public void testLog10() {
    var result = session.query("select math_log10(10000) as log10");
    assertEquals(4.0, result.next().getProperty("log10"), 0.0001);
  }

  @Test
  public void testAbsInt() {
    var result = session.query("select math_abs(-5) as abs");
    assertEquals(5, (int) (Integer) result.next().getProperty("abs"));
  }

  @Test
  public void testAbsDouble() {
    var result = session.query("select math_abs(-5.0d) as abs");
    assertEquals(5.0, result.findFirst(r -> r.getProperty("abs")), 0.0);
  }

  @Test
  public void testAbsFloat() {
    var result = session.query("select math_abs(-5.0f) as abs");
    assertEquals(5.0f, result.findFirst(r -> r.<Float>getProperty("abs")), 0.0f);
  }

  @Test(expected = QueryParsingException.class)
  public void testNonExistingFunction() {
    session.query("select math_min('boom', 'boom') as boom").findFirst(r -> r.getProperty("boom"));
  }
}
