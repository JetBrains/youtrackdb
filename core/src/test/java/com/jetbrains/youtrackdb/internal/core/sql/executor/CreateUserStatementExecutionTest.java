package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of CREATE USER SQL statements.
 */
public class CreateUserStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    var name = "testPlain";
    session.begin();
    var result = session.execute("CREATE USER test IDENTIFIED BY foo ROLE admin");
    session.commit();
    result.close();

    session.begin();
    result = session.query("SELECT name, roles.name as roles FROM OUser WHERE name = 'test'");
    Assert.assertTrue(result.hasNext());
    var user = result.next();
    Assert.assertEquals("test", user.getProperty("name"));
    List<String> roles = user.getProperty("roles");
    Assert.assertEquals(1, roles.size());
    Assert.assertEquals("admin", roles.get(0));

    result.close();
    session.commit();
  }
}
