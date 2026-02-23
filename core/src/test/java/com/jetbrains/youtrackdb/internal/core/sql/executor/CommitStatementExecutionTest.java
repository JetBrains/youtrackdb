package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the COMMIT SQL statement execution. */
public class CommitStatementExecutionTest extends DbTestBase {

  @Test
  public void testBegin() {
    Assert.assertTrue(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
    session.begin();
    Assert.assertFalse(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
    var result = session.execute("commit");
    printExecutionPlan(null, result);
    Assert.assertNotNull(result);
    Assert.assertTrue(result.hasNext());
    var item = result.next();
    Assert.assertEquals("commit", item.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    Assert.assertTrue(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
  }
}
