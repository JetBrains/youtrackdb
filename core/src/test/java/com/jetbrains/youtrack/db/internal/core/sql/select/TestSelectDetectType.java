package com.jetbrains.youtrack.db.internal.core.sql.select;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Test;

public class TestSelectDetectType extends DbTestBase {

  @Test
  public void testFloatDetection() {
    var res = session.query("select ty.type() as ty from ( select 1.021484375 as ty)");
    assertEquals(res.next().getProperty("ty"), "FLOAT");
    res = session.query("select ty.type() as ty from ( select " + Float.MAX_VALUE + "0101 as ty)");
    assertEquals(res.next().getProperty("ty"), "DOUBLE");
  }
}
