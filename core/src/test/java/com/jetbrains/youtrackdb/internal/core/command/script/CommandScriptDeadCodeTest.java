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

import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import com.jetbrains.youtrackdb.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrackdb.internal.core.command.CommandExecutorNotFoundException;
import com.jetbrains.youtrackdb.internal.core.command.CommandManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandRequest;
import com.jetbrains.youtrackdb.internal.core.command.CommandRequestTextAbstract;
import com.jetbrains.youtrackdb.internal.core.command.ScriptExecutorRegister;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLScriptEngine;
import com.jetbrains.youtrackdb.internal.core.sql.SQLScriptEngineFactory;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import org.junit.Test;

/**
 * Dead-code pin tests for the {@code core/command/script} and {@code core/command} packages
 * plus the {@code core/sql/SQLScriptEngine*} entry points.
 *
 * <p>Track 9 Phase A reviews (T1/R1, T2, T3) identified several dead or semi-dead regions
 * reachable only through paths with no production callers. Verified at Phase A by:
 *
 * <pre>
 *   grep -rn "CommandExecutorScript" core/src/main   --&gt; only the class itself
 *                                                        + a JavaDoc @see on CommandScript
 *   grep -rn "new CommandScript("   core/src/main   --&gt; only SQLScriptEngine.eval(Reader, ...)
 *                                                        which is itself dead (T2)
 *   grep -rn "CommandScript.execute(" core/src/main --&gt; only the same SQLScriptEngine.eval(Reader)
 *   grep -rn "commandReqExecMap\\|registerExecutor(Class\\|configCallbacks\\|
 *             getExecutor(CommandRequestInternal" core/src/main
 *                                                  --&gt; only within CommandManager itself
 *   grep -rn "implements ScriptExecutorRegister\\|implements ScriptInterceptor\\|
 *             implements ScriptInjection" core/src/main --&gt; 0 hits
 *   ls core/src/main/resources/META-INF/services/javax.script.ScriptEngineFactory
 *                                                  --&gt; file does not exist
 * </pre>
 *
 * <p>This suite exists to:
 *
 * <ul>
 *   <li>Exercise each dead class's reachable surface so JaCoCo reports coverage and the
 *       aggregate for {@code core/command/script} and {@code core/command} is not artificially
 *       depressed by never-loaded classes.
 *   <li>Flag the exact deletion targets Track 22 should pursue via {@code // WHEN-FIXED: Track 22}
 *       markers under each class's tests.
 * </ul>
 *
 * <p>If any production caller appears later, these tests still pass — but the package aggregate
 * coverage will naturally rise via the new caller, removing the rationale for the pin. Search
 * for the {@code // WHEN-FIXED: Track 22} markers in this file before deleting any class so an
 * accidentally reachable one is not removed.
 *
 * <p>All tests are standalone (no {@link com.jetbrains.youtrackdb.internal.DbTestBase}); the
 * pinned code either ignores its session field, accepts {@code null}, or executes stubs that
 * do not touch a session.
 */
public class CommandScriptDeadCodeTest {

  // ---------------------------------------------------------------------------
  // CommandScript — public ctor is only reached from the dead
  // SQLScriptEngine.eval(Reader, ...) path; execute() returns List.of() and
  // ignores both arguments. WHEN-FIXED: Track 22 — delete CommandScript and its
  // @see references once CommandExecutorScript is removed.
  // ---------------------------------------------------------------------------

  @Test
  public void commandScriptExecuteReturnsEmptyListRegardlessOfSessionAndArgs() {
    // Pins the stub contract: the class exists but execute() is a no-op.
    // Passing null for the @Nonnull session must not NPE because the stub body never
    // dereferences it — the annotation is documentary, not enforced at runtime.
    var script = new CommandScript("sql", "SELECT 1");

    var result = script.execute((DatabaseSessionEmbedded) null);

    assertEquals(
        "CommandScript.execute must return an immutable empty List.of()",
        List.of(),
        result);

    assertEquals(
        "execute(session, iArgs...) must also return List.of() ignoring iArgs",
        List.of(),
        script.execute(null, "ignored", 42, null));
    // WHEN-FIXED: Track 22 — delete CommandScript.execute along with the whole class.
  }

