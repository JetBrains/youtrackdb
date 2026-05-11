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
import java.util.Map;
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
   * assert the returned value. Pins the Invocable-dispatch path plus the {@code handleResult}
   * pipeline.
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
   * A stored JS function that returns {@code null} causes the executor to return Java null
   * straight through the {@code handleResult} pass-through. Pin.
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
   * Pin the {@link NoSuchMethodException} catch arm of {@code executeFunction}
   * (Jsr223ScriptExecutor:142-146). The arm fires when {@code Invocable.invokeFunction(name,
   * args)} is called for a function the engine's currently-loaded library code does not
   * declare — the JSR-223 contract is that the engine throws {@link NoSuchMethodException}
   * and the executor wraps it as a {@link CommandScriptException} tagged with the function
   * name and the database name.
   *
   * <p>Reproduction technique. The executor's lookup-vs-invocation seam allows a
   * fabricated mismatch between the {@code FunctionLibrary} key under which the
   * {@link Function} entity is registered and the {@link Function#getName()} the entity
   * itself reports. The {@code JSScriptFormatter.getFunctionDefinition} wraps the body in
   * {@code function <f.getName()>(...) { ... }} when generating the per-engine library,
   * so the JS engine ends up with a declaration named after the entity's INTERNAL
   * {@code getName()}. The executor's downstream
   * {@code Invocable.invokeFunction(functionName, ...)} call (executeFunction:128) uses
   * the PARAMETER {@code functionName} — which is the library lookup key, not the
   * entity name — so when the two diverge, the engine has no declaration matching the
   * invocation name and {@link NoSuchMethodException} fires.
   *
   * <p>The test fabricates the mismatch by reaching into the {@code FunctionLibraryImpl}
   * {@code functions} map via reflection (the map is the source-of-truth for
   * {@code getFunction(name)}) and inserting a {@link Function} entity under one key
   * whose persisted {@code name} property is different. This produces the
   * {@link NoSuchMethodException} branch without any racy timing assumption — the
   * mismatch is structural.
   *
   * <p>Falsifiable: a regression that re-routed this catch into the
   * {@code ScriptException} arm above would still throw {@link CommandScriptException}
   * but with a non-{@link NoSuchMethodException} cause; the cause-chain walk below
   * catches that regression. A regression that dropped the catch arm entirely would
   * surface the raw {@link NoSuchMethodException} past the executor.
   */
  @Test
  public void executeFunctionWrapsNoSuchMethodExceptionFromStaleEngineLibrary() throws Exception {
    executor = new Jsr223ScriptExecutor("javascript", new ScriptTransformerImpl());

    // Step 1: register a real Function entity with internal name "declared" — the library
    // generator emits "function declared() { return 1; }" so the engine has a function
    // named "declared".
    final var declared = "declared" + uniqueAlnumSuffix();
    createStoredFunction(declared, List.of(), "return 1;");

    // Step 2: now reach into FunctionLibraryImpl's internal map and ALSO register the
    // SAME Function entity under a SECOND key — "ghost". The map is keyed by
    // uppercase(name), so {@code getFunction("ghost")} resolves to the same Function
    // entity whose .getName() returns "declared". When the executor's executeFunction
    // path is called with "ghost":
    //   - line 96 lookup succeeds (returns the entity).
    //   - line 103 acquireDatabaseEngine returns an engine whose library code defines
    //     "declared" (the entity's getName()), NOT "ghost".
    //   - line 128 invokeFunction("ghost", args) → NoSuchMethodException, caught at 142.
    final var ghost = "ghost" + uniqueAlnumSuffix();
    final var lib = session.getMetadata().getFunctionLibrary();
    final var declaredFn = lib.getFunction(session, declared);
    assertNotNull("preflight: declared function must be reachable via FunctionLibrary",
        declaredFn);

    // FunctionLibraryImpl declares `private Map<String, Function> functions` (with various
    // names across history — accessor or field). Reflectively access the field.
    // session.getMetadata().getFunctionLibrary() returns a FunctionLibraryProxy whose
    // `delegate` field (declared on ProxedResource) is the real FunctionLibraryImpl. The
    // FunctionLibraryImpl carries the {@code protected final ConcurrentHashMap<String,
    // Function> functions} map that is the source-of-truth for getFunction(name). Reach
    // through the proxy to the delegate, then into the map.
    final var proxyDelegateField =
        com.jetbrains.youtrackdb.internal.core.db.record.ProxedResource.class.getDeclaredField(
            "delegate");
    proxyDelegateField.setAccessible(true);
    final var impl = proxyDelegateField.get(lib);
    final java.lang.reflect.Field functionsField;
    try {
      functionsField = impl.getClass().getDeclaredField("functions");
    } catch (NoSuchFieldException nsfe) {
      // FunctionLibraryImpl renamed the field; falsifiable preflight failure rather than a
      // silent skip. WHEN-FIXED: rename in FunctionLibraryImpl will require updating this
      // reflective handle.
      throw new AssertionError(
          "FunctionLibraryImpl.functions field rename detected — pin needs adjustment");
    }
    functionsField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var functionsMap = (Map<String, Function>) functionsField.get(impl);
    // Insert the SAME Function entity under the new "ghost" key (uppercased per the
    // FunctionLibraryImpl convention). The library code generator iterates
    // getFunctionNames() — which is functionsMap.keySet() — so "ghost" appears in the
    // iteration, but getFunctionDefinition emits the entity's getName(), i.e. "declared",
    // for BOTH iteration steps (the same entity is emitted twice with the same JS
    // declaration). The engine never gains a JS function named "ghost".
    functionsMap.put(ghost.toUpperCase(java.util.Locale.ENGLISH), declaredFn);

    final var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    final CommandScriptException ex;
    try {
      ex = assertThrows(
          "invokeFunction(ghost) on engine that only declared 'declared' must wrap "
              + "NoSuchMethodException as CommandScriptException",
          CommandScriptException.class,
          () -> executor.executeFunction(ctx, ghost, new HashMap<>()));
    } finally {
      // Cleanup: remove the fabricated key so sibling tests in this class see a clean
      // FunctionLibrary state. The "declared" function remains in the library for
      // subsequent DbTestBase teardown to clean up.
      functionsMap.remove(ghost.toUpperCase(java.util.Locale.ENGLISH));
    }

    // Wrap-contract pin: the BaseException.wrapException chain preserves the original
    // NoSuchMethodException somewhere in the cause chain. Walk the chain to find it.
    Throwable cause = ex.getCause();
    var foundNoSuchMethod = false;
    while (cause != null && !foundNoSuchMethod) {
      if (cause instanceof NoSuchMethodException) {
        foundNoSuchMethod = true;
      } else {
        cause = cause.getCause();
      }
    }
    assertTrue(
        "wrap-contract: the cause chain must surface a NoSuchMethodException — "
            + "a regression that routed the catch through the ScriptException arm "
            + "would produce a ScriptException cause instead",
        foundNoSuchMethod);

    // Wrap-context pins: both the function name and the database name must appear in
    // the wrapped exception message (matches the existing
    // executeFunctionRuntimeFailureWrapsAsCommandScriptException contract). Assert both
    // halves independently so a regression dropping ONE half is not masked by the other.
    final var msg = ex.getMessage() == null ? "" : ex.getMessage();
    assertTrue(
        "wrapped exception message must include the missing function name: " + msg,
        msg.contains(ghost));
    assertTrue(
        "wrapped exception message must include the dbName: " + msg,
        msg.contains(session.getDatabaseName()));
  }

  /**
   * Per-test alphanumeric-only suffix. The {@code FunctionLibrary.validateFunctionRecord}
   * gate accepts only {@code [A-Za-z][A-Za-z0-9_]*} for the persisted entity name, so
   * dashes and special characters from a method-name + nanoTime concatenation must be
   * stripped before the name is passed to {@code createFunction}.
   */
  private String uniqueAlnumSuffix() {
    return (name.getMethodName().replaceAll("[^a-zA-Z0-9]", "_") + "_" + System.nanoTime());
  }

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
