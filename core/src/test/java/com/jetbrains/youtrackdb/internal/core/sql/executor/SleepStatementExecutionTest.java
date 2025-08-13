package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class SleepStatementExecutionTest extends DbTestBase {

  @Test
  public void testBasic() {
    var begin = System.currentTimeMillis();
    var result = session.execute("sleep 1000");
    Assert.assertTrue(System.currentTimeMillis() - begin >= 1000);
    printExecutionPlan(null, result);
    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("sleep", item.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
  }
}
