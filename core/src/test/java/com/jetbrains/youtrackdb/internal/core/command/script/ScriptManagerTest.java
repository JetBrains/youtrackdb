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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.script.formatter.JSScriptFormatter;
import com.jetbrains.youtrackdb.internal.core.command.script.formatter.SQLScriptFormatter;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionUtilWrapper;
import com.jetbrains.youtrackdb.internal.core.sql.SQLScriptEngine;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for {@link ScriptManager} covering: engine/formatter/result-handler/injection registries,
 * binding helpers (bindContextVariables, bind, bindLegacyDatabaseAndUtil, unbind), library code
 * generation, allowed-packages security, the Rhino-fallback line-extraction branch of
 * {@code throwErrorMessage}, and per-database engine pool lifecycle.
 *
 * <p>{@link SequentialTest} because {@link ScriptManager#addAllowedPackages(Set)} and
 * {@link ScriptManager#closeAll()} mutate process-wide state on the shared {@link ScriptManager}
 * reachable via {@link YouTrackDBInternalEmbedded#getScriptManager()} (one ScriptManager per
 * YouTrackDB instance, shared across all sessions on that instance). Running in parallel with
 * sibling script tests would produce non-deterministic engine pool state. The
 * {@link #restoreAllowedPackages()} safety net in {@code @After} removes any packages added by
 * a failing test so sibling classes never observe leaked state.
 */
@Category(SequentialTest.class)
public class ScriptManagerTest extends DbTestBase {

  private ScriptManager scriptManager;
  private Set<String> snapshotPackages;

  @Before
  public void grabScriptManager() {
    scriptManager =
        ((YouTrackDBInternalEmbedded) YouTrackDBInternal.extract(youTrackDB)).getScriptManager();
    snapshotPackages = new HashSet<>(scriptManager.getAllowedPackages());
  }

  /**
   * Safety net: any package added by a failing test is removed here so sibling script tests
   * (JSScriptTest, etc.) do not observe leaked allowed-package state. No-op when the test
   * restored packages cleanly in its own finally block.
   *
   * <p>CQ4/TS5: also roll back any leftover transaction so an assertion that fails between
   * {@code session.begin()} and {@code session.commit()} does not cascade-poison sibling
   * tests inside this class (DbTestBase creates a fresh YouTrackDB per test, so cross-class
   * leakage is bounded, but within-class leakage through the sequential fixture is still
   * possible if a @Test throws mid-transaction).
   */
  @After
  public void restoreAllowedPackagesAndRollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
    if (scriptManager == null) {
      return;
    }
    final var current = scriptManager.getAllowedPackages();
    final var toRemove = new HashSet<>(current);
    toRemove.removeAll(snapshotPackages);
    if (!toRemove.isEmpty()) {
      scriptManager.removeAllowedPackages(toRemove);
      scriptManager.closeAll();
    }
  }

  // ==========================================================================
  // Engine registry: getEngine(lang), existsEngine, registerEngine,
  // getSupportedLanguages
  // ==========================================================================

  @Test
  public void getEngineNullLanguageThrowsCommandScriptException() {
    final var dbName = session.getDatabaseName();
    final var ex =
        assertThrows(CommandScriptException.class, () -> scriptManager.getEngine(dbName, null));
    assertTrue(ex.getMessage().contains("No language was specified"));
  }

  @Test
  public void getEngineUnknownLanguageThrowsWithSupportedList() {
    final var dbName = session.getDatabaseName();
    final var ex =
        assertThrows(
            CommandScriptException.class,
            () -> scriptManager.getEngine(dbName, "definitely-not-a-language-xyz"));
    assertTrue(ex.getMessage().contains("Unsupported language"));
    // The error lists supported languages so the user can correct the typo.
    assertTrue(ex.getMessage().contains("Supported languages are"));
  }

  @Test
  public void getEngineForSqlLanguageReturnsSqlScriptEngine() {
    final var engine = scriptManager.getEngine(session.getDatabaseName(), "sql");
    assertNotNull(engine);
    assertTrue(
        "Expected SQLScriptEngine for language 'sql', got " + engine.getClass().getName(),
        engine instanceof SQLScriptEngine);
  }

  @Test
  public void getEngineLanguageIsCaseInsensitive() {
    final var lower = scriptManager.getEngine(session.getDatabaseName(), "sql");
    final var upper = scriptManager.getEngine(session.getDatabaseName(), "SQL");
    assertNotNull(lower);
    assertNotNull(upper);
    // Same registered factory should produce the same engine type.
    assertEquals(lower.getClass(), upper.getClass());
  }

  @Test
  public void existsEngineReturnsFalseForNull() {
    assertFalse(scriptManager.existsEngine(null));
  }

  @Test
  public void existsEngineForSqlReturnsTrue() {
    assertTrue(scriptManager.existsEngine("sql"));
    // Case-insensitive lookup (lowercased via Locale.ENGLISH internally).
    assertTrue(scriptManager.existsEngine("SQL"));
  }

  @Test
  public void existsEngineReturnsFalseForUnknownLanguage() {
    assertFalse(scriptManager.existsEngine("never-registered-lang"));
  }

  @Test
  public void getSupportedLanguagesContainsSql() {
    final var supported = scriptManager.getSupportedLanguages();
    final var set = new HashSet<String>();
    supported.forEach(set::add);
    assertTrue("supported must contain sql: " + set, set.contains("sql"));
  }

  @Test
  public void registerEngineRoundTripReturnsSameManager() {
    // Spy factory so we can observe the registered instance via getScriptEngine().
    // Lowercase language key because existsEngine/getEngine lowercase the lookup but
    // registerEngine stores the key verbatim — convention enforced by ScriptManager:100.
    final var spy = new SpyEngineFactory();
    final var langKey = "mytestlang" + uniqueSuffix();
    final var returned = scriptManager.registerEngine(langKey, spy);
    assertSame("registerEngine must return this", scriptManager, returned);
    assertTrue(scriptManager.existsEngine(langKey));
    // And non-JS languages are NOT wrapped by JSScriptEngineFactory.maybeWrap — the same
    // factory should produce the same null-returning engine.
    assertNull(scriptManager.getEngine(session.getDatabaseName(), langKey));
  }

  // ==========================================================================
  // Formatter registry: registerFormatter + getFormatters + getFunction*
  // dispatch
  // ==========================================================================

  @Test
  public void getFormattersContainsSqlAndJavascript() {
    final var formatters = scriptManager.getFormatters();
    assertNotNull(formatters);
    assertTrue(formatters.containsKey("sql"));
    assertTrue(formatters.containsKey("javascript"));
  }

  @Test
  public void registerFormatterLowercasesKey() {
    final var key = "MyLang" + uniqueSuffix();
    final var formatter = new SQLScriptFormatter();
    final var returned = scriptManager.registerFormatter(key, formatter);
    assertSame(scriptManager, returned);
    // The stored key must be lowercased per Locale.ENGLISH semantics.
    assertSame(formatter, scriptManager.getFormatters().get(key.toLowerCase()));
  }

  @Test
  public void getFunctionDefinitionForUnknownLanguageThrowsIllegalArgument() {
    final var f = createFunction("Fn" + uniqueSuffix(), "no-such-lang", "return 1;", null);
    assertThrows(
        IllegalArgumentException.class,
        () -> scriptManager.getFunctionDefinition(session, f));
  }

  @Test
  public void getFunctionInvokeForUnknownLanguageThrowsIllegalArgument() {
    final var f = createFunction("Fn" + uniqueSuffix(), "no-such-lang", "return 1;", null);
    assertThrows(
        IllegalArgumentException.class,
        () -> scriptManager.getFunctionInvoke(session, f, new Object[0]));
  }

  @Test
  public void getFunctionDefinitionForJavascriptProducesFunctionSource() {
    final var fname = "Fn" + uniqueSuffix();
    final var f = createFunction(fname, "javascript", "return 1;", List.of("a", "b"));
    final var def = scriptManager.getFunctionDefinition(session, f);
    assertNotNull(def);
    // JSScriptFormatter emits "function NAME(a,b) { ... }" — verify exact wrapping shape.
    assertTrue("def should start with function header: " + def,
        def.startsWith("function " + fname + "(a,b) {"));
    assertTrue(def.contains("return 1;"));
  }

  @Test
  public void getFunctionInvokeForJavascriptProducesCallExpression() {
    final var fname = "Fn" + uniqueSuffix();
    final var f = createFunction(fname, "javascript", "return 1;", List.of("a"));
    final var invoke = scriptManager.getFunctionInvoke(session, f, new Object[] {42, "x"});
    assertEquals(fname + "(42,x);", invoke);
  }

  @Test
  public void getFunctionInvokeForJavascriptWithNullArgsProducesBareCall() {
    final var fname = "Fn" + uniqueSuffix();
    final var f = createFunction(fname, "javascript", "return 1;", null);
    assertEquals(fname + "();", scriptManager.getFunctionInvoke(session, f, null));
  }

  // ==========================================================================
  // ResultHandler registry: registerResultHandler + handleResult dispatch
  // ==========================================================================

  @Test
  public void handleResultReturnsRawResultWhenNoHandlerRegistered() {
    final var result = scriptManager.handleResult(
        "lang-no-handler-" + uniqueSuffix(), "raw", null, null, session);
    assertEquals("raw", result);
  }

  @Test
  public void handleResultDispatchesToRegisteredHandler() {
    // Lowercase lang: registerResultHandler lowercases on insert (ScriptManager:536), but
    // handleResult does NOT lowercase on lookup (ScriptManager:546). Callers must pre-normalize
    // — mirror that contract here.
    final var lang = ("handler-lang-" + uniqueSuffix()).toLowerCase();
    final var flag = new AtomicInteger();
    scriptManager.registerResultHandler(
        lang,
        (result, engine, binding, database) -> {
          flag.incrementAndGet();
          return "handled:" + result;
        });
    final var out = scriptManager.handleResult(lang, "raw", null, null, session);
    assertEquals(1, flag.get());
    assertEquals("handled:raw", out);
  }

  // ==========================================================================
  // Injection registry: registerInjection dedupe, unregister, getInjections,
  // bind/unbind iterate injections.
  // ==========================================================================

  @Test
  public void registerInjectionDedupesRepeatedRegistration() {
    final var injection = new CountingInjection();
    final var before = scriptManager.getInjections().size();
    scriptManager.registerInjection(injection);
    scriptManager.registerInjection(injection); // idempotent
    try {
      assertEquals(before + 1, scriptManager.getInjections().size());
    } finally {
      scriptManager.unregisterInjection(injection);
    }
    assertEquals(before, scriptManager.getInjections().size());
  }

  @Test
  public void bindAndUnbindIterateInjections() {
    final var injection = new CountingInjection();
    scriptManager.registerInjection(injection);
    try {
      final Bindings bindings = new SimpleBindings();
      scriptManager.bindContextVariables(null, bindings, session, null, null);
      assertEquals("bind should be called once", 1, injection.binds);
      scriptManager.unbind(null, bindings, null, null);
      assertEquals("unbind should be called once", 1, injection.unbinds);
    } finally {
      scriptManager.unregisterInjection(injection);
    }
  }

  // ==========================================================================
  // Library generation: null session, no-functions, null-language function,
  // matching-language function.
  // ==========================================================================

  @Test
  public void getLibraryReturnsNullWhenSessionIsNull() {
    assertNull(scriptManager.getLibrary(null, "javascript"));
  }

  @Test
  public void getLibraryReturnsNullWhenNoFunctionsMatchLanguage() {
    // No functions created — iterator body never runs, returns null because length == 0.
    assertNull(scriptManager.getLibrary(session, "javascript"));
  }

  @Test
  public void getLibraryThrowsConfigurationExceptionWhenFunctionHasNullLanguage() {
    // Reach for the defensive ConfigurationException branch at ScriptManager:195-197 by
    // constructing a Function whose language field we force to null AFTER creation, then
    // direct-mutate the map entry so the library iteration picks up a null-language function
    // without touching the persisted schema (which validates). This pin locks in the
    // defensive branch's observable shape. WHEN-FIXED: Track 22 — if this defensive guard
    // is hardened (e.g., check null at function-create time), the test's surface will shift
    // and should be re-pinned rather than deleted.
    final var fname = "FnNullLang" + uniqueSuffix();
    // Create a valid function first (language=javascript), then replace the in-memory entry
    // in the function library's cache with a copy whose language is null. This bypasses the
    // schema-level validation that would reject a raw null-language insert.
    createFunction(fname, "javascript", "return 1;", null);
    try {
      final var lib = session.getMetadata().getFunctionLibrary();
      final var f = lib.getFunction(session, fname);
      assertNotNull(f);
      f.setLanguage(null); // mutate in-memory only — the production getLibrary reads this.
      final var ex = assertThrows(
          ConfigurationException.class,
          () -> scriptManager.getLibrary(session, "javascript"));
      // FunctionLibrary keys are uppercased (ConcurrentHashMap<String, Function> at
      // FunctionLibraryImpl:51; put() normalizes via toUpperCase(Locale.ENGLISH) at line 146
      // / 222 / 90). getFunctionNames() returns that upper-case snapshot, and ScriptManager's
      // loop uses fName verbatim in the error message.
      assertTrue("should name the null-language function: " + ex.getMessage(),
          ex.getMessage().toUpperCase().contains(fname.toUpperCase()));
      assertTrue(ex.getMessage().contains("no language"));
    } finally {
      session.begin();
      session.getMetadata().getFunctionLibrary().dropFunction(session, fname);
      session.commit();
    }
  }

  @Test
  public void getLibraryReturnsConcatenatedDefinitionsForMatchingLanguage() {
    final var fname = "FnJs" + uniqueSuffix();
    createFunction(fname, "javascript", "return 42;", null);
    try {
      final var code = scriptManager.getLibrary(session, "javascript");
      assertNotNull("expected non-null library code for matching language", code);
      assertTrue("library should contain function name: " + code, code.contains(fname));
      assertTrue("library should contain function body", code.contains("return 42;"));
    } finally {
      session.begin();
      session.getMetadata().getFunctionLibrary().dropFunction(session, fname);
      session.commit();
    }
  }

  @Test
  public void getLibraryReturnsNullWhenNoFunctionsMatchLanguageButOthersExist() {
    final var fname = "FnSql" + uniqueSuffix();
    createFunction(fname, "sql", "SELECT 1", null);
    try {
      // SQL formatter returns null from getFunctionDefinition, so the language filter matches
      // but the definition is dropped — code buffer stays empty → method returns null.
      assertNull(scriptManager.getLibrary(session, "sql"));
    } finally {
      session.begin();
      session.getMetadata().getFunctionLibrary().dropFunction(session, fname);
      session.commit();
    }
  }

  // ==========================================================================
  // Binding helpers: bindContextVariables, bind (deprecated), unbind.
  // ==========================================================================

  @Test
  public void bindContextVariablesBindsDbAndParamsKeys() {
    final Bindings bindings = new SimpleBindings();
    final Map<Object, Object> args = new LinkedHashMap<>();
    args.put("alpha", 1);
    args.put("beta", "two");
    final var ctx = new BasicCommandContext();
    ctx.setVariable("ctxKey", "ctxVal");

    scriptManager.bindContextVariables(null, bindings, session, ctx, args);

    assertTrue("db must be bound as ScriptDatabaseWrapper",
        bindings.get("db") instanceof ScriptDatabaseWrapper);
    assertEquals("ctxVal", bindings.get("ctxKey"));
    assertSame(ctx, bindings.get("ctx"));
    assertEquals(1, bindings.get("alpha"));
    assertEquals("two", bindings.get("beta"));
    final var params = (Object[]) bindings.get("params");
    assertNotNull(params);
    assertEquals(2, params.length);
    // Insertion-order-preserving LinkedHashMap → params = [1, "two"].
    assertEquals(1, params[0]);
    assertEquals("two", params[1]);
  }

  @Test
  public void bindContextVariablesWithNullArgsAndNullContextBindsEmptyParams() {
    final Bindings bindings = new SimpleBindings();
    scriptManager.bindContextVariables(null, bindings, session, null, null);
    final var params = (Object[]) bindings.get("params");
    assertNotNull(params);
    assertEquals("null args → EMPTY_PARAMS", 0, params.length);
    assertTrue(bindings.get("db") instanceof ScriptDatabaseWrapper);
    // ctx null → "ctx" key is not populated by bindContext.
    assertFalse(bindings.containsKey("ctx"));
  }

  @Test
  public void bindContextVariablesWithNullDbSkipsDbKey() {
    final Bindings bindings = new SimpleBindings();
    scriptManager.bindContextVariables(null, bindings, (DatabaseSessionEmbedded) null, null, null);
    // db null → bindDatabase is a no-op.
    assertFalse(bindings.containsKey("db"));
    final var params = (Object[]) bindings.get("params");
    assertNotNull(params);
    assertEquals(0, params.length);
  }

  @Test
  public void bindDeprecatedAlsoBindsYoutrackdbAndUtil() {
    final Bindings bindings = new SimpleBindings();
    scriptManager.bind(null, bindings, session, null, null);
    assertTrue(bindings.get("youtrackdb") instanceof ScriptYouTrackDbWrapper);
    assertNotNull("util must be bound by bindLegacyDatabaseAndUtil", bindings.get("util"));
    assertTrue(bindings.get("db") instanceof ScriptDatabaseWrapper);
  }

  @Test
  public void bindDeprecatedWithNullDbBindsUtilOnly() {
    final Bindings bindings = new SimpleBindings();
    scriptManager.bind(null, bindings, null, null, null);
    // db null → bindLegacyDatabaseAndUtil skips "youtrackdb" but still binds "util".
    assertNull(bindings.get("youtrackdb"));
    // TB9: pin the concrete type rather than mere non-null — a regression that bound an
    // arbitrary non-null object under "util" would pass assertNotNull silently.
    assertTrue(
        "util binding must be a FunctionUtilWrapper regardless of db nullness",
        bindings.get("util") instanceof FunctionUtilWrapper);
  }

  @Test
  public void unbindClearsAllBoundKeysToNull() {
    final Bindings bindings = new SimpleBindings();
    final Map<Object, Object> args = new HashMap<>();
    args.put("alpha", 1);
    final var ctx = new BasicCommandContext();
    ctx.setVariable("ctxKey", "ctxVal");
    scriptManager.bindContextVariables(null, bindings, session, ctx, args);

    scriptManager.unbind(null, bindings, ctx, args);

    // unbind writes null to every key it wrote earlier — does NOT remove them.
    assertNull(bindings.get("db"));
    assertNull(bindings.get("youtrackdb"));
    assertNull(bindings.get("util"));
    assertNull(bindings.get("ctx"));
    assertNull(bindings.get("ctxKey"));
    assertNull(bindings.get("alpha"));
    assertNull(bindings.get("params"));
  }

  @Test
  public void unbindWithNullContextAndNullArgsStillNullsFixedKeys() {
    final Bindings bindings = new SimpleBindings();
    scriptManager.bindContextVariables(null, bindings, session, null, null);
    scriptManager.unbind(null, bindings, null, null);
    assertNull(bindings.get("db"));
    assertNull(bindings.get("ctx"));
    assertNull(bindings.get("params"));
  }

  // ==========================================================================
  // Allowed-packages security: add / remove / get round-trip.
  // ==========================================================================

  @Test
  public void addAllowedPackagesThenRemovePackagesRoundTrip() {
    final var pkg = "test.pkg." + uniqueSuffix();
    final Set<String> toAdd = new HashSet<>(List.of(pkg));
    try {
      scriptManager.addAllowedPackages(toAdd);
      assertTrue(scriptManager.getAllowedPackages().contains(pkg));
    } finally {
      scriptManager.removeAllowedPackages(toAdd);
    }
    assertFalse(scriptManager.getAllowedPackages().contains(pkg));
  }

  // ==========================================================================
  // throwErrorMessage: Rhino fallback + positive line number + no-line path.
  // ==========================================================================

  @Test
  public void throwErrorMessageWithPositiveLineNumberWrapsInCommandScriptException() {
    final var lib = "line1\nline2\nline3\nline4\nline5\n";
    final var se = new ScriptException("boom", "inline", 3);
    final var ex =
        assertThrows(
            CommandScriptException.class,
            () -> scriptManager.throwErrorMessage(session.getDatabaseName(), se, lib));
    // ScriptException.getMessage() includes the fileName+line suffix ("boom in inline at line
    // number 3."); the preamble echoes that full message. Assert the "ScriptManager: error"
    // prefix only, then pin the >>> framing on the error line.
    final var message = ex.getMessage();
    assertTrue("missing preamble: " + message, message.contains("ScriptManager: error boom"));
    assertTrue("should mark error line with arrows: " + message,
        message.contains(">>> line3"));
  }

  @Test
  public void throwErrorMessageWithNoLineInfoAppendsLibraryVerbatim() {
    final var lib = "SCRIPT BODY";
    // getLineNumber() returns -1 (default for String-only ctor); message has no
    // "<Unknown Source>#" pattern either, so the no-line-number branch fires.
    final var se = new ScriptException("generic failure");
    final var ex =
        assertThrows(
            CommandScriptException.class,
            () -> scriptManager.throwErrorMessage(session.getDatabaseName(), se, lib));
    final var message = ex.getMessage();
    assertTrue(message.contains("Error on evaluation of the script library"));
    assertTrue(message.contains("Script library was"));
    assertTrue(message.contains(lib));
  }

  @Test
  public void throwErrorMessageExtractsLineFromRhinoUnknownSourcePattern() {
    final var lib = "A\nB\nC\nD\nE\n";
    // ScriptException.toString() prefixes "javax.script.ScriptException: " to the message;
    // the Rhino-fallback regex matches "<Unknown Source>#N)" anywhere in the resulting text.
    // getLineNumber() is -1 so the fallback branch is reached.
    final var se = new ScriptException("boom at <Unknown Source>#3)");
    final var ex =
        assertThrows(
            CommandScriptException.class,
            () -> scriptManager.throwErrorMessage(session.getDatabaseName(), se, lib));
    // Because the extracted line=3 is positive, the formatter path runs → ">>> C" framed.
    assertTrue("extracted line should be framed: " + ex.getMessage(),
        ex.getMessage().contains(">>> C"));
  }

  @Test
  public void throwErrorMessageDetectsFunctionHeaderOnFunctionLine() {
    // The function-header scanner extracts the first whitespace-delimited word after "function"
    // and embeds it in the "Function NAME:" preamble. StringParser.getWords splits on
    // " \r\n\t" only (parens stay attached), so for "function foo(a) {" the extracted word is
    // "foo(a)". Covers the words.length > 0 && !"(".equals(words[0]) branch.
    final var lib =
        "var x = 1;\n"
            + "function foo(a) {\n"
            + "  return a;\n"
            + "}\n"
            + "foo(broken);\n";
    final var se = new ScriptException("boom", "inline", 5);
    final var ex =
        assertThrows(
            CommandScriptException.class,
            () -> scriptManager.throwErrorMessage(session.getDatabaseName(), se, lib));
    assertTrue("should report function name foo: " + ex.getMessage(),
        ex.getMessage().contains("Function foo(a):"));
  }

  // ==========================================================================
  // throwErrorMessage — TC1/TC6 falsifiable regression pins for boundary and
  // malformed input behaviors. These pin the CURRENT observed shape; if Track 22
  // hardens the error handler to throw CommandScriptException uniformly instead
  // of surfacing NumberFormatException/StringIndexOutOfBoundsException, these
  // tests will flip — rewrite them to pin the new wrap shape.
  // ==========================================================================

  /**
   * TC1 pin (malformed Rhino pattern, non-numeric line): when the Rhino-fallback regex
   * extracts a non-numeric segment between {@code "<Unknown Source>#"} and {@code ")"},
   * {@code Integer.parseInt(...)} throws {@link NumberFormatException}. This pins the
   * current observable — a Track 22 hardening that wraps the parse failure into a
   * {@link CommandScriptException} would flip this test; rewrite at that time.
   *
   * <p>WHEN-FIXED: Track 22 — add guard around Integer.parseInt in
   * {@code ScriptManager.throwErrorMessage} Rhino-fallback branch.
   */
  @Test
  public void throwErrorMessageRhinoFallbackNonNumericLineThrowsNumberFormatException() {
    final var lib = "A\nB\nC\n";
    final var se = new ScriptException("<Unknown Source>#abc)");
    assertThrows(
        NumberFormatException.class,
        () -> scriptManager.throwErrorMessage(session.getDatabaseName(), se, lib));
  }

  /**
   * TC1 pin (malformed Rhino pattern, missing close paren): when the regex finds
   * {@code "<Unknown Source>#"} but no closing {@code ")"}, {@code indexOf(...)} returns
   * {@code -1} and {@code substring(pos+len, -1)} throws
   * {@link StringIndexOutOfBoundsException}. Pin the current shape.
   *
   * <p>WHEN-FIXED: Track 22 — same hardening as
   * {@link #throwErrorMessageRhinoFallbackNonNumericLineThrowsNumberFormatException}.
   */
  @Test
  public void throwErrorMessageRhinoFallbackMissingCloseParenThrowsStringIndexOutOfBounds() {
    final var lib = "A\nB\nC\n";
    final var se = new ScriptException("<Unknown Source>#3");
    assertThrows(
        StringIndexOutOfBoundsException.class,
        () -> scriptManager.throwErrorMessage(session.getDatabaseName(), se, lib));
  }

  /**
   * TC6 pin: when the error line number exceeds the library length, the formatter's
   * pointer-arrow branch is never emitted. Pin that the error message still carries the
   * standard preamble (it does NOT include the {@code ">>>"} arrow marker because no
   * matching line exists).
   */
  @Test
  public void throwErrorMessageLineBeyondLibraryEndsOmitsArrowMarker() {
    final var lib = "A\nB\nC\n";
    final var se = new ScriptException("far beyond", "inline", 99);
    final var ex =
        assertThrows(
            CommandScriptException.class,
            () -> scriptManager.throwErrorMessage(session.getDatabaseName(), se, lib));
    assertFalse(
        "no arrow marker should be emitted when line is beyond library end: " + ex.getMessage(),
        ex.getMessage().contains(">>>"));
  }

  /**
   * TC6 pin (line 0 boundary): the {@code <= 0} guard on the error line number takes the
   * no-line-number branch for line 0 (previously only negative values were tested). Pin the
   * preamble shape without an arrow marker.
   */
  @Test
  public void throwErrorMessageLineNumberZeroTakesNoLineBranch() {
    final var lib = "A\nB\n";
    final var se = new ScriptException("oops", "inline", 0);
    final var ex =
        assertThrows(
            CommandScriptException.class,
            () -> scriptManager.throwErrorMessage(session.getDatabaseName(), se, lib));
    assertTrue(
        "line 0 must take the no-line-info preamble: " + ex.getMessage(),
        ex.getMessage().contains("Error on evaluation of the script library"));
  }

  // ==========================================================================
  // Database-engine pool lifecycle: acquire/release, close(dbName) removing
  // the pool, closeAll, and close for a non-existent db.
  // ==========================================================================

  @Test
  public void acquireDatabaseEngineForSqlReturnsSqlScriptEngine() {
    final var engine = scriptManager.acquireDatabaseEngine(session, "sql");
    try {
      assertNotNull(engine);
      assertTrue(engine instanceof SQLScriptEngine);
    } finally {
      scriptManager.releaseDatabaseEngine("sql", session.getDatabaseName(), engine);
    }
  }

  @Test
  public void releaseDatabaseEngineForUnknownDatabaseIsNoOp() {
    // Must not throw even though no pool has been created for this name.
    scriptManager.releaseDatabaseEngine(
        "sql",
        "never-opened-db-" + uniqueSuffix(),
        scriptManager.getEngine(session.getDatabaseName(), "sql"));
  }

  @Test
  public void closeForUnknownDatabaseIsNoOp() {
    // remove() returns null → dbPool.close() branch is skipped. Must not throw.
    scriptManager.close("never-opened-db-" + uniqueSuffix());
  }

  @Test
  public void closeForExistingDatabaseRemovesPool() {
    // Seed the pool for the current DB name.
    final var sqlEngine = scriptManager.acquireDatabaseEngine(session, "sql");
    scriptManager.releaseDatabaseEngine("sql", session.getDatabaseName(), sqlEngine);
    // close() should remove and close the pool. Subsequent acquire recreates fresh.
    scriptManager.close(session.getDatabaseName());
    // Verify a fresh acquire still works (pool recreated on demand).
    final var after = scriptManager.acquireDatabaseEngine(session, "sql");
    try {
      assertNotNull(after);
    } finally {
      scriptManager.releaseDatabaseEngine("sql", session.getDatabaseName(), after);
    }
  }

  @Test
  public void closeAllDrainsAllPools() {
    // Populate a pool so closeAll has work to do.
    final var e = scriptManager.acquireDatabaseEngine(session, "sql");
    scriptManager.releaseDatabaseEngine("sql", session.getDatabaseName(), e);
    // closeAll drains dbManagers and commandManager executors; calling it twice in a row
    // is safe (second call is a no-op).
    scriptManager.closeAll();
    scriptManager.closeAll();
  }

  /**
   * TX1 — concurrent first-access race pin for {@code acquireDatabaseEngine}. Two threads
   * simultaneously call {@link ScriptManager#acquireDatabaseEngine} on the same DB name when
   * no pool exists; the production code reads {@code dbManagers.get(name)} → null, constructs
   * a fresh {@link DatabaseScriptManager}, and races on {@code putIfAbsent}. The loser must
   * {@code close()} its orphan and fall through to the winner's entry. Pin the observable
   * invariant: after both threads complete, {@code dbManagers} contains exactly ONE entry for
   * the dbName — proving {@code putIfAbsent} admitted only the winner. The closer precise
   * pin (counting close() calls on the loser) would require injecting a subclass into the
   * factory, which is not exposed today — acceptable per T7's single-thread scope.
   *
   * <p>Covers: ScriptManager.acquireDatabaseEngine lines 248-263, {@code putIfAbsent} race
   * + {@code prev != null} → {@code dbManager.close()} → {@code dbManager = prev} branch.
   */
  @Test
  public void acquireDatabaseEngineConcurrentFirstAccessAdmitsSingleDbManager() throws Exception {
    final var dbName = session.getDatabaseName();
    // Ensure no pre-seeded entry so both threads race the null-check → putIfAbsent path.
    scriptManager.close(dbName);
    assertNull(
        "pre-race: dbManagers must not contain the target dbName",
        scriptManager.dbManagers.get(dbName));

    final var ready = new CountDownLatch(2);
    final var go = new CountDownLatch(1);
    final var pool = Executors.newFixedThreadPool(2);
    // Each racer needs its OWN DatabaseSessionEmbedded — the primary `session` is bound to
    // the test thread and cannot be activated on a worker thread simultaneously. Open two
    // fresh sessions against the same database; acquireDatabaseEngine reads
    // session.getDatabaseName() to key dbManagers, so both end up racing on the same key.
    final var sessionA = youTrackDB.open(dbName, "admin", adminPassword);
    final var sessionB = youTrackDB.open(dbName, "admin", adminPassword);
    final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.function.Consumer<DatabaseSessionEmbedded> racer =
        racerSession -> {
          try {
            racerSession.activateOnCurrentThread();
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            final var engine = scriptManager.acquireDatabaseEngine(racerSession, "sql");
            try {
              assertNotNull("racer must observe a non-null engine", engine);
            } finally {
              scriptManager.releaseDatabaseEngine("sql", dbName, engine);
            }
          } catch (Throwable t) {
            failure.compareAndSet(null, t);
          }
        };

    try {
      final var f1 = pool.submit(() -> racer.accept(sessionA));
      final var f2 = pool.submit(() -> racer.accept(sessionB));
      assertTrue("both racers must arrive at the barrier", ready.await(5, TimeUnit.SECONDS));
      go.countDown();
      f1.get(10, TimeUnit.SECONDS);
      f2.get(10, TimeUnit.SECONDS);
      if (failure.get() != null) {
        throw new AssertionError("racer threw", failure.get());
      }
    } finally {
      pool.shutdownNow();
      assertTrue("race-pool must terminate", pool.awaitTermination(5, TimeUnit.SECONDS));
      // Reactivate the primary session before touching it from the test thread.
      session.activateOnCurrentThread();
      sessionA.close();
      sessionB.close();
      session.activateOnCurrentThread();
    }

    // Post-race invariant: exactly one DatabaseScriptManager survived putIfAbsent for dbName.
    assertNotNull(
        "winner's DatabaseScriptManager must be present in dbManagers",
        scriptManager.dbManagers.get(dbName));
  }

  @Test
  public void getCommandManagerReturnsNonNull() {
    // Sanity pin — constructor must wire a non-null CommandManager.
    assertNotNull(scriptManager.getCommandManager());
  }

  @Test
  public void registerFormatterAcceptsJsScriptFormatterOverride() {
    // Registering a new formatter with an existing language lower-cased key overrides.
    final var old = scriptManager.getFormatters().get("javascript");
    try {
      final var override = new JSScriptFormatter();
      scriptManager.registerFormatter("JavaScript", override);
      assertSame(override, scriptManager.getFormatters().get("javascript"));
    } finally {
      // Restore to avoid polluting sibling tests.
      scriptManager.registerFormatter("javascript", old);
    }
  }

  // ==========================================================================
  // Helpers
  // ==========================================================================

  /**
   * Unique per-method suffix — UUID-style collision avoidance inside parallel surefire. Always
   * lowercase so callers can safely concatenate into language keys passed to APIs that lowercase
   * the lookup (existsEngine / getEngine / registerResultHandler contract).
   */
  private String uniqueSuffix() {
    return (name.getMethodName().replaceAll("[^a-zA-Z0-9]", "_") + "_" + System.nanoTime())
        .toLowerCase();
  }

  /**
   * Create + persist a Function using the direct {@code new Function(session)} pattern (see
   * {@code FunctionSqlTest} for the same idiom). Calling {@code FunctionLibraryImpl.createFunction}
   * first and mutating afterward doesn't work because the internal transaction commits with the
   * default language {@code "SQL"}; a post-commit setter on the in-memory Function is discarded
   * when the commit hook reloads the function from the DB entity.
   */
  private Function createFunction(
      final String name,
      final String language,
      final String code,
      final List<String> parameters) {
    session.begin();
    final var f = new Function(session);
    f.setName(name);
    f.setCode(code);
    f.setLanguage(language);
    if (parameters != null) {
      f.setParameters(parameters);
    }
    f.save(session);
    session.commit();
    return session.getMetadata().getFunctionLibrary().getFunction(session, name);
  }

  /** Minimal ScriptEngineFactory that returns null from getScriptEngine() so we can observe
   * that a non-JS registered engine is not wrapped by JSScriptEngineFactory.maybeWrap. */
  private static class SpyEngineFactory implements javax.script.ScriptEngineFactory {
    @Override
    public String getEngineName() {
      return "spy";
    }

    @Override
    public String getEngineVersion() {
      return "1";
    }

    @Override
    public List<String> getExtensions() {
      return Arrays.asList("spy");
    }

    @Override
    public List<String> getMimeTypes() {
      return Arrays.asList("text/x-spy");
    }

    @Override
    public List<String> getNames() {
      return Arrays.asList("spy");
    }

    @Override
    public String getLanguageName() {
      return "spylang";
    }

    @Override
    public String getLanguageVersion() {
      return "1";
    }

    @Override
    public Object getParameter(String key) {
      return null;
    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
      return obj + "." + m;
    }

    @Override
    public String getOutputStatement(String toDisplay) {
      return toDisplay;
    }

    @Override
    public String getProgram(String... statements) {
      return "";
    }

    @Override
    public ScriptEngine getScriptEngine() {
      return null;
    }
  }

  /** Counts bind/unbind calls so the integration test can assert iteration happened. */
  private static class CountingInjection implements ScriptInjection {
    int binds;
    int unbinds;

    @Override
    public void bind(ScriptEngine engine, Bindings binding, DatabaseSessionEmbedded database) {
      binds++;
    }

    @Override
    public void unbind(ScriptEngine engine, Bindings binding) {
      unbinds++;
    }
  }
}
