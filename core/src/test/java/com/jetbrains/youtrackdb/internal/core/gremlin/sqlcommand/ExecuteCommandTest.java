package com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.service.YTDBCommandService;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.Map;
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

  /** Verifies direct dispatcher calls keep schema DDL in an already-open transaction. */
  @Test
  public void shouldExecuteDdlCreateClass() {
    graph.tx().readWrite();

    var result = exec("CREATE CLASS TestVertex EXTENDS V");

    Assert.assertTrue(result instanceof SqlCommandExecutionResult.Unit);
    graph.tx().rollback();
    Assert.assertNull(session.getSchema().getClass("TestVertex"));
  }

  /** Verifies lazy yql schema DDL rolls back with an explicit caller transaction. */
  @Test
  public void shouldRollbackYqlSchemaDdlWithCallerTransaction() {
    var ddl = graph.traversal().yql("CREATE CLASS YqlRollbackVertex EXTENDS V");
    graph.tx().open();

    ddl.iterate();
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("YqlRollbackVertex"));
  }

  /** Verifies pre-applied DDL uses the transaction active when execution actually starts. */
  @Test
  public void shouldRollbackPreAppliedYqlInLaterCallerTransaction() {
    graph.traversal().command("CREATE CLASS PreAppliedDataVertex EXTENDS V");
    var ddl = graph.traversal().yql("CREATE CLASS PreAppliedDdlVertex EXTENDS V");
    ddl.asAdmin().applyStrategies();
    Assert.assertTrue(graph.tx().isOpen());
    graph.tx().rollback();

    graph.tx().open();
    graph.addVertex(T.label, "PreAppliedDataVertex", "name", "pending");
    graph.traversal().V().hasNext();
    ddl.iterate();
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("PreAppliedDdlVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("PreAppliedDataVertex").count().next().longValue());
  }

  /** Verifies DDL does not commit a transaction auto-opened by an earlier data traversal. */
  @Test
  public void shouldRollbackDdlWithEarlierAutoOpenedDataTraversal() {
    graph.traversal().command("CREATE CLASS AutoOpenedDataVertex EXTENDS V");
    var pendingData = graph.traversal()
        .addV("AutoOpenedDataVertex")
        .property("name", "pending");
    pendingData.asAdmin().applyStrategies();
    Assert.assertTrue(graph.tx().isOpen());

    var ddl = graph.traversal().yql("CREATE CLASS AutoOpenedDdlVertex EXTENDS V");
    ddl.asAdmin().applyStrategies();
    pendingData.iterate();
    ddl.iterate();
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("AutoOpenedDdlVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("AutoOpenedDataVertex").count().next().longValue());
  }

  /** Verifies real work after DDL preparation claims the transaction before execution. */
  @Test
  public void shouldRollbackPreparedDdlAfterDirectGraphMutation() {
    graph.traversal().command("CREATE CLASS PreparedDdlDataVertex EXTENDS V");
    var ddl = graph.traversal().yql("CREATE CLASS PreparedDdlVertex EXTENDS V");
    ddl.asAdmin().applyStrategies();
    Assert.assertTrue(graph.tx().isOpen());

    graph.addVertex(T.label, "PreparedDdlDataVertex", "name", "pending");
    ddl.iterate();
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("PreparedDdlVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("PreparedDdlDataVertex").count().next().longValue());
  }

  /** Verifies retained vertex-property removal claims a prepared transaction before DDL. */
  @Test
  public void shouldRollbackRetainedVertexPropertyRemovalBeforePreparedDdl() {
    var vertex = graph.addVertex(T.label, "RetainedVertexProperty", "name", "original");
    var vertexId = vertex.id();
    var retainedProperty = vertex.property("name");
    graph.tx().commit();

    var ddl = graph.traversal().yql("CREATE CLASS VertexPropertyRemovalDdl EXTENDS V");
    ddl.asAdmin().applyStrategies();
    retainedProperty.remove();
    ddl.iterate();

    Assert.assertTrue(graph.tx().isOpen());
    graph.tx().rollback();
    Assert.assertNull(session.getSchema().getClass("VertexPropertyRemovalDdl"));
    Assert.assertEquals("original", graph.vertices(vertexId).next().value("name"));
  }

  /** Verifies retained edge-property removal claims a prepared transaction before DDL. */
  @Test
  public void shouldRollbackRetainedEdgePropertyRemovalBeforePreparedDdl() {
    var from = graph.addVertex(T.label, "RetainedEdgePropertyFrom");
    var to = graph.addVertex(T.label, "RetainedEdgePropertyTo");
    var edge = from.addEdge("RetainedEdgeProperty", to, "weight", 7);
    var edgeId = edge.id();
    var retainedProperty = edge.property("weight");
    graph.tx().commit();

    var ddl = graph.traversal().yql("CREATE CLASS EdgePropertyRemovalDdl EXTENDS V");
    ddl.asAdmin().applyStrategies();
    retainedProperty.remove();
    ddl.iterate();

    Assert.assertTrue(graph.tx().isOpen());
    graph.tx().rollback();
    Assert.assertNull(session.getSchema().getClass("EdgePropertyRemovalDdl"));
    Assert.assertEquals(Integer.valueOf(7), graph.edges(edgeId).next().value("weight"));
  }

  /** Verifies retained meta-property removal claims a prepared transaction before DDL. */
  @Test
  public void shouldRollbackRetainedMetaPropertyRemovalBeforePreparedDdl() {
    var vertex = graph.addVertex(T.label, "RetainedMetaProperty");
    var vertexId = vertex.id();
    var vertexProperty = vertex.property("nickname", "Al");
    var retainedProperty = vertexProperty.property("alias", "Big Al");
    graph.tx().commit();

    var ddl = graph.traversal().yql("CREATE CLASS MetaPropertyRemovalDdl EXTENDS V");
    ddl.asAdmin().applyStrategies();
    retainedProperty.remove();
    ddl.iterate();

    Assert.assertTrue(graph.tx().isOpen());
    graph.tx().rollback();
    Assert.assertNull(session.getSchema().getClass("MetaPropertyRemovalDdl"));
    var reloadedProperty = graph.vertices(vertexId).next().property("nickname");
    Assert.assertEquals("Big Al", reloadedProperty.value("alias"));
  }

  /** Verifies an independent identical DDL does not commit data after traversal preparation. */
  @Test
  public void shouldRollbackIndependentIdenticalDdlWithPendingData() {
    graph.traversal().command("CREATE CLASS IdenticalDdlDataVertex EXTENDS V");
    var preparedDdl =
        graph.traversal().yql("CREATE CLASS IndependentIdenticalDdlVertex EXTENDS V");
    preparedDdl.asAdmin().applyStrategies();
    Assert.assertTrue(graph.tx().isOpen());

    graph.addVertex(T.label, "IdenticalDdlDataVertex", "name", "pending");
    graph.traversal().yql("CREATE CLASS IndependentIdenticalDdlVertex EXTENDS V").iterate();
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("IndependentIdenticalDdlVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("IdenticalDdlDataVertex").count().next().longValue());
  }

  /** Verifies data before schema DDL in one traversal remains controlled by the caller. */
  @Test
  public void shouldRollbackAddVertexBeforeSchemaDdlInSameTraversal() {
    graph.traversal().command("CREATE CLASS ChainedDataVertex EXTENDS V");

    graph.traversal()
        .addV("ChainedDataVertex")
        .property("name", "pending")
        .call(YTDBCommandService.YQL_NAME,
            yqlParams("CREATE CLASS ChainedDataDdlVertex EXTENDS V"))
        .iterate();
    Assert.assertTrue(graph.tx().isOpen());
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("ChainedDataDdlVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("ChainedDataVertex").count().next().longValue());
  }

  /** Verifies a non-DDL command claims the transaction before later schema DDL. */
  @Test
  public void shouldRollbackYqlMutationBeforeSchemaDdlInSameTraversal() {
    graph.traversal().command("CREATE CLASS ChainedYqlDataVertex EXTENDS V");

    graph.traversal()
        .yql("INSERT INTO ChainedYqlDataVertex SET name = 'pending'")
        .call(YTDBCommandService.YQL_NAME,
            yqlParams("CREATE CLASS ChainedYqlDdlVertex EXTENDS V"))
        .iterate();
    Assert.assertTrue(graph.tx().isOpen());
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("ChainedYqlDdlVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("ChainedYqlDataVertex").count().next().longValue());
  }

  /** Verifies a reset DDL traversal reevaluates ownership for the current transaction. */
  @Test
  public void shouldNotCommitPendingDataWhenResetYqlRunsInNewTransaction() {
    graph.traversal().command("CREATE CLASS ResetDataVertex EXTENDS V");
    var ddl = graph.traversal().yql("CREATE CLASS ResetDdlVertex IF NOT EXISTS EXTENDS V");

    graph.tx().open();
    ddl.iterate();
    graph.tx().commit();
    ddl.asAdmin().reset();

    graph.tx().open();
    graph.addVertex(T.label, "ResetDataVertex", "name", "pending");
    ddl.iterate();
    graph.tx().rollback();

    Assert.assertNotNull(session.getSchema().getClass("ResetDdlVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("ResetDataVertex").count().next().longValue());
  }

  /**
   * Verifies a prepared locked clone remains standalone while its preparation transaction is open.
   */
  @Test
  public void shouldPersistLockedCloneYqlWithOpenPreparationTransaction() {
    var ddl = graph.traversal().yql("CREATE CLASS StandaloneClonedDdlVertex EXTENDS V");
    ddl.asAdmin().applyStrategies();
    var clonedDdl = ddl.asAdmin().clone();
    Assert.assertTrue(graph.tx().isOpen());

    clonedDdl.iterate();
    if (graph.tx().isOpen()) {
      graph.tx().rollback();
    }

    Assert.assertNotNull(session.getSchema().getClass("StandaloneClonedDdlVertex"));
  }

  /** Verifies a locked clone uses a later caller transaction instead of preparation state. */
  @Test
  public void shouldRollbackLockedCloneYqlInLaterCallerTransaction() {
    graph.traversal().command("CREATE CLASS ClonedDataVertex EXTENDS V");
    var ddl = graph.traversal().yql("CREATE CLASS ClonedDdlVertex EXTENDS V");
    ddl.asAdmin().applyStrategies();
    var clonedDdl = ddl.asAdmin().clone();
    graph.tx().rollback();

    graph.tx().open();
    graph.addVertex(T.label, "ClonedDataVertex", "name", "pending");
    clonedDdl.iterate();
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("ClonedDdlVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("ClonedDataVertex").count().next().longValue());
  }

  /** Verifies eager command schema DDL rolls back with pending data in the caller transaction. */
  @Test
  public void shouldNotCommitPendingDataBeforeCommandSchemaDdl() {
    graph.traversal().command("CREATE CLASS ExistingVertex EXTENDS V");
    graph.tx().open();
    graph.addVertex(T.label, "ExistingVertex", "name", "pending");

    graph.traversal().command("CREATE CLASS CommandRollbackVertex EXTENDS V");
    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("CommandRollbackVertex"));
    Assert.assertEquals(0L,
        graph.traversal().V().hasLabel("ExistingVertex").count().next().longValue());
  }

  /** Verifies schema and data become durable together when the caller commits. */
  @Test
  public void shouldCommitYqlSchemaAndDataTogether() {
    graph.tx().open();
    graph.traversal().yql("CREATE CLASS CommittedVertex EXTENDS V").iterate();
    graph.addVertex(T.label, "CommittedVertex", "name", "committed");

    graph.tx().commit();

    Assert.assertNotNull(session.getSchema().getClass("CommittedVertex"));
    Assert.assertEquals(1L,
        graph.traversal().V().hasLabel("CommittedVertex").count().next().longValue());
  }

  /** Verifies standalone schema DDL remains immediately durable for both public methods. */
  @Test
  public void shouldPersistStandaloneSchemaDdlImmediately() {
    graph.traversal().command("CREATE CLASS StandaloneCommandVertex EXTENDS V");
    graph.traversal().yql("CREATE CLASS StandaloneYqlVertex EXTENDS V").iterate();

    Assert.assertFalse(graph.tx().isOpen());
    Assert.assertNotNull(session.getSchema().getClass("StandaloneCommandVertex"));
    Assert.assertNotNull(session.getSchema().getClass("StandaloneYqlVertex"));
  }

  /** Verifies transaction helpers own schema DDL and commit it with their scope. */
  @Test
  public void shouldCommitSchemaDdlInsideTransactionHelper() throws Exception {
    graph.traversal().executeInTx(
        tx -> tx.command("CREATE CLASS HelperCommittedVertex EXTENDS V"));

    Assert.assertNotNull(session.getSchema().getClass("HelperCommittedVertex"));
  }

  /** Verifies a helper rollback removes schema DDL when user code fails. */
  @Test
  public void shouldRollbackSchemaDdlWhenTransactionHelperFails() {
    try {
      graph.traversal().executeInTx(tx -> {
        tx.yql("CREATE CLASS HelperRollbackVertex EXTENDS V").iterate();
        throw new IllegalStateException("force rollback");
      });
      Assert.fail("The helper must propagate the user exception");
    } catch (IllegalStateException expected) {
      Assert.assertEquals("force rollback", expected.getMessage());
    }

    Assert.assertNull(session.getSchema().getClass("HelperRollbackVertex"));
  }

  /** Verifies chained BEGIN and ROLLBACK dynamically own and discard schema DDL. */
  @Test
  public void shouldRollbackSchemaDdlInChainedTransactionControls() {
    graph.traversal()
        .yql("BEGIN")
        .call(YTDBCommandService.YQL_NAME,
            yqlParams("CREATE CLASS ChainedRollbackVertex EXTENDS V"))
        .call(YTDBCommandService.YQL_NAME, yqlParams("ROLLBACK"))
        .iterate();

    Assert.assertNull(session.getSchema().getClass("ChainedRollbackVertex"));
  }

  /** Verifies COMMIT ends ownership before later schema DDL in the same traversal. */
  @Test
  public void shouldPersistSchemaDdlAfterCommitInChainedTransactionControls() {
    graph.traversal()
        .yql("BEGIN")
        .call(YTDBCommandService.YQL_NAME, yqlParams("COMMIT"))
        .call(YTDBCommandService.YQL_NAME,
            yqlParams("CREATE CLASS ChainedStandaloneVertex EXTENDS V"))
        .iterate();

    Assert.assertFalse(graph.tx().isOpen());
    Assert.assertNotNull(session.getSchema().getClass("ChainedStandaloneVertex"));
  }

  /** Verifies a transaction opened by an earlier traversal owns later schema DDL. */
  @Test
  public void shouldUseTransactionOpenedByEarlierTraversal() {
    graph.traversal().V().hasNext();
    graph.traversal().yql("CREATE CLASS EarlierTraversalVertex EXTENDS V").iterate();

    graph.tx().rollback();

    Assert.assertNull(session.getSchema().getClass("EarlierTraversalVertex"));
  }

  /** Verifies every approved schema statement is classified without maintenance DDL. */
  @Test
  public void shouldClassifyOnlySupportedSchemaDdlFamily() {
    var supported = new String[] {
        "CREATE CLASS FamilyClass EXTENDS V",
        "ALTER CLASS FamilyClass NAME FamilyRenamed",
        "DROP CLASS FamilyClass",
        "CREATE PROPERTY FamilyClass.value STRING",
        "ALTER PROPERTY FamilyClass.value NAME renamed",
        "DROP PROPERTY FamilyClass.value",
        "CREATE INDEX FamilyClass.value ON FamilyClass (value) NOTUNIQUE",
        "DROP INDEX FamilyClass.value"
    };
    for (var sql : supported) {
      Assert.assertTrue(sql,
          session.isSchemaDdl(SQLEngine.parse(sql, session)));
    }

    Assert.assertFalse(session.isSchemaDdl(SQLEngine.parse("TRUNCATE CLASS FamilyClass", session)));
    Assert.assertFalse(session.isSchemaDdl(
        SQLEngine.parse("CREATE SEQUENCE FamilySequence TYPE ORDERED", session)));
    Assert.assertFalse(
        session.isSchemaDdl(SQLEngine.parse("REBUILD INDEX FamilyClass.value", session)));
  }

  /** Verifies schema DDL clears populated transaction query results. */
  @Test
  public void shouldInvalidatePopulatedTransactionResultCacheForSchemaDdl() {
    var cacheWasEnabled =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    try {
      graph.traversal().command("CREATE CLASS CachedSchemaVertex EXTENDS V");
      graph.addVertex(T.label, "CachedSchemaVertex", "name", "cached");
      graph.tx().commit();
      graph.tx().open();

      var graphTx = (YTDBTransaction) graph.tx();
      try (var results = graphTx.getDatabaseSession().query("SELECT FROM CachedSchemaVertex")) {
        results.toList();
      }
      var tx = (FrontendTransactionImpl) graphTx.getDatabaseSession().getActiveTransaction();
      Assert.assertEquals(1, tx.getQueryResultCache().size());

      graph.traversal().command("CREATE PROPERTY CachedSchemaVertex.tag STRING");

      Assert.assertEquals(0, tx.getQueryResultCache().size());
      graph.tx().rollback();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheWasEnabled);
    }
  }

  private static Map<String, Object> yqlParams(String command) {
    return Map.of(
        YTDBCommandService.COMMAND, command,
        YTDBCommandService.ARGUMENTS, Map.of());
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