  @Test
  public void commandScriptNoArgCtorDefaultsLanguageToNullAndCachesByDefault() {
    // The no-arg ctor sets useCache = true but does NOT call setLanguage, so
    // getLanguage() must be null. This shape is what the dead SQLScriptEngine.eval(Reader)
    // path relies on (it passes a language via the one-arg ctor).
    var script = new CommandScript();

    assertNull("no-arg ctor must leave language null", script.getLanguage());
    assertTrue(
        "no-arg ctor sets useCache = true (inherited from CommandRequestAbstract)",
        script.isUseCache());
    assertFalse(
        "execute() is explicitly isIdempotent() == false",
        script.isIdempotent());
    assertEquals(
        "toString() falls back to 'script.<text>' when language is null",
        "script.null", // text is also null via no-arg ctor
        script.toString());
    // WHEN-FIXED: Track 22 — delete CommandScript along with its parent holder methods.
  }

  @Test
  public void commandScriptOneArgCtorDefaultsLanguageToSqlAndPopulatesToString() {
    var script = new CommandScript("UPDATE Foo SET bar = 1");

    assertEquals("one-arg ctor defaults language to 'sql'", "sql", script.getLanguage());
    assertEquals("UPDATE Foo SET bar = 1", script.getText());
    assertEquals(
        "toString() renders 'language.text' when language is set",
        "sql.UPDATE Foo SET bar = 1",
        script.toString());
    assertTrue(
        "one-arg ctor must also set useCache (same chain as no-arg ctor)",
        script.isUseCache());
    assertFalse(
        "execute() is explicitly isIdempotent() == false regardless of ctor",
        script.isIdempotent());
    // WHEN-FIXED: Track 22 — delete CommandScript(String) once the dead callers are gone.
  }

  // Note: CommandScript.fromStream/toStream round-trip is un-testable today because
  // RecordSerializerNetwork has no concrete implementation anywhere in core. A Track 22
  // deletion that drops the overrides is still observable via compile-time removal of
  // the methods — caller-side regressions would surface at build time. No runtime pin
  // here; TC-1 deferred.

  @Test
  public void commandScriptSetLanguageRejectsNullAndEmpty() {
    var script = new CommandScript();

    var nullEx = assertThrows(
        IllegalArgumentException.class, () -> script.setLanguage(null));
    assertTrue(
        "null path must share the canonical prefix with empty path",
        nullEx.getMessage().startsWith("Not a valid script language specified"));

    var emptyEx = assertThrows(
        IllegalArgumentException.class, () -> script.setLanguage(""));
    assertTrue(
        "empty message must identify the invalid input",
        emptyEx.getMessage().startsWith("Not a valid script language specified"));
    // WHEN-FIXED: Track 22 — setLanguage validation is only exercised here and via
    // CommandRequestTextAbstract test paths; removing CommandScript deletes it.
  }

  @Test
  public void commandScriptCompiledScriptGetterRoundTripsNull() {
    // The compiledScript field is only ever set by the dead CommandExecutorScript path.
    // Pin the getter/setter null round-trip so a regression from null-tolerant to
    // NPE-throwing is visible.
    var script = new CommandScript("sql", "x");

    assertNull("compiledScript defaults to null", script.getCompiledScript());
    script.setCompiledScript(null);
    assertNull("setter accepts null", script.getCompiledScript());
    // WHEN-FIXED: Track 22 — delete compiledScript field + accessors.
  }

  // ---------------------------------------------------------------------------
  // CommandExecutorScript — full class is dead. No production caller instantiates
  // it; META-INF/services does not register it; CommandManager's legacy class-based
  // dispatch (the only conceivable loader) is itself dead. WHEN-FIXED: Track 22 —
  // delete CommandExecutorScript together with CommandScript.
  // ---------------------------------------------------------------------------

  @Test
  public void commandExecutorScriptDefaultCtorProducesCommandExecutorAbstract() {
    // The class is public only so reflection or legacy CommandManager dispatch could
    // reach it. Instantiate it via its public ctor (standalone) to cover the single
    // uncovered line `public CommandExecutorScript() {}` in JaCoCo, and to pin the
    // "extends CommandExecutorAbstract" structural contract. The zero-callers claim
    // is documented in the class-level Javadoc; it is not asserted here — that would
    // require a classpath scan well beyond this pin's scope.
    var exec = new CommandExecutorScript();

    assertTrue(
        "CommandExecutorScript extends CommandExecutorAbstract",
        exec instanceof CommandExecutorAbstract);
    // WHEN-FIXED: Track 22 — delete CommandExecutorScript.
  }

