package com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand;

import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import org.apache.tinkerpop.gremlin.structure.T;
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

  @Test(expected = IllegalArgumentException.class)
  public void shouldRejectEmptyStringCommand() {
    exec("");
  }

  @Test(expected = CommandSQLParsingException.class)
  public void shouldThrowParseErrorForInvalidSqlInOpenTransaction() {
    graph.tx().readWrite();
    exec("THIS IS NOT VALID SQL !!!");
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

  // ── Statement cache and execution plan cache regression tests ──

  /**
   * Verifies that queries executed via executeCommand() within an open transaction go
   * through the statement cache (SQLEngine.parse / YqlStatementCache). The cache sets
   * originalStatement on the parsed SQLStatement, which is the key for the execution
   * plan cache. Without this, every executeCommand() call re-parses and re-plans from
   * scratch.
   */
  @Test
  public void shouldPopulateStatementCacheForQueryInOpenTransaction() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var stmtCache = session.getSharedContext().getYqlStatementCache();
    var query = "SELECT FROM Person WHERE name = 'Alice'";

    Assert.assertFalse(
        "Statement cache should NOT contain the query before executeCommand()",
        stmtCache.contains(query));

    graph.tx().readWrite();
    exec(query);

    Assert.assertTrue(
        "Statement cache should contain the query after executeCommand()",
        stmtCache.contains(query));
  }

  /**
   * Verifies that the parsed statement produced by executeCommand() has
   * originalStatement set, which is the key for the execution plan cache.
   * This is the core property that was missing before the fix: the Gremlin path
   * used direct parser construction which never set originalStatement, so the
   * execution plan cache always missed.
   */
  @Test
  public void shouldSetOriginalStatementEnablingExecutionPlanCache() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    graph.tx().readWrite();
    var query = "SELECT FROM Person WHERE name = 'Alice'";
    exec(query);

    // SQLEngine.parse() returns the cached statement from the exec() call above,
    // so its originalStatement reflects what executeCommand() produced.
    var cachedStatement = SQLEngine.parse(query, session);
    Assert.assertNotNull(
        "Statement parsed via executeCommand() should have originalStatement set "
            + "(required for YqlExecutionPlanCache)",
        cachedStatement.getOriginalStatement());
    Assert.assertEquals(query, cachedStatement.getOriginalStatement());
  }

  /**
   * Verifies the BEGIN → query sequence: BEGIN uses uncached parse (no tx open yet),
   * then the subsequent query uses the cached parse path (tx is now open). This is
   * the most common real-world usage pattern through the Gremlin yql() path.
   */
  @Test
  public void shouldUseCachedParseAfterBeginOpensTransaction() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    // Close auto-opened tx so BEGIN has to open it
    if (graph.tx().isOpen()) {
      graph.tx().rollback();
    }

    var stmtCache = session.getSharedContext().getYqlStatementCache();
    var query = "SELECT FROM Person WHERE name = 'Alice'";

    Assert.assertFalse(
        "Statement cache should NOT contain the query before BEGIN + query sequence",
        stmtCache.contains(query));

    // BEGIN uses uncached parse (no tx open yet)
    exec("BEGIN");
    Assert.assertTrue(graph.tx().isOpen());

    // Query uses cached parse (tx is now open)
    var result = exec(query);
    Assert.assertTrue(result instanceof SqlCommandExecutionResult.Results);

    Assert.assertTrue(
        "Statement cache should contain the query after BEGIN + query sequence",
        stmtCache.contains(query));
  }

  /**
   * Verifies that executing the same query twice via executeCommand() populates the
   * execution plan cache on the first call, so the second call can reuse the plan.
   */
  @Test
  public void shouldPopulateExecutionPlanCacheOnRepeatedQuery() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    var planCache = session.getSharedContext().getYqlExecutionPlanCache();
    var query = "SELECT FROM Person WHERE name = 'Alice'";

    Assert.assertFalse(
        "Execution plan cache should NOT contain the query before executeCommand()",
        planCache.contains(query));

    graph.tx().readWrite();
    // First execution: parses and plans
    exec(query);
    // Second execution: should hit plan cache
    exec(query);

    Assert.assertTrue(
        "Execution plan cache should contain the query after repeated executeCommand()",
        planCache.contains(query));
  }

  /**
   * Verifies that DDL statements executed via executeCommand() work correctly and
   * return Unit results.
   */
  @Test
  public void shouldExecuteDdlCreateClass() {
    graph.tx().readWrite();

    var result = exec("CREATE CLASS TestVertex EXTENDS V");

    Assert.assertTrue(result instanceof SqlCommandExecutionResult.Unit);
    Assert.assertNotNull(session.getSchema().getClass("TestVertex"));
  }

  /**
   * Verifies that SELECT queries executed via executeCommand() within an open
   * transaction return Results (not Unit).
   */
  @Test
  public void shouldExecuteSelectWithOpenTransaction() {
    session.getSchema().createVertexClass("Person");
    graph.addVertex(T.label, "Person", "name", "Alice");
    graph.tx().commit();

    graph.tx().readWrite();
    var result = exec("SELECT FROM Person");

    Assert.assertTrue(result instanceof SqlCommandExecutionResult.Results);
  }
}
