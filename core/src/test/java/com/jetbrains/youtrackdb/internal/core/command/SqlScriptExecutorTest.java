package com.jetbrains.youtrackdb.internal.core.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests SQL script execution including plain scripts and parameterized scripts.
 */
public class SqlScriptExecutorTest extends DbTestBase {

  @Test
  public void testPlain() {
    session.begin();
    var script = "insert into V set name ='a';\n";
    script += "insert into V set name ='b';\n";
    script += "insert into V set name ='c';\n";
    script += "insert into V set name ='d';\n";
    script += "select from V;";

    var result = session.computeScript("sql", script);
    var list =
        result.stream().map(x -> x.getProperty("name")).toList();
    result.close();

    Assert.assertTrue(list.contains("a"));
    Assert.assertTrue(list.contains("b"));
    Assert.assertTrue(list.contains("c"));
    Assert.assertTrue(list.contains("d"));
    Assert.assertEquals(4, list.size());
    session.commit();
  }

  @Test
  public void testWithPositionalParams() {
    session.begin();
    var script = "insert into V set name ='a';\n";
    script += "insert into V set name ='b';\n";
    script += "insert into V set name ='c';\n";
    script += "insert into V set name ='d';\n";
    script += "select from V where name = ?;\n";

    var result = session.computeScript("sql", script, "a");

    var list =
        result.stream().map(x -> x.getProperty("name")).collect(Collectors.toList());
    result.close();
    Assert.assertTrue(list.contains("a"));

    Assert.assertEquals(1, list.size());
    session.commit();
  }

  @Test
  public void testWithNamedParams() {
    session.begin();
    var script = "insert into V set name ='a';\n";
    script += "insert into V set name ='b';\n";
    script += "insert into V set name ='c';\n";
    script += "insert into V set name ='d';\n";
    script += "select from V where name = :name;";

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "a");

    var result = session.computeScript("sql", script, params);
    var list =
        result.stream().map(x -> x.getProperty("name")).toList();
    result.close();

    Assert.assertTrue(list.contains("a"));