  @Test
  public void commandExecutorScriptIsNotRegisteredInAnyMetaInfServicesFile() throws Exception {
    // If a downstream module ever registered CommandExecutorScript as a service the
    // pin would go stale. Scan the known META-INF/services files that could plausibly
    // carry such a registration and assert the dead class is absent from each.
    var loader = Thread.currentThread().getContextClassLoader();
    String deadClassName =
        "com.jetbrains.youtrackdb.internal.core.command.script.CommandExecutorScript";
    for (String serviceName : new String[] {
        "com.jetbrains.youtrackdb.internal.core.engine.Engine",
        "com.jetbrains.youtrackdb.internal.core.command.ScriptExecutorRegister",
        "javax.script.ScriptEngineFactory",
    }) {
      try (var in = loader.getResourceAsStream("META-INF/services/" + serviceName)) {
        if (in == null) {
          continue;
        }
        var contents = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(
            "Service " + serviceName + " must not register the dead CommandExecutorScript",
            contents.contains(deadClassName));
      }
    }
    // WHEN-FIXED: Track 22 — once CommandExecutorScript is deleted this test can
    // either be removed or retargeted at whatever replacement is introduced.
  }

  // ---------------------------------------------------------------------------
  // CommandManager legacy class-based dispatch — commandReqExecMap + configCallbacks
  // + registerExecutor(Class, ...) + unregisterExecutor(Class) + getExecutor(
  // CommandRequestInternal) form a disused cluster. The live path is scriptExecutors
  // + registerScriptExecutor(String, ScriptExecutor) + getScriptExecutor(String)
  // (exercised in Step 2). WHEN-FIXED: Track 22 — delete the class-based cluster.
  // ---------------------------------------------------------------------------

  @Test
  public void commandManagerGetExecutorOnEmptyLegacyMapThrowsNotFound() {
    // The legacy dispatch map is initialised empty in the default ctor (only
    // scriptExecutors is populated with sql + script). An unregistered CommandRequest
    // subclass must therefore hit the "Cannot find a command executor" branch.
    var manager = new CommandManager();

    var ex = assertThrows(
        CommandExecutorNotFoundException.class,
        () -> manager.getExecutor(new DummyCommandRequest()));
    assertTrue(
        "exception must start with the legacy dispatch's not-found prefix",
        ex.getMessage().startsWith("Cannot find a command executor for the command request"));
    // WHEN-FIXED: Track 22 — delete CommandManager.getExecutor(CommandRequestInternal)
    // and the backing commandReqExecMap.
  }

  @Test
  public void commandManagerLegacyRegisterUnregisterRoundTripsWithoutCallback() {
    // Pin the round-trip of the dead legacy register/unregister pair so the
    // uncovered branches (put + remove of both maps) are exercised at least once.
    var manager = new CommandManager();

    manager.registerExecutor(DummyCommandRequest.class, DummyCommandExecutor.class);
    // After registration, getExecutor must succeed and produce a fresh instance.
    var exec = manager.getExecutor(new DummyCommandRequest());
    assertTrue(
        "getExecutor must instantiate the registered class",
        exec instanceof DummyCommandExecutor);

    manager.unregisterExecutor(DummyCommandRequest.class);
    // After unregistration the map is empty again and getExecutor falls back to throw.
    assertThrows(
        CommandExecutorNotFoundException.class,
        () -> manager.getExecutor(new DummyCommandRequest()));
    // WHEN-FIXED: Track 22 — delete registerExecutor(Class, Class) + unregisterExecutor(Class).
  }

  @Test
  public void commandManagerGetExecutorWrapsInstantiationFailureThroughContextChain() {
    // Pin the catch (Exception) path in CommandManager.getExecutor (CommandManager.java:107-115).
    // That block dereferences iCommand.getContext().getDatabaseSession().getDatabaseName() when
    // wrapping the instantiation failure. Our DummyCommandRequest defaults its context to a
    // fresh BasicCommandContext with no database session — so getDatabaseSession() today
    // throws DatabaseException("No database session found in SQL context"). This pins the
    // current observable shape so a Track 22 deletion of the catch block (or a refactor that
    // null-guards the context chain) is visible.
    var manager = new CommandManager();
    manager.registerExecutor(DummyCommandRequest.class, UninstantiableExecutor.class);

    // UninstantiableExecutor is abstract, so Class.newInstance() throws
    // InstantiationException → caught → context chain dereference fails inside the wrap.
    var ex = assertThrows(
        com.jetbrains.youtrackdb.internal.core.exception.DatabaseException.class,
        () -> manager.getExecutor(new DummyCommandRequest()));
    assertTrue(
        "message must identify the missing session in SQL context",
        ex.getMessage().contains("No database session found in SQL context"));
    // WHEN-FIXED: Track 22 — delete CommandManager.getExecutor together with its catch block.
  }

