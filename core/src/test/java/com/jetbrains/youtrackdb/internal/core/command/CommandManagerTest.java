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
package com.jetbrains.youtrackdb.internal.core.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Standalone tests for the live-path surface of {@link CommandManager} — the script-executor
 * registry and its close-propagation contract. The legacy class-based dispatch surface (empty
 * {@code commandReqExecMap}, {@code registerExecutor(Class, Class)},
 * {@code getExecutor(CommandRequestInternal)} catch branch, callback dispatch) is pinned as dead
 * code by Step 1's
 * {@link com.jetbrains.youtrackdb.internal.core.command.script.CommandScriptDeadCodeTest} and is
 * not re-covered here.
 *
 * <p>Each test constructs a fresh {@link CommandManager} so the constructor-installed defaults
 * ({@code "sql"} and {@code "script"} executors) start in a known state.
 */
public class CommandManagerTest {

  // ---------------------------------------------------------------------------
  // Constructor defaults — ctor registers "sql" and "script".
  // Source: CommandManager.java:39-42.
  // ---------------------------------------------------------------------------

  /**
   * The default constructor registers both {@code "sql"} and {@code "script"} language entries
   * with a {@link SqlScriptExecutor}. Today each language gets its OWN {@link SqlScriptExecutor}
   * instance (two {@code new} calls in the ctor). Pin that shape so a future refactor to share a
   * singleton is a deliberate, caught change.
   */
  @Test
  public void constructorRegistersSqlAndScriptWithSqlScriptExecutor() {
    var manager = new CommandManager();

    var sql = manager.getScriptExecutor("sql");
    var script = manager.getScriptExecutor("script");

    assertNotNull("'sql' must be registered by the default ctor", sql);
    assertNotNull("'script' must be registered by the default ctor", script);
    assertTrue("'sql' executor must be a SqlScriptExecutor",
        sql instanceof SqlScriptExecutor);
    assertTrue("'script' executor must be a SqlScriptExecutor",
        script instanceof SqlScriptExecutor);
    // Distinct instances today — the ctor calls `new SqlScriptExecutor()` twice.
    assertFalse("each language currently gets its own executor instance",
        sql == script);
  }

  // ---------------------------------------------------------------------------
  // getScriptExecutor — null, exact-match, lowercase-fallback, unknown.
  // Source: CommandManager.java:44-58.
  // ---------------------------------------------------------------------------

  /**
   * {@code getScriptExecutor(null)} rejects null with {@link IllegalArgumentException} and the
   * exact (legacy) message {@code "Invalid script languange: null"} — note the historical
   * misspelling. Pin the spelling: downstream tooling may match on it.
   */
  @Test
  public void getScriptExecutorNullLanguageThrowsWithLegacyMessage() {
    var manager = new CommandManager();

    var ex = assertThrows(IllegalArgumentException.class,
        () -> manager.getScriptExecutor(null));
    assertEquals("Invalid script languange: null", ex.getMessage());
  }

  /**
   * An exact-match key hits the first lookup (line 48) and skips the lowercase fallback.
   */
  @Test
  public void getScriptExecutorExactKeyReturnsRegisteredExecutor() {
    var manager = new CommandManager();
    ScriptExecutor custom = new RecordingScriptExecutor();
    manager.registerScriptExecutor("custom", custom);

    assertSame("exact-key lookup must return the registered executor",
        custom, manager.getScriptExecutor("custom"));
  }

  /**
   * Case-mismatched key falls through to the lowercase retry at line 50. {@code "SQL"} misses the
   * exact lookup, then succeeds as {@code "sql"}.
   */
  @Test
  public void getScriptExecutorUppercaseKeyFallsBackToLowercase() {
    var manager = new CommandManager();
    var uppercaseLookup = manager.getScriptExecutor("SQL");

    // The same as the lowercase lookup.
    assertSame("uppercase key must resolve via lowercase fallback",
        manager.getScriptExecutor("sql"), uppercaseLookup);
  }

