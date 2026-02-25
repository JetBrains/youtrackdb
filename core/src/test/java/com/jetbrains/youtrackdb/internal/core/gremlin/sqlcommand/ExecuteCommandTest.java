package com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ExecuteCommandTest extends GraphBaseTest {

  @After
  public void rollbackOpenTx() {
    if (graph.tx().isOpen()) {
      graph.tx().rollback();
    }
  }

  private SqlCommandExecutionResult exec(String sql) {
    return ((YTDBGraphInternal) graph).executeCommand(sql, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldRejectNullCommand() {
    exec(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldRejectBlankCommand() {
    exec("   ");
  }

  @Test
  public void shouldBeginTransaction() {
    var result = exec("BEGIN");

    Assert.assertTrue(result instanceof SqlCommandExecutionResult.Unit);
    Assert.assertTrue(graph.tx().isOpen());
  }

  @Test
  public void shouldNoOpBeginWhenAlreadyOpen() {
    graph.tx().readWrite();

    var result = exec("BEGIN");

    Assert.assertTrue(result instanceof SqlCommandExecutionResult.Unit);
    Assert.assertTrue(graph.tx().isOpen());
  }

  @Test
  public void shouldCommitOpenTransaction() {
    graph.tx().readWrite();

    var result = exec("COMMIT");

    Assert.assertTrue(result instanceof SqlCommandExecutionResult.Unit);
    Assert.assertFalse(graph.tx().isOpen());
  }

  @Test(expected = IllegalStateException.class)
  public void shouldThrowOnCommitWithoutTransaction() {
    exec("COMMIT");
  }

  @Test
  public void shouldRollbackOpenTransaction() {
    graph.tx().readWrite();

    var result = exec("ROLLBACK");

    Assert.assertTrue(result instanceof SqlCommandExecutionResult.Unit);
    Assert.assertFalse(graph.tx().isOpen());
  }

  @Test(expected = IllegalStateException.class)
  public void shouldThrowOnRollbackWithoutTransaction() {
    exec("ROLLBACK");
  }
}
