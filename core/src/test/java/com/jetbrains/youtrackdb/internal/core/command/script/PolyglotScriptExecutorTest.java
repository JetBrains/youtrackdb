/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.command.script;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.concur.resource.ResourcePool;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformerImpl;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link PolyglotScriptExecutor}, the GraalVM-based JavaScript executor. Each test
 * creates a fresh {@link PolyglotScriptExecutor} with a fresh {@link ScriptTransformerImpl} so
 * pool state never leaks between tests. The executor needs a live {@link DatabaseSessionEmbedded}
 * — it reaches into {@code database.getSharedContext().getYouTrackDB().getScriptManager()} for
 * bindings, library code, and function-formatter lookups — so tests run under {@link DbTestBase}.
 *
 * <p>Tests do NOT mutate {@code GlobalConfiguration.SCRIPT_POLYGLOT_USE_GRAAL}: directly
 * instantiating {@link PolyglotScriptExecutor} forces the Graal path regardless of the
 * process-wide flag, and GraalVM JS is required by other tests in this module (e.g.
 * {@link JSScriptTest}), so the executor always has a runtime to drive.
 *
 * <p>Pool internals are inspected via reflection on the {@code protected contextPools} field.
 * Direct observation is preferred over indirect behavioral inference because the key coverage
 * target — {@link PolyglotScriptExecutor#close(String)} and
 * {@link PolyglotScriptExecutor#closeAll()} — must be proven to clear the map, not merely to
 * disable the pool.
 */
public class PolyglotScriptExecutorTest extends TestUtilsFixture {

  private PolyglotScriptExecutor executor;

  @After
  public void closeExecutor() {
    if (executor != null) {
      // Drain any pools the test left behind so sibling tests do not observe cached Graal
      // contexts on the shared dbName. closeAll is idempotent — safe to call unconditionally.
      try {
        executor.closeAll();
      } catch (Exception ignored) {
        // Best-effort cleanup; test outcome is already decided.
      }
    }
  }

  // ==========================================================================
  // Constructor — language normalization.
  // ==========================================================================

  /**
   * The ctor normalizes the lowercase "javascript" alias to "js" because the GraalVM Polyglot
   * API expects the canonical language id. This pins the lowercase-alias branch.
   *
   * <p>TS3 fix: split from a bundled three-scenario test into three focused tests so each
   * failure points at the specific alias case that regressed (the earlier form masked which
   * branch failed because the first assertion failure stopped the test immediately).
   */
  @Test
  public void ctorNormalizesLowercaseJavascriptAliasToJs() throws Exception {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    assertEquals("js", readLanguage(executor));
  }

  /**
   * The ctor normalizes the mixed-case "JavaScript" alias to "js". Case-insensitive alias
   * handling is a separate branch from the lowercase alias.
   */
  @Test
  public void ctorNormalizesMixedCaseJavaScriptAliasToJs() throws Exception {
    executor = new PolyglotScriptExecutor("JavaScript", new ScriptTransformerImpl());
    assertEquals("js", readLanguage(executor));
  }

  /**
   * Non-alias languages (e.g., "python") must be passed through verbatim — pin to catch
   * accidental over-generalisation of the normalization rule (e.g., lowercasing everything).
   */
  @Test
  public void ctorPassesThroughNonAliasLanguageVerbatim() throws Exception {
    executor = new PolyglotScriptExecutor("python", new ScriptTransformerImpl());
    assertEquals("python", readLanguage(executor));
  }

  // ==========================================================================
  // reuseResource — always returns true, contexts are reusable.
  // ==========================================================================

  /**
   * The pool listener contract requires {@code reuseResource} to decide whether a pooled
   * {@link Context} is safe to hand back. For Polyglot the answer is always "yes" (contexts are
   * stateless across invocations after the library is pre-evaluated). Pin the behavior — a
   * future change that introduces per-script state must flip this to false.
   */
  @Test
  public void reuseResourceAlwaysReturnsTrue() {
    executor = new PolyglotScriptExecutor("js", new ScriptTransformerImpl());
    try (var ctx = Context.newBuilder("js").build()) {
      assertTrue(executor.reuseResource(session, new Object[0], ctx));
    }
  }

  // ==========================================================================
  // execute(script, Object...) — scalar / array / null / host-object result
  // paths. Drives ScriptTransformerImpl.toResultSet indirectly (R2 hitchhiker).
  // ==========================================================================

  /**
   * {@code execute(session, "1+1", Object...)} must return a non-null ResultSet containing one
   * row with a numeric "value" property. Pins the scalar-result path through the transformer's
   * {@code isNumber} branch (emits Double) and the {@code defaultResultSet} wrap.
   */
  @Test
  public void executeScalarScriptReturnsOneRowWithNumericValue() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    try (var rs = executor.execute(session, "1 + 1")) {
      assertNotNull(rs);
      assertTrue(rs.hasNext());
      final var first = rs.next();
      final Number n = first.getProperty("value");
      assertEquals(2.0, n.doubleValue(), 0.0);
      assertFalse("scalar result must yield exactly one row", rs.hasNext());
    }
  }

  /**
   * The transformer's {@code hasArrayElements} branch currently assumes each element is a
   * host-backed object (it calls {@code v.getArrayElement(i).asHostObject()} unconditionally),
   * so a pure JS array of primitives throws a {@link ClassCastException} from
   * {@code Value.asHostObject}. The executor's {@code catch (PolyglotException)} does NOT
   * catch ClassCastException, so the raw exception propagates to the caller. Pin the observed
   * shape. WHEN-FIXED: Track 22 — the transformer should fall through
   * {@code isNumber}/{@code isString} for non-host array elements; when that's fixed, this
   * test should flip to asserting the row count equals the array size (3 for {@code
   * [1, 2, 3]}).
   */
  @Test
  public void executeJsPrimitiveArrayThrowsClassCastFromAsHostObject() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    assertThrows(
        ClassCastException.class,
        () -> {
          try (var rs = executor.execute(session, "[1, 2, 3]")) {
            // Draining forces the transformer to run over every element.
            rs.stream().count();
          }
        });
  }

  /**
   * {@code null} is the polyglot's {@code isNull} branch: the transformer returns
   * {@code null} directly (see {@link
   * com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformerImpl#toResultSet}
   * first branch). Pin the observed {@code null} ResultSet shape. WHEN-FIXED: Track 22 —
   * the transformer should return an empty ResultSet instead of null to spare consumers a
   * null-check; when that's fixed, this test should assert non-null + hasNext=false.
   */
  @Test
  public void executeNullScriptReturnsNullResultSetAsShapePin() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    assertNull(
        "Graal null result currently yields a null ResultSet (transformer short-circuits)",
        executor.execute(session, "null"));
  }

  /**
   * TC3 iter-2 boundary pin: an EMPTY JavaScript source — {@code ""} — is a common user input
   * (empty editor, failed templating). Graal's {@code Context.eval} on an empty script returns
   * a "null" / "undefined" Value; the transformer's {@code toResultSet} null-short-circuit
   * branch then returns {@code null}. Pin this current observable shape. WHEN-FIXED: Track 22 —
   * when the transformer returns an empty ResultSet instead of null, flip this to assert
   * non-null + hasNext=false.
   */
  @Test
  public void executeEmptyScriptReturnsNullResultSetAsShapePin() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    assertNull(
        "empty JS script currently maps to null ResultSet via transformer null-branch",
        executor.execute(session, ""));
  }

  /**
   * TC3 iter-2 boundary pin: whitespace-only scripts ({@code "   \n\t   "}) also evaluate to a
   * null/undefined Value in Graal and take the same transformer null-branch as the empty
   * script. Pin the current shape.
   */
  @Test
  public void executeWhitespaceOnlyScriptReturnsNullResultSetAsShapePin() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    assertNull(
        "whitespace-only JS script currently maps to null ResultSet via transformer null-branch",
        executor.execute(session, "   \n\t   "));
  }

  /**
   * A JavaScript string literal triggers the transformer's {@code isString} branch. The
   * returned Java String is the transformer's default-path scalar, wrapped via
   * {@code defaultResultSet} into a single-row result whose "value" property is the string.
   */
  @Test
  public void executeStringScriptReturnsStringValueInOneRow() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    try (var rs = executor.execute(session, "'hello'")) {
      assertNotNull(rs);
      assertTrue(rs.hasNext());
      final var first = rs.next();
      assertEquals("hello", first.getProperty("value"));
    }
  }

  // ==========================================================================
  // Error handling — PolyglotException wrap path.
  // ==========================================================================

  /**
   * A script with a syntax error triggers a {@code PolyglotException} from Graal; the executor
   * wraps it as a {@link CommandScriptException} that carries the database name, the offending
   * script, and the source column number. Pin the wrap shape and that the underlying script
   * text / cause chain are preserved.
   *
   * <p>TB2 fix (Phase C iter-1): the earlier form asserted only {@code getMessage().length()
   * > 0}, which any non-empty string satisfies — a regression that dropped {@code
   * BaseException.wrapException} or replaced the rich ctor with a constant-message wrap would
   * pass unnoticed. Pin the two load-bearing wrap properties: (1) the database name is
   * embedded in the message context, and (2) the cause chain reaches the original
   * {@link PolyglotException}.
   */
  @Test
  public void executeSyntaxErrorScriptWrapsPolyglotExceptionAsCommandScriptException() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    final var broken = "function (";
    final var ex =
        assertThrows(
            CommandScriptException.class, () -> executor.execute(session, broken, new Object[0]));
    assertNotNull("exception must carry a non-null message", ex.getMessage());
    assertTrue(
        "wrapped exception must echo the database name for diagnostics: " + ex.getMessage(),
        ex.getMessage().contains(session.getDatabaseName()));
    // Production uses BaseException.wrapException(new CommandScriptException(...), pe, dbName)
    // so the cause chain must reach the originating PolyglotException.
    assertNotNull("wrap must preserve the original cause", ex.getCause());
    Throwable root = ex.getCause();
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    assertTrue(
        "root cause chain must reach PolyglotException (actual: " + root.getClass() + ")",
        root instanceof PolyglotException);
  }

  // ==========================================================================
  // Pool lifecycle — contextPools is populated on first execute for a dbName;
  // close(dbName) removes that entry; closeAll empties the map; repeated
  // execute on the same dbName reuses the pool via computeIfAbsent (R5 pin).
  // ==========================================================================

  /**
   * On construction the {@code contextPools} map is empty — lazily populated on first
   * {@code execute}. Pin the lazy-init invariant.
   */
  @Test
  public void contextPoolsStartsEmpty() throws Exception {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    assertTrue("contextPools must start empty", pools(executor).isEmpty());
  }

  /**
   * After a single {@code execute} on the session's database, {@code contextPools} contains
   * exactly one entry keyed by the database name. Pin that execute establishes the pool and
   * keys it by {@code database.getDatabaseName()}.
   */
  @Test
  public void executePopulatesContextPoolsKeyedByDatabaseName() throws Exception {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    try (var rs = executor.execute(session, "1")) {
      rs.stream().count();
    }

    final var map = pools(executor);
    assertEquals("exactly one pool must exist after one execute", 1, map.size());
    assertTrue(
        "pool must be keyed by the database name",
        map.containsKey(session.getDatabaseName()));
  }

  /**
   * R5 pin — {@code resolveContext} uses {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent}
   * atomically. Two sequential {@code execute} calls on the same database must yield the same
   * {@link ResourcePool} instance (the map value is never recomputed). Pinning same-instance
   * equality is the observable proof that computeIfAbsent did not create a second pool.
   * WHEN-FIXED: Track 22 — if resolveContext is refactored to synchronized/lock-based caching,
   * this test remains valid as long as pool identity is preserved.
   */
  @Test
  public void resolveContextReturnsSamePoolAcrossExecutes() throws Exception {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    try (var rs1 = executor.execute(session, "1")) {
      rs1.stream().count();
    }
    final var firstPool = pools(executor).get(session.getDatabaseName());

    try (var rs2 = executor.execute(session, "2")) {
      rs2.stream().count();
    }
    final var secondPool = pools(executor).get(session.getDatabaseName());

    assertSame(
        "computeIfAbsent must not create a second pool for the same database name",
        firstPool,
        secondPool);
  }

  /**
   * {@code close(dbName)} removes the entry and closes every pooled Context. Pin the map
   * eviction and that a subsequent execute on the same dbName rebuilds the pool (proves the
   * previous entry was actually gone, not just flagged).
   */
  @Test
  public void closeRemovesContextPoolForDbName() throws Exception {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    try (var rs = executor.execute(session, "1")) {
      rs.stream().count();
    }

    final var removedPool = pools(executor).get(session.getDatabaseName());
    assertNotNull("pool must exist before close", removedPool);

    executor.close(session.getDatabaseName());

    assertFalse(
        "contextPools must no longer contain the dbName after close",
        pools(executor).containsKey(session.getDatabaseName()));

    // Re-executing rebuilds a fresh pool — different identity from the removed one.
    try (var rs = executor.execute(session, "1")) {
      rs.stream().count();
    }
    final var rebuiltPool = pools(executor).get(session.getDatabaseName());
    assertNotNull(rebuiltPool);
    // The pool instance before close and after rebuild must not be the same — pin that close
    // fully discarded the old pool rather than reusing it.
    assertNotSame(
        "rebuilt pool must not reuse the closed pool instance", removedPool, rebuiltPool);
  }

  /**
   * {@code close(unknownDbName)} must be a no-op — {@code contextPools.remove} returns null
   * and the null-guard skips the close loop. Pin no-throw on an unknown key.
   */
  @Test
  public void closeUnknownDbNameIsNoOp() throws Exception {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    final var before = pools(executor).size();
    executor.close("not-a-database");
    assertEquals("close on an unknown dbName must not mutate contextPools", before,
        pools(executor).size());
  }

  /**
   * {@code closeAll()} must close every pool and clear the map. Pin both invariants: the map
   * is empty afterwards AND a subsequent execute rebuilds a pool (not reuses a zombie).
   */
  @Test
  public void closeAllEmptiesTheContextPoolsMap() throws Exception {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());
    try (var rs = executor.execute(session, "1")) {
      rs.stream().count();
    }
    assertFalse(pools(executor).isEmpty());

    executor.closeAll();

    assertTrue("closeAll must empty contextPools", pools(executor).isEmpty());

    // Rebuild via execute — proves the map was fully cleared, not just disabled.
    try (var rs = executor.execute(session, "1")) {
      rs.stream().count();
    }
    assertEquals(1, pools(executor).size());
  }

  // ==========================================================================
  // executeFunction — stored JS function; covers the function-invoke path
  // through bindContextVariables + ctx.eval(functionInvoke).
  // ==========================================================================

  /**
   * {@code executeFunction} invokes a stored JavaScript function by name. Create a Function
   * with a trivial body that returns a number; invoke it through the executor and assert the
   * numeric return is coerced to Double per the {@code isNumber} branch.
   *
   * <p>Also pins that the executor correctly navigates context→databaseSession→scriptManager,
   * fetches the Function from the function library, runs the security check, and finally
   * coerces the polyglot Value to a Java Double via {@code result.asDouble()}.
   */
  @Test
  public void executeFunctionReturnsNumericResultAsDouble() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());

    final var fname = "polyExecFnNumber";
    createStoredFunction(fname, "return 21 + 21;");

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    final var result = executor.executeFunction(ctx, fname, new HashMap<>());
    assertTrue("numeric function result must coerce to Double", result instanceof Double);
    assertEquals(42.0, (Double) result, 0.0);
  }

  /**
   * A function returning {@code null} exercises the {@code isNull} branch of
   * {@code executeFunction} — the method returns Java {@code null}. Pin the null-mapping
   * contract.
   */
  @Test
  public void executeFunctionReturningNullMapsToJavaNull() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());

    final var fname = "polyExecFnNull";
    createStoredFunction(fname, "return null;");

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    assertNull(
        "polyglot 'null' return must map to Java null",
        executor.executeFunction(ctx, fname, new HashMap<>()));
  }

  /**
   * A function returning a string exercises the {@code isString} branch — the executor returns
   * the native Java String via {@code result.asString()}. Pin the string-mapping contract.
   */
  @Test
  public void executeFunctionReturningStringMapsToJavaString() {
    executor = new PolyglotScriptExecutor("javascript", new ScriptTransformerImpl());

    final var fname = "polyExecFnString";
    createStoredFunction(fname, "return 'pinned';");

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    final var result = executor.executeFunction(ctx, fname, new HashMap<>());
    assertEquals("pinned", result);
  }

  // ==========================================================================
  // Reflection helpers — inspect protected state without widening visibility.
  // ==========================================================================

  /**
   * Read the protected {@code contextPools} map from an executor instance. Encapsulated here so
   * a future refactor to the field name or visibility touches exactly one place.
   */
  @SuppressWarnings("unchecked")
  private static ConcurrentHashMap<
      String, ResourcePool<DatabaseSessionEmbedded, Context>>
      pools(PolyglotScriptExecutor e) throws Exception {
    final var f = PolyglotScriptExecutor.class.getDeclaredField("contextPools");
    f.setAccessible(true);
    return (ConcurrentHashMap<String, ResourcePool<DatabaseSessionEmbedded, Context>>) f.get(e);
  }

  /**
   * Read the {@code language} field from AbstractScriptExecutor (the superclass). Encapsulated
   * so the normalization assertion does not depend on a public getter.
   */
  private static String readLanguage(PolyglotScriptExecutor e) throws Exception {
    final var f =
        Class.forName(
            "com.jetbrains.youtrackdb.internal.core.command.traverse.AbstractScriptExecutor")
            .getDeclaredField("language");
    f.setAccessible(true);
    return (String) f.get(e);
  }

  // ==========================================================================
  // Test fixture helpers.
  // ==========================================================================

  /**
   * Create a stored JavaScript function in the current database's function library. Uses the
   * direct {@code new Function(session)} idiom (see {@code ScriptManagerTest} Step 4a) because
   * the interface-level {@code FunctionLibrary.createFunction(String)} commits the entity
   * with the default "sql" language, overriding any post-call setter on the in-memory
   * Function.
   */
  private Function createStoredFunction(String name, String code) {
    session.begin();
    final var f = new Function(session);
    f.setName(name);
    f.setCode(code);
    f.setLanguage("javascript");
    f.save(session);
    session.commit();
    return session.getMetadata().getFunctionLibrary().getFunction(session, name);
  }
}