  /**
   * A language that is neither an exact nor a lowercase match throws
   * {@link IllegalArgumentException} with a message that includes the caller-supplied language
   * (line 52-54). Pin that the message names the language to help callers diagnose the miss.
   */
  @Test
  public void getScriptExecutorUnknownLanguageThrowsWithOriginalCase() {
    var manager = new CommandManager();

    var ex = assertThrows(IllegalArgumentException.class,
        () -> manager.getScriptExecutor("WeirdLang"));
    assertTrue("error message must include the requested language: " + ex.getMessage(),
        ex.getMessage().contains("WeirdLang"));
  }

  /**
   * TC4 iter-2 boundary pin: empty-string language is NOT null (passes the null-guard) but
   * also not a registered key — takes the exact-miss → lowercase-miss → throw path. Pin that
   * the diagnostic message still includes the "language: " prefix so the trailing empty
   * string is at least signalled. A regression that concatenated a null variable or silently
   * returned a default executor for empty input would be caught.
   */
  @Test
  public void getScriptExecutorEmptyStringThrowsWithEmptyLanguageInMessage() {
    var manager = new CommandManager();

    var ex = assertThrows(IllegalArgumentException.class,
        () -> manager.getScriptExecutor(""));
    assertTrue(
        "error message must include the 'language:' diagnostic marker: " + ex.getMessage(),
        ex.getMessage().contains("language:"));
  }

  /**
   * TC4 iter-2 boundary pin: {@link HashMap#put} accepts {@code null} keys, so
   * {@code registerScriptExecutor(null, executor)} stores the entry under a null key.
   * {@code getScriptExecutor(null)} still throws at the null-guard, so the null-keyed entry
   * is effectively unreachable via the public API. Pin both halves so a future hardening
   * (null-rejecting {@code registerScriptExecutor}) is a deliberate, visible change.
   */
  @Test
  public void registerScriptExecutorWithNullKeyIsStoredButUnreachableViaGetter() {
    var manager = new CommandManager();
    ScriptExecutor custom = new RecordingScriptExecutor();

    manager.registerScriptExecutor(null, custom);

    // The backing map exposes the null-keyed entry directly.
    assertTrue(
        "underlying HashMap accepts null keys — entry must be visible in the exposed map",
        manager.getScriptExecutors().containsKey(null));
    assertSame(
        "null-keyed entry resolves to the registered executor via direct map access",
        custom,
        manager.getScriptExecutors().get(null));
    // The public getter still rejects null — the null-keyed entry is effectively dead.
    assertThrows(IllegalArgumentException.class, () -> manager.getScriptExecutor(null));
  }

  // ---------------------------------------------------------------------------
  // registerScriptExecutor / getScriptExecutors.
  // Source: CommandManager.java:68-74.
  // ---------------------------------------------------------------------------

  /**
   * {@code registerScriptExecutor} replaces an existing entry (the underlying map is a plain
   * {@link java.util.HashMap}, so put overwrites). Pin that replacement is atomic and visible to
   * subsequent lookups.
   */
  @Test
  public void registerScriptExecutorReplacesExistingEntry() {
    var manager = new CommandManager();
    var before = manager.getScriptExecutor("sql");
    ScriptExecutor replacement = new RecordingScriptExecutor();

    manager.registerScriptExecutor("sql", replacement);

    assertSame("registerScriptExecutor must replace the previous entry",
        replacement, manager.getScriptExecutor("sql"));
    assertFalse("replacement must be a distinct instance from the ctor default",
        before == replacement);
  }

  /**
   * {@code getScriptExecutors()} exposes the backing map — default ctor state contains exactly
   * {@code "sql"} and {@code "script"}. After a register, the map reflects the new entry.
   */
  @Test
  public void getScriptExecutorsExposesBackingMap() {
    var manager = new CommandManager();

    var map = manager.getScriptExecutors();
    assertTrue("default 'sql' key", map.containsKey("sql"));
    assertTrue("default 'script' key", map.containsKey("script"));
    assertEquals("only two default registrations", 2, map.size());

    manager.registerScriptExecutor("custom", new RecordingScriptExecutor());

    // The returned map is the live backing map — changes are visible.
    assertTrue("registered 'custom' is visible through the exposed map",
        manager.getScriptExecutors().containsKey("custom"));
  }

