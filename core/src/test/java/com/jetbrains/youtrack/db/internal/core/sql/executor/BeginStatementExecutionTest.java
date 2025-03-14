package com.jetbrains.youtrack.db.internal.core.sql.executor;

import static com.jetbrains.youtrack.db.internal.core.sql.executor.ExecutionPlanPrintUtils.printExecutionPlan;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class BeginStatementExecutionTest extends DbTestBase {

  @Test
  public void testBegin() {
    Assert.assertTrue(
        session.getTransactionInternal() == null || !session.getTransactionInternal().isActive());
    var result = session.command("begin");
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
