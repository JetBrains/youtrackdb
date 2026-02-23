package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/** Tests for SQL ROLLBACK statement execution. */
public class RollbackStatementExecutionTest extends DbTestBase {

  @Test
  public void testBegin() {
    Assert.assertTrue(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
    session.begin();
    Assert.assertFalse(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
    var result = session.execute("rollback");
    printExecutionPlan(null, result);
    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("rollback", item.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
  }
}