  @Test
  public void commandManagerLegacyRegisterWithCallbackInvokesCallbackOnDispatch() {
    // Covers the three-arg registerExecutor + configCallbacks.put + callback.call
    // path — all currently zero-production-caller.
    var manager = new CommandManager();
    var callbackCount = new int[] {0};
    CallableFunction<Void, CommandRequest> callback = req -> {
      callbackCount[0]++;
      return null;
    };

    manager.registerExecutor(DummyCommandRequest.class, DummyCommandExecutor.class, callback);

    var exec = manager.getExecutor(new DummyCommandRequest());
    assertNotNull(exec);
    assertEquals(
        "callback must be invoked exactly once per getExecutor dispatch",
        1,
        callbackCount[0]);
    // WHEN-FIXED: Track 22 — delete configCallbacks + registerExecutor(Class, Class, Callable).
  }

  // ---------------------------------------------------------------------------
  // ScriptExecutorRegister SPI — zero production impls, zero META-INF/services
  // entries. Iterated once at ScriptManager construction and otherwise unused.
  // WHEN-FIXED: Track 22 — delete the SPI interface.
  // ---------------------------------------------------------------------------

  @Test
  public void scriptExecutorRegisterIsAnSpiWithNoCoreImplementations() throws Exception {
    // Pin that the interface is an SPI (single method; not an annotation; public).
    var iface = ScriptExecutorRegister.class;
    assertTrue("ScriptExecutorRegister must be an interface", iface.isInterface());
    assertEquals(
        "ScriptExecutorRegister exposes exactly one SPI method",
        1,
        iface.getDeclaredMethods().length);
    assertTrue(
        "SPI interface must be public so downstream modules could implement it",
        Modifier.isPublic(iface.getModifiers()));

    // The core module must not ship a registration; ScriptManager.lookupProvider will
    // find zero entries at runtime. If a downstream module ships an impl the stream
    // may still exist — in that case scope the check to core's own package.
    var loader = Thread.currentThread().getContextClassLoader();
    var serviceFile = "META-INF/services/" + iface.getName();
    try (var in = loader.getResourceAsStream(serviceFile)) {
      if (in != null) {
        var contents = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(
            "core must not register ScriptExecutorRegister impls under core/command/script",
            contents.contains("com.jetbrains.youtrackdb.internal.core.command.script."));
      }
    }
    // WHEN-FIXED: Track 22 — delete ScriptExecutorRegister.
  }

  // ---------------------------------------------------------------------------
  // SQLScriptEngineFactory — instantiated directly in ScriptManager.<init>
  // (registerEngine("sql", new SQLScriptEngineFactory())) but not JSR-223-
  // discoverable because the core module ships no
  // META-INF/services/javax.script.ScriptEngineFactory file. WHEN-FIXED: Track 22
  // — either wire the service file or delete the JSR-223 metadata methods.
  // ---------------------------------------------------------------------------

  @Test
  public void sqlScriptEngineFactoryIsNotDiscoverableViaJsr223ServiceLoader() {
    var manager = new ScriptEngineManager();

    // JSR-223 discovery should not find the factory by "sql" alias.
    boolean foundBySql = false;
    boolean foundByClass = false;
    for (ScriptEngineFactory factory : manager.getEngineFactories()) {
      if (factory instanceof SQLScriptEngineFactory) {
        foundByClass = true;
      }
      if (factory.getEngineName().equalsIgnoreCase(SQLScriptEngine.NAME)) {
        foundBySql = true;
      }
    }
    assertFalse(
        "SQLScriptEngineFactory must not appear in JSR-223 ServiceLoader results",
        foundByClass);
    assertFalse(
        "No JSR-223 factory may claim the engine name 'sql'",
        foundBySql);
    // WHEN-FIXED: Track 22 — decide whether to register or delete JSR-223 metadata.
  }

  @Test
  public void sqlScriptEngineEvalReaderThrowsWhenNoDatabaseInBindings() throws Exception {
    // SQLScriptEngine.eval(Reader, Bindings) delegates to new CommandScript(text).execute(...)
    // via the dead path at SQLScriptEngine.java:151 — but only AFTER the early null-db
    // check at lines 138-140. Without a DatabaseSessionEmbedded in bindings, control
    // never reaches CommandScript; we pin only the guard-throw shape here. The dead
    // CommandScript.execute delegation is pinned separately by
    // commandScriptExecuteReturnsEmptyListRegardlessOfSessionAndArgs.
    var engine = new SQLScriptEngine(new SQLScriptEngineFactory());

    Reader reader = new StringReader("select 1");
    var ex = assertThrows(
        CommandExecutionException.class,
        () -> engine.eval(reader, engine.createBindings()));
    assertTrue(
        "missing-db error message pins the current guard-throw shape",
        ex.getMessage().contains("No database available"));
    // WHEN-FIXED: Track 22 — either delete eval(Reader, ...) or route it to live code.
  }

