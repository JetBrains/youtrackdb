package com.jetbrains.youtrackdb.internal.core.sql.functions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.math.BigDecimal;
import java.util.Date;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the SQL CONVERT() type conversion function and its as<Type>() siblings. Each
 * conversion is exercised in its own @Test method so a failure in one path does not abort the
 * remaining cases and JUnit reports the failing scenario by name.
 */
public class SQLFunctionConvertTest extends DbTestBase {

  @Before
  public void setUpFixture() {
    session.execute("create class TestConversion").close();

    session.begin();
    session.execute("insert into TestConversion set string = 'Jay', date = sysdate(), number = 33")
        .close();
    session.commit();

    session.begin();
    var doc = session.query("select from TestConversion limit 1").next().getIdentity();
    session.execute("update TestConversion set selfrid = 'foo" + doc.getIdentity() + "'").close();
    session.commit();
  }

  @After
  public void rollbackIfLeftOpen() {
    // Each test opens a query transaction; if an assertion fires before commit, the leaked
    // transaction would cascade into DbTestBase.afterTest() and mask the real failure.
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  @Test
  public void asStringReturnsString() {
    session.begin();
    var results = session.query("select string.asString() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof String);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void asDateReturnsDate() {
    session.begin();
    var results = session.query("select number.asDate() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Date);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void asDateTimeReturnsDate() {
    session.begin();
    var results = session.query("select number.asDateTime() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Date);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void asIntegerReturnsInteger() {
    session.begin();
    var results = session.query("select number.asInteger() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Integer);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void asLongReturnsLong() {
    session.begin();
    var results = session.query("select number.asLong() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Long);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void asFloatReturnsFloat() {
    session.begin();
    var results = session.query("select number.asFloat() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Float);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void asDecimalReturnsBigDecimal() {
    session.begin();
    var results = session.query("select number.asDecimal() as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof BigDecimal);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void convertToLongReturnsLong() {
    session.begin();
    var results = session.query("select number.convert('LONG') as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Long);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void convertToShortReturnsShort() {
    session.begin();
    var results = session.query("select number.convert('SHORT') as convert from TestConversion");
    assertTrue(results.next().getProperty("convert") instanceof Short);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void convertToDoubleReturnsDouble() {
    session.begin();
    var results = session.query("select number.convert('DOUBLE') as convert from TestConversion");
    assertNotNull(results);
    assertTrue(results.next().getProperty("convert") instanceof Double);
    assertFalse(results.hasNext());
    session.commit();
  }

  @Test
  public void convertToLinkResolvesAndReadsThroughProperty() {
    session.begin();
    var results =
        session.query(
            "select selfrid.substring(3).convert('LINK').string as convert from TestConversion");
    assertEquals("Jay", results.next().getProperty("convert"));
    assertFalse(results.hasNext());
    session.commit();
  }
}
