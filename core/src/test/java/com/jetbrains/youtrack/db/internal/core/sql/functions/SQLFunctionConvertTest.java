package com.jetbrains.youtrack.db.internal.core.sql.functions;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.math.BigDecimal;
import java.util.Date;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 */
public class SQLFunctionConvertTest extends DbTestBase {

  @Test
  public void testSQLConversions() {
    session.execute("create class TestConversion").close();

    session.begin();
    session.execute("insert into TestConversion set string = 'Jay', date = sysdate(), number = 33")
        .close();
    session.commit();

    session.begin();
    var doc = session.query("select from TestConversion limit 1").next().getIdentity();
    session.execute("update TestConversion set selfrid = 'foo" + doc.getIdentity() + "'").close();
    session.commit();

    session.begin();
    var results = session.query("select string.asString() as convert from TestConversion");

    assertTrue(results.next().getProperty("convert") instanceof String);
    assertFalse(results.hasNext());

    results = session.query("select number.asDate() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Date);
    assertFalse(results.hasNext());

    results = session.query("select number.asDateTime() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Date);
    assertFalse(results.hasNext());

    results = session.query("select number.asInteger() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Integer);
    assertFalse(results.hasNext());

    results = session.query("select number.asLong() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Long);
    assertFalse(results.hasNext());

    results = session.query("select number.asFloat() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Float);
    assertFalse(results.hasNext());

    results = session.query("select number.asDecimal() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof BigDecimal);
    assertFalse(results.hasNext());

    results = session.query("select number.convert('LONG') as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Long);
    assertFalse(results.hasNext());

    results = session.query("select number.convert('SHORT') as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Short);
    assertFalse(results.hasNext());

    results = session.query("select number.convert('DOUBLE') as convert from TestConversion");
    assertNotNull(results);
    assertTrue(results.next().getProperty("convert") instanceof Double);
    assertFalse(results.hasNext());

    results =
        session.query(
            "select selfrid.substring(3).convert('LINK').string as convert from TestConversion");
    assertEquals(results.next().getProperty("convert"), "Jay");
    assertFalse(results.hasNext());
    session.commit();
  }
}
