package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the SQL CREATE FUNCTION command executor. */
public class CommandExecutorSQLCreateFunctionTest extends DbTestBase {

  @Test
  public void testCreateFunction() {
    session.begin();
    session.execute(
            "CREATE FUNCTION testCreateFunction \"return 'hello '+name;\" PARAMETERS [name]"
                + " IDEMPOTENT true LANGUAGE Javascript")
        .close();
    session.commit();

    var result = session.execute("select testCreateFunction('world') as name");
    Assert.assertEquals(result.next().getProperty("name"), "hello world");
    Assert.assertFalse(result.hasNext());
  }
}
