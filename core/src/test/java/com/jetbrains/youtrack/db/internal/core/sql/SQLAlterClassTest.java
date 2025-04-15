package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class SQLAlterClassTest extends DbTestBase {

  @Test
  public void alterClassRenameTest() {
    session.getMetadata().getSchema().createClass("TestClass");

    try {
      session.execute("alter class TestClass name = 'test_class'").close();
      Assert.fail("the rename should fail for wrong syntax");
    } catch (CommandSQLParsingException ex) {

    }
    Assert.assertNotNull(session.getMetadata().getSchema().getClass("TestClass"));
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
