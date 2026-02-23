package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the BEGIN SQL statement execution. */
public class BeginStatementExecutionTest extends DbTestBase {

  @Test
  public void testBegin() {
    Assert.assertTrue(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
    var result = session.execute("begin");
    printExecutionPlan(null, result);
    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("begin", item.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    Assert.assertFalse(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
    session.commit();
  }
}
