package com.jetbrains.youtrack.db.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateDBSequenceStatementExecutionTest extends DbTestBase {

  @Test
  public void testSimple() {
    session.execute("CREATE SEQUENCE Sequence1 TYPE ORDERED");

    var results = session.query("select sequence('Sequence1').next() as val");
    Assert.assertTrue(results.hasNext());
    var result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(1L);
    Assert.assertFalse(results.hasNext());
    results.close();

    results = session.query("select sequence('Sequence1').next() as val");
    Assert.assertTrue(results.hasNext());
    result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(2L);
    Assert.assertFalse(results.hasNext());
    results.close();

    results = session.query("select sequence('Sequence1').next() as val");
    Assert.assertTrue(results.hasNext());
    result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(3L);
    Assert.assertFalse(results.hasNext());
    results.close();
  }

  @Test
  public void testIncrement() {
    session.execute("CREATE SEQUENCE SequenceIncrement TYPE ORDERED INCREMENT 3");

    var results = session.query("select sequence('SequenceIncrement').next() as val");
    Assert.assertTrue(results.hasNext());
    var result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(3L);
    Assert.assertFalse(results.hasNext());
    results.close();

    results = session.query("select sequence('SequenceIncrement').next() as val");
    Assert.assertTrue(results.hasNext());
    result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(6L);
    Assert.assertFalse(results.hasNext());
    results.close();

    results = session.query("select sequence('SequenceIncrement').next() as val");
    Assert.assertTrue(results.hasNext());
    result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(9L);
    Assert.assertFalse(results.hasNext());
    results.close();
  }

  @Test
  public void testStart() {
    session.execute("CREATE SEQUENCE SequenceStart TYPE ORDERED START 3");

    var results = session.query("select sequence('SequenceStart').next() as val");
    Assert.assertTrue(results.hasNext());
    var result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(4L);
    Assert.assertFalse(results.hasNext());
    results.close();

    results = session.query("select sequence('SequenceStart').next() as val");
    Assert.assertTrue(results.hasNext());
    result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(5L);
    Assert.assertFalse(results.hasNext());
    results.close();

    results = session.query("select sequence('SequenceStart').next() as val");
    Assert.assertTrue(results.hasNext());
    result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(6L);
    Assert.assertFalse(results.hasNext());
    results.close();
  }

  @Test
  public void testStartIncrement() {
    session.execute("CREATE SEQUENCE SequenceStartIncrement TYPE ORDERED START 3 INCREMENT 10");

    var results = session.query("select sequence('SequenceStartIncrement').next() as val");
    Assert.assertTrue(results.hasNext());
    var result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(13L);
    Assert.assertFalse(results.hasNext());
    results.close();

    results = session.query("select sequence('SequenceStartIncrement').next() as val");
    Assert.assertTrue(results.hasNext());
    result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(23L);
    Assert.assertFalse(results.hasNext());
    results.close();

    results = session.query("select sequence('SequenceStartIncrement').next() as val");
    Assert.assertTrue(results.hasNext());
    result = results.next();
    assertThat((Long) result.getProperty("val")).isEqualTo(33L);
    Assert.assertFalse(results.hasNext());
    results.close();
  }

  @Test
  public void testCreateSequenceIfNotExists() {
    session.execute("CREATE SEQUENCE SequenceIfNotExists if not exists TYPE ORDERED").close();

    var result =
        session.execute("CREATE SEQUENCE SequenceIfNotExists if not exists TYPE ORDERED");

    Assert.assertFalse(result.hasNext());
    result.close();
  }
}