  @Test
  public void sqlScriptEngineFactoryInstantiationAndMetadataContract() {
    // The factory's public ctor is live (called in ScriptManager<init>). Pin the
    // engine-name contract so a rename would trip the pin.
    var factory = new SQLScriptEngineFactory();
    var engine = factory.getScriptEngine();

    assertTrue(
        "getScriptEngine must return an SQLScriptEngine",
        engine instanceof SQLScriptEngine);
    assertSame(
        "SQLScriptEngine.getFactory() must return the same factory instance",
        factory,
        ((SQLScriptEngine) engine).getFactory());
    // WHEN-FIXED: Track 22 — if the JSR-223 metadata surface is deleted this test
    // must drop the factory.getScriptEngine() assertion.
  }

  // ---------------------------------------------------------------------------
  // ScriptDocumentDatabaseWrapper + ScriptYouTrackDbWrapper — reachable only via
  // ScriptManager.bindLegacyDatabaseAndUtil which is called from the deprecated
  // ScriptManager.bind(...) overload. The live bind(...) caller is
  // Jsr223ScriptExecutor.executeFunction (stored JS functions), so these wrappers
  // are NOT fully dead — only a subset of their methods is unreachable. Step 4
  // covers the live subset via a stored-function test; this pin covers the
  // deprecated no-arg wiring.
  // ---------------------------------------------------------------------------

  @Test
  public void scriptYouTrackDbWrapperNoArgCtorThrowsOnGetDatabase() {
    // Pin the "no database" fallback path — used only via the deprecated no-arg ctor.
    var wrapper = new ScriptYouTrackDbWrapper();

    var ex = assertThrows(ConfigurationException.class, wrapper::getDatabase);
    assertTrue(
        "message must identify the missing database context",
        ex.getMessage().contains("No database instance found in context"));
    // WHEN-FIXED: Track 22 — delete the no-arg ctor when deprecating the wrapper.
  }

  // ---------------------------------------------------------------------------
  // Test fixture classes — standalone stubs that satisfy the legacy dispatch API
  // without needing a full session or request implementation.
  // ---------------------------------------------------------------------------

  /**
   * Minimal CommandRequestInternal whose only purpose is to exercise
   * CommandManager legacy dispatch. Not used in production.
   */
  private static final class DummyCommandRequest extends CommandRequestTextAbstract {

    DummyCommandRequest() {
      super("dummy");
    }

    @Override
    public boolean isIdempotent() {
      return true;
    }

    @Override
    public List<EntityImpl> execute(DatabaseSessionEmbedded querySession, Object... iArgs) {
      return List.of();
    }
  }

  /**
   * Minimal CommandExecutor whose only purpose is to satisfy
   * CommandManager.registerExecutor's class-based contract. Not used in production.
   * Extends {@link CommandExecutorAbstract} to inherit the bulk of the interface.
   *
   * <p>Declared {@code public} out of necessity: {@link CommandManager#getExecutor}
   * calls {@code Class.newInstance()} without {@code setAccessible(true)}, which
   * requires a public class with a public no-arg ctor.
   */
  public static final class DummyCommandExecutor extends CommandExecutorAbstract {

    public DummyCommandExecutor() {
      // public no-arg ctor so CommandManager.getExecutor can instantiate us reflectively.
    }

    @Override
    @SuppressWarnings("unchecked")
    public DummyCommandExecutor parse(
        DatabaseSessionEmbedded session, CommandRequest iRequest) {
      return this;
    }

    @Override
    public Object execute(DatabaseSessionEmbedded session, Map<Object, Object> iArgs) {
      return null;
    }

    @Override
    public boolean isIdempotent() {
      return true;
    }

    @Override
    public String getSyntax() {
      return "dummy";
    }

    @Override
    protected void throwSyntaxErrorException(String dbName, String iText) {
      throw new IllegalStateException("unused: " + iText);
    }
  }

  /**
   * Intentionally abstract — {@link Class#newInstance()} on an abstract class throws
   * {@link InstantiationException}, which drives CommandManager into its catch block.
   * Used only by {@link #commandManagerGetExecutorWrapsInstantiationFailureThroughContextChain}.
   */
  public abstract static class UninstantiableExecutor extends CommandExecutorAbstract {
    // abstract by design; no ctor needed.
  }
}
