package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ProfileStatementExecutionTest extends DbTestBase {

  @Test
  public void testProfile() {
    graph.autoExecuteInTx(g -> g.addSchemaClass("testProfile"));

    session.begin();
    session.execute("insert into testProfile set name ='foo'");
    session.execute("insert into testProfile set name ='bar'");
    session.commit();

    session.begin();
    var result = session.query("PROFILE SELECT FROM testProfile WHERE name ='bar'");
    Assert.assertTrue(result.getExecutionPlan().prettyPrint(0, 2).contains("μs"));

    result.close();
    session.commit();
  }
}
