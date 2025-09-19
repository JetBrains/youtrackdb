package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class SQLAlterClassTest extends DbTestBase {

  @Test
  public void alterClassRenameTest() {
    session.getMetadata().getSlowMutableSchema().createClass("TestClass");

    try {
      session.execute("alter class TestClass name = 'test_class'").close();
      Assert.fail("the rename should fail for wrong syntax");
    } catch (CommandSQLParsingException ex) {

    }
    Assert.assertNotNull(session.getMetadata().getSlowMutableSchema().getClass("TestClass"));
  }

  @Test
  public void testQuoted() {
    try {
      session.execute("create class `Client-Type`").close();
      session.begin();
      session.execute("insert into `Client-Type` set foo = 'bar'").close();
      session.commit();

      var result = session.query("Select from `Client-Type`");
      Assert.assertEquals(result.stream().count(), 1);
    } catch (CommandSQLParsingException ex) {
      Assert.fail();
    }
  }
}