    Assert.assertEquals(1, list.size());
    session.commit();
  }

  @Test
  public void testMultipleCreateEdgeOnTheSameLet() {
    session.begin();
    var script = "let $v1 = create vertex V set name = 'Foo';\n";
    script += "let $v2 = create vertex V set name = 'Bar';\n";
    script += "create edge from $v1 to $v2;\n";
    script += "let $v3 = create vertex V set name = 'Baz';\n";
    script += "create edge from $v1 to $v3;\n";

    var result = session.computeScript("sql", script);
    result.close();

    result = session.query("SELECT expand(out()) FROM V WHERE name ='Foo'");
    Assert.assertEquals(2, result.stream().count());
    result.close();
    session.commit();
  }

  // ---------------------------------------------------------------------------
  // Track 9 Step 2 — branch-gap extensions.
  // Source: SqlScriptExecutor.java:80-152 (executeInternal) and 154-215 (executeFunction).
  // ---------------------------------------------------------------------------

  /**
   * A script missing its trailing {@code ';'} must still run — the executor appends one before
   * parsing (line 46-48). Without this branch, the parser would reject the script.
   */
  @Test
  public void testScriptWithoutTrailingSemicolonIsAccepted() {
    session.begin();
    // No trailing semicolon on the final insert — executor appends one.
    var script = "insert into V set name = 'auto-terminated'";

    var result = session.computeScript("sql", script);
    result.close();
    session.commit();

    var query = session.query("SELECT FROM V WHERE name = 'auto-terminated'");
    assertEquals(1, query.stream().count());
    query.close();
  }

  /**
   * A script containing a {@code BEGIN}/{@code COMMIT RETRY n} block must build a retry plan.
   * When {@code n} is zero or negative, {@code executeInternal} throws
   * {@link CommandExecutionException} with {@code "Invalid retry number"} (line 110-114). Pin
   * that message so calling code can diagnose the misconfiguration. The error is raised
   * eagerly at script-plan build time, so {@code session.computeScript} itself throws without
   * producing a ResultSet.
   */
  @Test
  public void testCommitRetryZeroThrowsInvalidRetryNumber() {
    final var script =
        "BEGIN;\ninsert into V set name = 'retry-zero';\nCOMMIT RETRY 0;\n";

    var ex = assertThrows(CommandExecutionException.class,
        () -> session.computeScript("sql", script));
    assertTrue("error message must name the retry count: " + ex.getMessage(),
        ex.getMessage().contains("Invalid retry number"));
    assertTrue("message must include the rejected count: " + ex.getMessage(),
        ex.getMessage().contains("0"));
  }

  /**
   * A script that wraps statements in {@code BEGIN}/{@code COMMIT RETRY n} with n &gt; 0 executes
   * the retry block and leaves the produced records visible. This pins the retry-plan chaining
   * branch at lines 116-126 (RetryStep construction + plan.chain).
   */
  @Test
  public void testCommitRetryPositiveExecutesBlock() {
    var script = "BEGIN;\n";
    script += "insert into V set name = 'retry-ok';\n";
    script += "COMMIT RETRY 3;\n";

    var result = session.computeScript("sql", script);
    result.close();

    var query = session.query("SELECT FROM V WHERE name = 'retry-ok'");
    assertEquals("retry block must execute exactly once when no conflict",
        1, query.stream().count());
    query.close();
  }

  /**
   * A {@code BEGIN}/{@code ROLLBACK} script exercises the rollback branch at lines 136-144:
   * pending retry-block statements are still chained onto the plan (and therefore executed and
   * then rolled back) and {@code nestedTxLevel} resets to zero. Pin the observed behavior: the
   * inserted record does NOT persist.
   */
  @Test
  public void testBeginRollbackRollsBackBufferedStatements() {
    var script = "BEGIN;\n";
    script += "insert into V set name = 'rolled-back';\n";
    script += "ROLLBACK;\n";

    var result = session.computeScript("sql", script);
    result.close();

    var query = session.query("SELECT FROM V WHERE name = 'rolled-back'");
    assertEquals("rolled-back record must not be visible",
        0, query.stream().count());
    query.close();
  }

  /**
   * A {@code LET} statement must register its alias via {@code scriptContext.declareScriptVariable}
   * (line 147-149). The simplest observable is that the alias is usable in a following statement.
   */
  @Test
  public void testLetStatementDeclaresScriptVariableForReuse() {
    session.begin();
    var script = "LET $label = 'via-let';\n";
    script += "insert into V set name = $label;\n";

    var result = session.computeScript("sql", script);
    result.close();
    session.commit();

    var query = session.query("SELECT FROM V WHERE name = 'via-let'");
    assertEquals("LET alias must propagate into the next statement",
        1, query.stream().count());
    query.close();
  }

  /**
   * {@code executeFunction} with an unknown function name must fail with a
   * {@link com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException} or NPE (the
   * no-such-function path throws from {@code FunctionLibrary.getFunction}). Pin that the call
   * does not silently return {@code null} — callers must see the failure rather than a phantom
   * empty result.
   *
   * <p>The exact exception class is not pinned (it varies by FunctionLibrary impl); what matters
   * is that it throws. The test uses the SqlScriptExecutor registered for {@code "sql"} via the
   * default {@link CommandManager}, obtained through the session's YouTrackDB root.
   */
  @Test
  public void testExecuteFunctionOnUnknownNameThrows() {
    session.begin();
    try {
      var ctx = new BasicCommandContext(session);
      var executor = new SqlScriptExecutor();
      Map<Object, Object> args = new HashMap<>();

      // Must throw — either CommandScriptException (wrap) or NPE from FunctionLibrary.
      assertThrows(RuntimeException.class,
          () -> executor.executeFunction(ctx, "__definitelyNotDefined__", args));
    } finally {
      if (session.isTxActive()) {
        session.commit();
      }
    }
  }
}
