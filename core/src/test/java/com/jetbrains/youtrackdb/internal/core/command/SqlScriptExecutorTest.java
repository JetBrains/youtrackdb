package com.jetbrains.youtrackdb.internal.core.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests SQL script execution including plain scripts and parameterized scripts.
 */
public class SqlScriptExecutorTest extends DbTestBase {

  /**
   * Rollback any transaction left open by a failing test method before {@link
   * DbTestBase#afterTest()} drops the database. Track 7/8 precedent: failing script tests (e.g.,
   * the {@code COMMIT RETRY 0} path) can abort mid-begin and leave a dangling transaction that
   * cascade-poisons the close path. JUnit 4 runs subclass {@code @After} before superclass
   * {@code @After}, so this runs ahead of teardown.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

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

    try (var result = session.computeScript("sql", script)) {
      // Consume nothing — insert runs on close of the execution plan.
    }
    session.commit();

    try (var query = session.query("SELECT FROM V WHERE name = 'auto-terminated'")) {
      assertEquals(1, query.stream().count());
    }
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
    final var script =
        "BEGIN;\ninsert into V set name = 'retry-ok';\nCOMMIT RETRY 3;\n";

    try (var result = session.computeScript("sql", script)) {
      // Statements execute as part of the plan — consuming the ResultSet is not required.
    }

    try (var query = session.query("SELECT FROM V WHERE name = 'retry-ok'")) {
      assertEquals("retry block must execute exactly once when no conflict",
          1, query.stream().count());
    }
  }

  /**
   * A {@code BEGIN}/{@code ROLLBACK} script exercises the rollback branch at lines 136-144:
   * pending retry-block statements are still chained onto the plan (and therefore executed and
   * then rolled back) and {@code nestedTxLevel} resets to zero. Pin the observed behavior: the
   * inserted record does NOT persist.
   */
  @Test
  public void testBeginRollbackRollsBackBufferedStatements() {
    final var script =
        "BEGIN;\ninsert into V set name = 'rolled-back';\nROLLBACK;\n";

    try (var result = session.computeScript("sql", script)) {
      // Empty consume — BEGIN/ROLLBACK plan is what's being tested.
    }

    try (var query = session.query("SELECT FROM V WHERE name = 'rolled-back'")) {
      assertEquals("rolled-back record must not be visible",
          0, query.stream().count());
    }
  }

  /**
   * A {@code LET} statement must register its alias via {@code scriptContext.declareScriptVariable}
   * (line 147-149). The simplest observable is that the alias is usable in a following statement.
   */
  @Test
  public void testLetStatementDeclaresScriptVariableForReuse() {
    session.begin();
    final var script =
        "LET $label = 'via-let';\ninsert into V set name = $label;\n";

    try (var result = session.computeScript("sql", script)) {
      // Plan execution is the observable; no row iteration needed.
    }
    session.commit();

    try (var query = session.query("SELECT FROM V WHERE name = 'via-let'")) {
      assertEquals("LET alias must propagate into the next statement",
          1, query.stream().count());
    }
  }

  /**
   * {@code executeFunction} with an unknown function name must fail visibly rather than silently
   * returning {@code null}. The function-lookup path dereferences the result of {@code
   * FunctionLibrary.getFunction} without a null guard, which today throws a
   * {@link NullPointerException}. We pin that specific shape so a refactor that quietly returns
   * {@code null} (or broadens to a different exception not rooted in function lookup) is caught.
   *
   * <p>WHEN-FIXED: Track 22 — if {@code FunctionLibrary.getFunction} gains a null-guard and the
   * unknown-name path starts throwing {@link CommandScriptException} with a message naming the
   * missing function, flip this assertion to pin that instead; the NPE shape is observed state,
   * not a desired contract.
   */
  @Test
  public void testExecuteFunctionOnUnknownNameThrows() {
    session.begin();
    var ctx = new BasicCommandContext(session);
    var executor = new SqlScriptExecutor();
    Map<Object, Object> args = new HashMap<>();

    // Observed shape today: NPE on the function.getName() call because getFunction returned null.
    // The pin is a falsifiable regression: if we start returning null from executeFunction, the
    // NPE stops being thrown and this test fails.
    assertThrows(NullPointerException.class,
        () -> executor.executeFunction(ctx, "__definitelyNotDefined__", args));
  }
}
