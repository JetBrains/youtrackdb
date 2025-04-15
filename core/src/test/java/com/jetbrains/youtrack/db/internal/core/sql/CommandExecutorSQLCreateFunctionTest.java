package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
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
