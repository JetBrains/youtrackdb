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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformerImpl;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link Jsr223ScriptExecutor}, the JSR-223 fallback executor. Each test directly
 * instantiates a {@code new Jsr223ScriptExecutor("javascript", ...)} rather than flipping
 * {@link com.jetbrains.youtrackdb.api.config.GlobalConfiguration#SCRIPT_POLYGLOT_USE_GRAAL}: the
 * {@link ScriptManager} wires {@link PolyglotScriptExecutor} vs. {@link Jsr223ScriptExecutor} at
 * its own construction time (one per YouTrackDB instance), so a mid-process config toggle has no
 * effect. Driving a fresh {@code Jsr223ScriptExecutor} directly exercises exactly the JSR-223
 * code paths ({@code Compilable.compile}, {@code Invocable.invokeFunction}, {@code
 * NoSuchMethodException} wrap) regardless of what the shared ScriptManager holds.
 *
 * <p>The underlying JSR-223 ScriptEngine is obtained through
 * {@code scriptManager.acquireDatabaseEngine}, which returns whatever engine the JVM advertises
 * for "javascript" (typically Graal's JSR-223 factory when GraalJS is on the classpath). That
 * engine implements {@link javax.script.Compilable} and {@link javax.script.Invocable}, so the
 * happy paths through this executor are reachable.
 *
 * <p>The "language does not support compilation" branch
 * ({@code !(scriptEngine instanceof Compilable)}) is not reachable today without injecting a
 * non-Compilable engine into the ScriptManager — documented as a Track 22 coverage gap.
 */
public class Jsr223ScriptExecutorTest extends TestUtilsFixture {

  private Jsr223ScriptExecutor executor;

  // ==========================================================================
  // execute(script, params) — happy path, positional + named overloads.
  // ==========================================================================

  /**
   * {@code execute(session, "1+1", Object[])} must compile the script via {@code Compilable},
   * run it via {@link javax.script.CompiledScript#eval(javax.script.Bindings)}, and forward
   * the numeric result to the transformer. Pin the single-row numeric output from the default
   * transformer path.
   */
  @Test
  public void executePositionalArgsOverloadReturnsNumericResult() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());
    try (var rs = executor.execute(session, "1 + 1")) {
      assertNotNull(rs);
      assertTrue(rs.hasNext());
      final var first = rs.next();
      final Number n = first.getProperty("value");
      assertEquals(2.0, n.doubleValue(), 0.0);
    }
  }

  /**
   * {@code execute(session, script, Map)} must take the same compile/eval path as the
   * positional overload and return the same result. Pins the alternate overload — the compiled
   * script MUST reference the bound parameter by name so a regression that silently dropped
   * the params map would fail the eval with a {@code ReferenceError} (TB6 fix: previously the
   * script was {@code "5 + 7"} which ignored params entirely, making the map-overload delegate
   * unfalsifiable).
   */
  @Test
  public void executeMapArgsOverloadReturnsResult() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());
    final var params = new HashMap<String, Object>();
    params.put("x", 10);
    try (var rs = executor.execute(session, "x + 7", params)) {
      assertNotNull(rs);
      assertTrue(rs.hasNext());
      final Number n = rs.next().getProperty("value");
      assertEquals(17.0, n.doubleValue(), 0.0);
    }
  }

  /**
   * A multi-statement script exercises the Compilable/eval pipeline more fully — the script
   * defines a function in the engine scope and then invokes it. Pins the statement-block
   * compile path.
   */
  @Test
  public void executeMultiStatementScriptRunsAllStatements() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());
    final var script = "var a = 3; var b = 4; a * b;";
    try (var rs = executor.execute(session, script)) {
      assertTrue(rs.hasNext());
      final Number n = rs.next().getProperty("value");
      assertEquals(12.0, n.doubleValue(), 0.0);
    }
  }

  // ==========================================================================
  // Error paths — compilation failure routed through scriptManager.throwErrorMessage;
  // runtime ScriptException wrapped as CommandScriptException.
  // ==========================================================================

  /**
   * A syntactically invalid script fails {@link javax.script.Compilable#compile(String)} and
   * the executor calls {@code scriptManager.throwErrorMessage(...)}, which throws
   * {@link CommandScriptException}. Pin the throw-contract of the compile-error path.
   */
  @Test
  public void executeInvalidScriptThrowsCommandScriptException() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());
    // Malformed function declaration — fails the compile stage of Compilable.compile.
    final var broken = "function (";
    assertThrows(
        CommandScriptException.class,
        () -> {
          try (var rs = executor.execute(session, broken)) {
            // Exhaust the stream so any deferred throw surfaces.
            rs.stream().count();
          }
        });
  }

  /**
   * A script that compiles successfully but throws at runtime triggers
   * {@code CompiledScript.eval} → {@code ScriptException}, which the executor wraps as
   * {@link CommandScriptException}. Pin the runtime-error wrap shape.
   */
  @Test
  public void executeRuntimeFailureWrapsAsCommandScriptException() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());
    final var runtimeError = "throw new Error('boom');";
    assertThrows(
        CommandScriptException.class,
        () -> {
          try (var rs = executor.execute(session, runtimeError)) {
            rs.stream().count();
          }
        });
  }

  // ==========================================================================
  // executeFunction — Invocable path (stored JS function) with args and no args.
  // ==========================================================================

  /**
   * {@code executeFunction} must route through {@code Invocable.invokeFunction(name, args)} for
   * an Invocable JSR-223 engine. Create a stored JS function with no args, invoke it, and
   * assert the returned value. Pins the Invocable-dispatch path plus the
   * CommandExecutorUtility.transformResult + handleResult pipeline.
   */
  @Test
  public void executeFunctionViaInvocablePathReturnsValue() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());

    final var fname = "jsrExecFnNoArgs";
    createStoredFunction(fname, List.of(), "return 'pinned';");

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    final var result = executor.executeFunction(ctx, fname, new HashMap<>());
    assertEquals("pinned", result);
  }

  /**
   * {@code executeFunction} with named args — each map entry becomes a positional argument in
   * iteration order. Pin that {@code invokeFunction} receives the args in map-entry-iteration
   * order (deterministic for LinkedHashMap). TB5 fix: the body uses subtraction (a
   * non-commutative operation) so an argument reordering regression (swapping a and b) would
   * flip the result sign and fail the test — the earlier {@code return a + b;} form masked
   * ordering bugs because 10+20 == 20+10.
   */
  @Test
  public void executeFunctionWithArgsPassesArgumentsPositionally() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());

    final var fname = "jsrExecFnWithArgs";
    createStoredFunction(fname, List.of("a", "b"), "return a - b;");

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    final var args = new LinkedHashMap<Object, Object>();
    args.put("a", 30);
    args.put("b", 7);

    final var result = executor.executeFunction(ctx, fname, args);
    // GraalJS returns a polyglot Number for numeric subtraction; positional order must be
    // preserved — a regression that swapped args would yield -23, not 23.
    assertNotNull(result);
    assertEquals(
        "positional order must be preserved: a(30) - b(7) = 23, not b - a = -23",
        23,
        ((Number) result).intValue());
  }

  /**
   * {@code executeFunction} with a {@code null} args map must use an empty Object[] per the
   * {@code iArgs == null ? EMPTY_OBJECT_ARRAY : ...} branch. Function has no declared
   * parameters so the call still succeeds. Pins the null-args-use-empty-array path.
   */
  @Test
  public void executeFunctionWithNullArgsUsesEmptyArgArray() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());

    final var fname = "jsrExecFnNullArgs";
    createStoredFunction(fname, List.of(), "return 42;");

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    final var result = executor.executeFunction(ctx, fname, null);
    assertNotNull(result);
    assertEquals(42, ((Number) result).intValue());
  }

  /**
   * A stored JS function that returns {@code null} exercises the null-return path of
   * {@code CommandExecutorUtility.transformResult} — the executor returns Java null. Pin.
   */
  @Test
  public void executeFunctionReturningNullReturnsJavaNull() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());

    final var fname = "jsrExecFnReturnsNull";
    createStoredFunction(fname, List.of(), "return null;");

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    assertNull(executor.executeFunction(ctx, fname, new HashMap<>()));
  }

  /**
   * When the stored function's body references an undefined identifier, the JSR-223 engine
   * throws a {@link javax.script.ScriptException}. The executor wraps it as
   * {@link CommandScriptException} through the {@code catch (ScriptException e)} branch. Pin
   * the runtime-error wrap in the executeFunction path (distinct from the execute()
   * compilation-error wrap above).
   *
   * <p>TB7 fix: also pin the wrap preserves the original cause chain and the database name —
   * a regression that unwrapped the exception or dropped {@code BaseException.wrapException}
   * would yield a {@link CommandScriptException} with null cause, silently losing the
   * diagnostic trail to the underlying {@link javax.script.ScriptException}.
   */
  @Test
  public void executeFunctionRuntimeFailureWrapsAsCommandScriptException() {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());

    final var fname = "jsrExecFnRuntimeError";
    createStoredFunction(fname, List.of(), "undefinedVariable + 1;");

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    final var ex =
        assertThrows(
            CommandScriptException.class,
            () -> executor.executeFunction(ctx, fname, new HashMap<>()));
    assertNotNull(
        "runtime wrap must preserve the ScriptException as the cause chain",
        ex.getCause());
    // TB6 iter-2 strengthening: the wrap carries BOTH the dbName (attached via
    // BaseException.wrapException context chain) AND the function name (embedded by
    // executeFunction before calling wrapException). Assert each half independently so a
    // regression that drops ONE side of the diagnostic is not masked by the other —
    // previously the assertion used `||` which admitted either-one-missing.
    final var msg = ex.getMessage() == null ? "" : ex.getMessage();
    assertTrue(
        "wrapped exception message must include the function name: " + msg,
        msg.contains(fname));
    assertTrue(
        "wrapped exception message must include the dbName: " + msg,
        msg.contains(session.getDatabaseName()));
  }

  // ==========================================================================
  // Test fixture helpers — stored function creation scoped to the current DB.
  // ==========================================================================

  /**
   * Create a stored JavaScript function in the current database. Parameter names are passed
   * through to {@link Function#setParameters} so the function formatter generates the correct
   * signature for getFunctionInvoke() callers.
   *
   * <p>Uses the direct {@code new Function(session)} idiom (see {@code ScriptManagerTest} Step
   * 4a for the rationale): the interface-level
   * {@code FunctionLibrary.createFunction(String)} commits the entity with the default
   * language ("sql"), so setting language/code after that call has no persistent effect.
   */
  private Function createStoredFunction(String name, List<String> params, String code) {
    session.begin();
    final var f = new Function(session);
    f.setName(name);
    f.setCode(code);
    f.setLanguage("javascript");
    if (params != null && !params.isEmpty()) {
      f.setParameters(params);
    }
    f.save(session);
    session.commit();
    return session.getMetadata().getFunctionLibrary().getFunction(session, name);
  }
}
