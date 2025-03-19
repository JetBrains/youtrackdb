package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateFunctionStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    var name = "testPlain";
    session.begin();
    var result =
        session.execute(
            "CREATE FUNCTION " + name + " \"return a + b;\" PARAMETERS [a,b] language javascript");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertFalse(result.hasNext());
    Assert.assertNotNull(next);
    Assert.assertEquals(name, next.getProperty("functionName"));
    result.close();
    session.commit();

    result = session.query("select " + name + "('foo', 'bar') as sum");
    Assert.assertTrue(result.hasNext());
    next = result.next();
    Assert.assertFalse(result.hasNext());
    Assert.assertNotNull(next);
    Assert.assertEquals("foobar", next.getProperty("sum"));
    result.close();
  }
}