  // ---------------------------------------------------------------------------
  // close(dbName) / closeAll — propagation to all script executors.
  // Source: CommandManager.java:118-128.
  // ---------------------------------------------------------------------------

  /**
   * {@code close(dbName)} iterates {@link Map#values()} and invokes {@link ScriptExecutor#close}
   * on each. With the ctor's default registrations ({@code sql}, {@code script}), our two stubs
   * each receive exactly one close call with the caller-supplied db name.
   */
  @Test
  public void closeDbNamePropagatesToAllRegisteredExecutors() {
    var manager = new CommandManager();
    var execA = new RecordingScriptExecutor();
    var execB = new RecordingScriptExecutor();

    // Replace both defaults so we can observe the propagation.
    manager.registerScriptExecutor("sql", execA);
    manager.registerScriptExecutor("script", execB);

    manager.close("myDb");

    assertEquals("execA must receive exactly one close(dbName) call",
        List.of("myDb"), execA.closeCalls);
    assertEquals("execB must receive exactly one close(dbName) call",
        List.of("myDb"), execB.closeCalls);
    // closeAll must NOT have been invoked — close(dbName) is scoped.
    assertEquals("execA must not see closeAll on a scoped close",
        0, execA.closeAllCount);
    assertEquals(0, execB.closeAllCount);
  }

  /**
   * {@code closeAll()} iterates {@link Map#values()} and invokes {@link ScriptExecutor#closeAll}
   * on each. Pin that scoped {@code close(dbName)} is NOT a side-effect of {@code closeAll}.
   */
  @Test
  public void closeAllPropagatesToAllRegisteredExecutors() {
    var manager = new CommandManager();
    var execA = new RecordingScriptExecutor();
    var execB = new RecordingScriptExecutor();

    manager.registerScriptExecutor("sql", execA);
    manager.registerScriptExecutor("script", execB);

    manager.closeAll();

    assertEquals("execA must receive exactly one closeAll call",
        1, execA.closeAllCount);
    assertEquals("execB must receive exactly one closeAll call",
        1, execB.closeAllCount);
    assertTrue("execA must not see close(dbName) on a full close",
        execA.closeCalls.isEmpty());
    assertTrue(execB.closeCalls.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // Minimal ScriptExecutor stub that records close() calls for assertions.
  // ---------------------------------------------------------------------------

  /**
   * Records which {@code close(dbName)} / {@code closeAll} calls it receives so tests can verify
   * propagation through {@link CommandManager#close(String)} and {@link CommandManager#closeAll}.
   * All execute/executeFunction/interceptor methods throw {@link UnsupportedOperationException} —
   * the tests in this suite never invoke them.
   */
  private static final class RecordingScriptExecutor implements ScriptExecutor {
    final List<String> closeCalls = new ArrayList<>();
    int closeAllCount;

    @Override
    public ResultSet execute(DatabaseSessionEmbedded database, String script, Object... params) {
      throw new UnsupportedOperationException("not expected in CommandManagerTest");
    }

    @Override
    public ResultSet execute(DatabaseSessionEmbedded database, String script, Map params) {
      throw new UnsupportedOperationException("not expected in CommandManagerTest");
    }

    @Override
    public Object executeFunction(CommandContext context, String functionName,
        Map<Object, Object> iArgs) {
      throw new UnsupportedOperationException("not expected in CommandManagerTest");
    }

    @Override
    public void registerInterceptor(ScriptInterceptor interceptor) {
      throw new UnsupportedOperationException("not expected in CommandManagerTest");
    }

    @Override
    public void unregisterInterceptor(ScriptInterceptor interceptor) {
      throw new UnsupportedOperationException("not expected in CommandManagerTest");
    }

    @Override
    public void close(String iDatabaseName) {
      closeCalls.add(iDatabaseName);
    }

    @Override
    public void closeAll() {
      closeAllCount++;
    }
  }
}
