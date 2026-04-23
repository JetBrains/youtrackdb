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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.ScriptInterceptor;
import com.jetbrains.youtrackdb.internal.core.command.traverse.AbstractScriptExecutor;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.SimpleBindings;
import org.junit.Test;

/**
 * Minimal wiring smoke tests for the two script SPIs that are live but have zero core
 * implementations: {@link ScriptInjection} and {@link ScriptInterceptor}.
 *
 * <p>The SPI interfaces are wired into live code ({@link ScriptManager#bindContextVariables} /
 * {@link ScriptManager#unbind} iterate {@code injections}; {@link AbstractScriptExecutor}
 * iterates its interceptor list before dispatching). Production never registers an impl, so
 * the register/unregister and bind/unbind loops stay uncovered unless exercised here.
 *
 * <p>All tests are standalone — the SPIs neither require a database session nor a transaction.
 * A {@code null} {@link DatabaseSessionEmbedded} is accepted by both code paths.
 */
public class SPIWiringSmokeTest {

  // ---------------------------------------------------------------------------
  // ScriptInjection — ScriptManager.registerInjection + bindInjectors + unbind
  // ---------------------------------------------------------------------------

  @Test
  public void scriptInjectionRegisterDeduplicatesBySameInstance() {
    // registerInjection guards against re-adding the same instance (ScriptManager.java:471-475);
    // verify the list grows once despite two register calls with the same impl.
    var manager = new ScriptManager();
    var counter = new AtomicInteger();
    var injection = new RecordingInjection(counter);

    manager.registerInjection(injection);
    manager.registerInjection(injection);

    var injections = manager.getInjections();
    assertEquals("duplicate registration must be suppressed", 1, injections.size());
    assertSame("the stored injection must be the one we registered", injection, injections.get(0));
  }

  @Test
  public void scriptInjectionUnregisterRemovesTheInstance() {
    var manager = new ScriptManager();
    var counter = new AtomicInteger();
    var injection = new RecordingInjection(counter);

    manager.registerInjection(injection);
    assertEquals(1, manager.getInjections().size());

    manager.unregisterInjection(injection);
    assertTrue(
        "after unregister the list must be empty",
        manager.getInjections().isEmpty());
  }

  @Test
  public void scriptInjectionBindAndUnbindAreInvokedDuringBindContextVariables() {
    // Exercises the injection iteration loop in bindInjectors (lines 328-333)
    // and unbind (lines 447-449). No database required — bindDatabase accepts null.
    var manager = new ScriptManager();
    var bindCount = new AtomicInteger();
    var unbindCount = new AtomicInteger();
    manager.registerInjection(new RecordingInjection(bindCount, unbindCount));

    Bindings binding = new SimpleBindings();
    manager.bindContextVariables(
        /* engine    = */ null,
        /* binding   = */ binding,
        /* db        = */ (DatabaseSessionEmbedded) null,
        /* iContext  = */ null,
        /* iArgs     = */ new HashMap<>());

    assertEquals("ScriptInjection.bind must be called exactly once", 1, bindCount.get());
    assertEquals(
        "ScriptInjection.unbind must NOT be called from bindContextVariables",
        0,
        unbindCount.get());

    manager.unbind(/* engine */ null, binding, /* context */ null, /* iArgs */ null);
    assertEquals(
        "ScriptInjection.unbind must be called exactly once by ScriptManager.unbind",
        1,
        unbindCount.get());
  }

  // ---------------------------------------------------------------------------
  // ScriptInterceptor — AbstractScriptExecutor.registerInterceptor /
  // unregisterInterceptor / preExecute iteration.
  // ---------------------------------------------------------------------------

  @Test
  public void scriptInterceptorRegisterUnregisterAndDispatch() {
    // AbstractScriptExecutor's interceptor list is private; we observe its state
    // through preExecute dispatch. Registering must route the call; unregistering
    // must stop future dispatches.
    var executor = new RecordingScriptExecutor("sql");
    var calls = new java.util.ArrayList<String>();

    ScriptInterceptor interceptor =
        (db, language, script, params) -> calls.add(language + ":" + script);

    executor.registerInterceptor(interceptor);
    executor.preExecute(null, "SELECT 1", null);
    assertEquals(
        "registered interceptor must receive the preExecute call",
        List.of("sql:SELECT 1"),
        calls);

    executor.unregisterInterceptor(interceptor);
    executor.preExecute(null, "SELECT 2", null);
    assertEquals(
        "unregistered interceptor must receive no further preExecute calls",
        List.of("sql:SELECT 1"),
        calls);
  }

  @Test
  public void scriptInterceptorMultipleInterceptorsAreAllInvokedInOrder() {
    // preExecute iterates the interceptors list in insertion order — pin that ordering.
    var executor = new RecordingScriptExecutor("javascript");
    var calls = new java.util.ArrayList<String>();

    executor.registerInterceptor(
        (db, language, script, params) -> calls.add("first:" + script));
    executor.registerInterceptor(
        (db, language, script, params) -> calls.add("second:" + script));

    executor.preExecute(null, "print(1)", null);

    assertEquals(
        "both interceptors must fire, in registration order",
        List.of("first:print(1)", "second:print(1)"),
        calls);
  }

  // ---------------------------------------------------------------------------
  // Fixture classes
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link ScriptInjection} that counts bind/unbind invocations. Used only by
   * this test; not a production impl.
   */
  private static final class RecordingInjection implements ScriptInjection {

    private final AtomicInteger bindCount;
    private final AtomicInteger unbindCount;

    RecordingInjection(AtomicInteger bindCount) {
      this(bindCount, new AtomicInteger());
    }

    RecordingInjection(AtomicInteger bindCount, AtomicInteger unbindCount) {
      this.bindCount = bindCount;
      this.unbindCount = unbindCount;
    }

    @Override
    public void bind(ScriptEngine engine, Bindings binding, DatabaseSessionEmbedded database) {
      bindCount.incrementAndGet();
    }

    @Override
    public void unbind(ScriptEngine engine, Bindings binding) {
      unbindCount.incrementAndGet();
    }
  }

  /**
   * Minimal subclass of {@link AbstractScriptExecutor} that provides no-op impls for the
   * mandatory execute / executeFunction methods (we only exercise the interceptor path).
   */
  private static final class RecordingScriptExecutor extends AbstractScriptExecutor {

    RecordingScriptExecutor(String language) {
      super(language);
    }

    @Override
    public ResultSet execute(DatabaseSessionEmbedded database, String script, Object... params) {
      return null;
    }

    @Override
    public ResultSet execute(DatabaseSessionEmbedded database, String script, Map params) {
      return null;
    }

    @Override
    public Object executeFunction(
        CommandContext context, String functionName, Map<Object, Object> iArgs) {
      return null;
    }
  }
}
