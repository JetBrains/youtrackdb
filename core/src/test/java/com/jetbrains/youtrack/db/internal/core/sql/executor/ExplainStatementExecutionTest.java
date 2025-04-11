package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ExplainStatementExecutionTest extends DbTestBase {

  @Test
  public void testExplainSelectNoTarget() {
    var result = session.query("explain select 1 as one, 2 as two, 2+3");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertNotNull(next.getProperty("executionPlan"));
    Assert.assertNotNull(next.getProperty("executionPlanAsString"));

    var plan = result.getExecutionPlan();
    Assert.assertNotNull(plan);
    Assert.assertTrue(plan instanceof SelectExecutionPlan);

    result.close();
  }
}
