package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ProfileStatementExecutionTest extends DbTestBase {

  @Test
  public void testProfile() {
    session.createClass("testProfile");

    session.begin();
    session.command("insert into testProfile set name ='foo'");
    session.command("insert into testProfile set name ='bar'");
    session.commit();

    session.begin();
    var result = session.query("PROFILE SELECT FROM testProfile WHERE name ='bar'");
    Assert.assertTrue(result.getExecutionPlan().prettyPrint(0, 2).contains("μs"));

    result.close();
    session.commit();
  }
}
