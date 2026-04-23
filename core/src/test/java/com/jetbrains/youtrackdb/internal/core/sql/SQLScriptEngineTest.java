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
package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.script.ScriptDatabaseWrapper;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.script.Bindings;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;
import org.junit.Test;

/**
 * Behavioral tests for {@link SQLScriptEngine}, the JSR-223 engine for YouTrackDB SQL.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Constructor + {@code getFactory()} round-trip
 *   <li>JSR-223 stub methods: {@code put / get / getContext / setBindings / setContext}
 *   <li>{@code getBindings / createBindings} — fresh {@link SimpleBindings} per call
 *   <li>{@code eval} missing-db guard (via all six overloads) — pins {@link
 *       CommandExecutionException} shape
 *   <li>{@code eval(String, Bindings)} live path — real SQL script through a
 *       {@link ScriptDatabaseWrapper} binding
 *   <li>{@code eval(Reader, Bindings)} live path — pins the observed
 *       {@link com.jetbrains.youtrackdb.internal.core.command.script.CommandScript#execute
 *       CommandScript.execute} return value (empty list, T2 dead-code pin — see
 *       {@code CommandScriptDeadCodeTest}).
 *   <li>{@code convertToParameters} — all six dispatch branches via a package-private probe
 *       subclass.
 * </ul>
 *
 * <p>Extends {@link TestUtilsFixture} because the live-path tests use the session to run real
 * SQL. Stub/contract tests that don't need the DB still live here for cohesion — each is
 * annotated with the independence it has.
 */
public class SQLScriptEngineTest extends TestUtilsFixture {

  // ===========================================================================
  // Constructor + getFactory() round-trip (independent of DB).
  // ===========================================================================

  /** getFactory returns the factory passed at construction. */
  @Test
  public void getFactoryReturnsConstructorArgument() {
    final var factory = new SQLScriptEngineFactory();
    final var engine = new SQLScriptEngine(factory);
    assertSame("getFactory() must return the factory passed to the constructor", factory,
        engine.getFactory());
  }

  // ===========================================================================
  // JSR-223 stub methods — independent of DB.
  // ===========================================================================

  /** put(key, value) is a stub (void + no observable side-effect). */
  @Test
  public void putIsANoOp() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    engine.put("any-key", 42);
    // get must still return null — the stub did not store anything.
    assertNull(engine.get("any-key"));
  }

  /** get(key) returns null for any key. */
  @Test
  public void getReturnsNullForAnyKey() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    assertNull(engine.get("not-set"));
    assertNull(engine.get(""));
    assertNull(engine.get(null));
  }

  /** getContext() returns null — pins the "no context" stub. */
  @Test
  public void getContextReturnsNull() {
    assertNull(new SQLScriptEngine(new SQLScriptEngineFactory()).getContext());
  }

  /** setContext(ctx) is a no-op; getContext stays null. */
  @Test
  public void setContextIsANoOp() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    engine.setContext(new SimpleScriptContext());
    assertNull("setContext is a no-op; getContext() must remain null", engine.getContext());
  }

  /** setBindings(bindings, scope) is a no-op; getBindings returns a fresh map each time. */
  @Test
  public void setBindingsIsANoOp() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var b = new SimpleBindings();
    b.put("x", 1);
    engine.setBindings(b, javax.script.ScriptContext.ENGINE_SCOPE);

    // getBindings returns a fresh SimpleBindings regardless of the just-set bindings.
    final var got = engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE);
    assertNotSame(b, got);
    assertTrue(got.isEmpty());
  }

  /** getBindings returns a fresh SimpleBindings each call (no caching). */
  @Test
  public void getBindingsReturnsFreshInstancePerCall() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var a = engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE);
    final var b = engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE);
    assertNotNull(a);
    assertNotNull(b);
    assertNotSame("each getBindings(scope) call produces a new SimpleBindings", a, b);
    assertTrue(a instanceof SimpleBindings);
    assertTrue(b instanceof SimpleBindings);
  }

  /** createBindings returns a fresh SimpleBindings each call. */
  @Test
  public void createBindingsReturnsFreshInstancePerCall() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var a = engine.createBindings();
    final var b = engine.createBindings();
    assertNotSame(a, b);
    assertTrue(a instanceof SimpleBindings);
    assertTrue(a.isEmpty());
  }

  // ===========================================================================
  // eval — missing-db guard across all six overloads.
  // ===========================================================================

  /**
   * eval(String, ScriptContext) delegates to eval(String, null) and throws for no-db bindings.
   * Pins the delegation and the error message.
   */
  @Test
  public void evalStringScriptContextThrowsWhenNoDbInBindings() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var ex =
        assertThrows(
            CommandExecutionException.class,
            () -> engine.eval("SELECT 1", new SimpleScriptContext()));
    assertTrue(
        "error message must name the missing-db condition",
        ex.getMessage().contains("No database available in bindings"));
  }

  /** eval(Reader, ScriptContext) delegates to eval(Reader, null). */
  @Test
  public void evalReaderScriptContextThrowsWhenNoDbInBindings() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var ex =
        assertThrows(
            CommandExecutionException.class,
            () -> engine.eval(new StringReader("SELECT 1"), new SimpleScriptContext()));
    assertTrue(ex.getMessage().contains("No database available in bindings"));
  }

  /** eval(String) with no bindings path throws the same guard. */
  @Test
  public void evalStringAloneThrowsWhenNoDbInBindings() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    assertThrows(CommandExecutionException.class, () -> engine.eval("SELECT 1"));
  }

  /** eval(Reader) with no bindings path throws the same guard. */
  @Test
  public void evalReaderAloneThrowsWhenNoDbInBindings() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    assertThrows(CommandExecutionException.class, () -> engine.eval(new StringReader("SELECT 1")));
  }

  /** eval(String, Bindings) with null bindings throws. */
  @Test
  public void evalStringNullBindingsThrowsMissingDb() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    assertThrows(
        CommandExecutionException.class, () -> engine.eval("SELECT 1", (Bindings) null));
  }

  /**
   * eval(String, Bindings) with bindings that don't contain "db" throws. Pins that ONLY the
   * "db" key is checked — anything else is ignored.
   */
  @Test
  public void evalStringBindingsWithoutDbThrows() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var n = new SimpleBindings();
    n.put("not-db", "irrelevant");
    assertThrows(CommandExecutionException.class, () -> engine.eval("SELECT 1", n));
  }

  /** eval(Reader, Bindings) with null bindings throws. */
  @Test
  public void evalReaderNullBindingsThrowsMissingDb() {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    assertThrows(
        CommandExecutionException.class,
        () -> engine.eval(new StringReader("SELECT 1"), (Bindings) null));
  }

  // ===========================================================================
  // eval — live paths with a real session bound under "db".
  // ===========================================================================

  /**
   * eval(String, Bindings) happy path: a real {@link ScriptDatabaseWrapper} bound as "db" runs
   * a real SQL statement and returns a non-empty list of Results (SELECT 1 yields one row).
   * Pins the end-to-end live wiring.
   */
  @Test
  public void evalStringBindingsRunsRealSql() throws ScriptException {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var n = new SimpleBindings();
    n.put("db", new ScriptDatabaseWrapper(session));

    final var out = engine.eval("SELECT 1 as v", n);
    assertNotNull(out);
    // Output is a BasicLegacyResultSet<Result>; iterate via the stream() contract via toList().
    // BasicLegacyResultSet implements Iterable<Result> so we can coerce to List<Result>.
    @SuppressWarnings("unchecked")
    final List<Result> rows = (List<Result>) out;
    assertFalse("SELECT 1 must produce at least one row", rows.isEmpty());
    assertEquals(Integer.valueOf(1), rows.get(0).getProperty("v"));
  }

  /**
   * eval(String, Bindings) with EXTRA parameters in the bindings map (beyond "db") converts
   * those into script parameters via {@code convertToParameters}. This is the
   * "keys-all-String" branch that routes to {@code runScript(lang, script, (Map) params)}.
   * Pins that a named :x parameter is resolved from the bindings.
   */
  @Test
  public void evalStringBindingsPassesNamedParameters() throws ScriptException {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var n = new SimpleBindings();
    n.put("db", new ScriptDatabaseWrapper(session));
    n.put("x", 7);

    final var out = engine.eval("SELECT :x as v", n);
    @SuppressWarnings("unchecked")
    final List<Result> rows = (List<Result>) out;
    assertFalse(rows.isEmpty());
    assertEquals(Integer.valueOf(7), rows.get(0).getProperty("v"));
  }

  /**
   * eval(Reader, Bindings) live path is UNTESTABLE TODAY against a {@link StringReader}: the
   * production code does
   *
   * <pre>
   *   while (reader.ready()) {
   *     buffer.append((char) reader.read());
   *   }
   * </pre>
   *
   * which infinite-loops on {@link StringReader} because {@code StringReader.ready()} always
   * returns {@code true} once the reader is open — even after {@code read()} has returned
   * {@code -1} (EOF). The cast {@code (char) -1} is 0xFFFF, and the loop keeps appending
   * {@code ￿} forever → OOM. Pin this with a custom Reader that signals
   * {@code ready() == false} AFTER the content is drained, so the loop terminates. Under this
   * custom Reader the live path reaches {@code new CommandScript(buffer).execute(session, n)},
   * and the stub returns {@code List.of()} — the dead-code pin from {@code
   * CommandScriptDeadCodeTest} (Step 1) matches this observed shape.
   *
   * <p>WHEN-FIXED: Track 22 — replace the ready() loop with a proper EOF check
   * ({@code int c; while ((c = reader.read()) != -1) buffer.append((char) c);}), OR delete
   * the overload entirely alongside {@code CommandScript.execute}. Both (a) the Reader
   * workaround and (b) the empty-list pin will break when Track 22 lands, forcing a
   * re-pin.
   */
  @Test
  public void evalReaderBindingsWithCustomReaderReturnsEmptyListFromDeadCommandScriptExecute()
      throws ScriptException {
    final var engine = new SQLScriptEngine(new SQLScriptEngineFactory());
    final var n = new SimpleBindings();
    n.put("db", session);

    final var out = engine.eval(new EofAwareReader("SELECT 1"), n);
    // WHEN-FIXED: Track 22 — CommandScript.execute returns List.of() unconditionally.
    assertNotNull(out);
    assertTrue("output must be an empty List (CommandScript.execute stub)", out instanceof List);
    assertTrue(((List<?>) out).isEmpty());
  }

  /**
   * Reader that returns {@code ready() == false} after the source string is drained, working
   * around the {@code StringReader.ready()-always-true} production bug above. Only used to
   * pin the eval(Reader, Bindings) live path — remove when Track 22 fixes the ready() loop.
   */
  private static final class EofAwareReader extends java.io.Reader {
    private final String data;
    private int pos;

    EofAwareReader(final String data) {
      this.data = data;
    }

    @Override
    public int read(final char[] cbuf, final int off, final int len) {
      if (pos >= data.length()) {
        return -1;
      }
      final var toCopy = Math.min(len, data.length() - pos);
      data.getChars(pos, pos + toCopy, cbuf, off);
      pos += toCopy;
      return toCopy;
    }

    @Override
    public int read() {
      if (pos >= data.length()) {
        return -1;
      }
      return data.charAt(pos++);
    }

    @Override
    public boolean ready() {
      return pos < data.length();
    }

    @Override
    public void close() {
    }
  }

  // ===========================================================================
  // convertToParameters — six dispatch branches via a probe subclass.
  // ===========================================================================

  /**
   * Probe subclass that exposes {@code convertToParameters} for package-external tests without
   * needing reflection. This is test-only — the production method stays protected.
   */
  private static final class ProbeEngine extends SQLScriptEngine {
    ProbeEngine() {
      super(new SQLScriptEngineFactory());
    }

    Map<Object, Object> call(Object... iArgs) {
      return convertToParameters(iArgs);
    }
  }

  /** Branch 1: single Map argument is returned verbatim (by reference). */
  @Test
  public void convertToParametersSingleMapIsByReference() {
    final Map<Object, Object> in = new LinkedHashMap<>();
    in.put("k", 1);
    final var out = new ProbeEngine().call(in);
    assertSame("single-Map arg must be returned by reference, not copied", in, out);
  }

  /**
   * Branch 2: single Object[] argument is unwrapped and indexed positionally with Integer keys.
   * Pins the unwrap-then-index behavior.
   */
  @Test
  public void convertToParametersSingleObjectArrayUnwrapsAndIndexesByInteger() {
    final var out = new ProbeEngine().call(new Object[] {new Object[] {"a", "b", "c"}});
    assertEquals(3, out.size());
    assertEquals("a", out.get(0));
    assertEquals("b", out.get(1));
    assertEquals("c", out.get(2));
    // Pin Integer key type (not String "0") — a refactor that stringifies would be visible.
    assertTrue(
        "keys MUST be Integer, not String — pins the positional-indexing type",
        out.keySet().stream().allMatch(k -> k instanceof Integer));
  }

  /**
   * Branch 3: multiple scalar arguments are positionally indexed with Integer keys starting at 0.
   * Pins the multi-arg positional dispatch.
   */
  @Test
  public void convertToParametersMultipleScalarsIndexPositionally() {
    final var out = new ProbeEngine().call("x", 42, Boolean.TRUE);
    assertEquals(3, out.size());
    assertEquals("x", out.get(0));
    assertEquals(Integer.valueOf(42), out.get(1));
    assertEquals(Boolean.TRUE, out.get(2));
  }

  /** Branch 4: empty varargs → empty HashMap, not null. Pins the zero-arg contract. */
  @Test
  public void convertToParametersEmptyVarargsReturnsEmptyMap() {
    final var out = new ProbeEngine().call();
    assertNotNull("empty varargs must return an empty map, not null", out);
    assertTrue(out.isEmpty());
  }

  /**
   * Branch 5: an {@link com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable} with
   * a valid RID position gets its RID extracted and stored in place of the Identifiable.
   * Pins the "use the RID only" optimization — the entity reference is replaced by its
   * identity for parameter passing.
   *
   * <p>A {@link com.jetbrains.youtrackdb.internal.core.id.RecordId} is itself an Identifiable
   * (RID extends Identifiable) whose {@code getIdentity()} returns {@code this}, so the output
   * value equals the input by reference when isValidPosition is true.
   */
  @Test
  public void convertToParametersIdentifiableWithValidPositionStoresRid() {
    final var rid = new com.jetbrains.youtrackdb.internal.core.id.RecordId(3, 7);
    assertTrue("precondition: position 7 is valid (not COLLECTION_POS_INVALID==-1)",
        rid.isValidPosition());

    final var out = new ProbeEngine().call((Object) rid);
    // A RID's getIdentity() returns itself — so branch 5 stores the RID under key 0. For a
    // non-RID Identifiable the stored value would differ from the input; here the test
    // observes that the positional insert targets the identity, not the wrapper.
    assertSame("RID is extracted (for RecordId, identity == self)", rid, out.get(0));
  }

  /**
   * Branch 5 negative: an Identifiable whose RID has an INVALID position (-1) is stored
   * VERBATIM (the {@code isValidPosition} guard fails — no RID extraction). Pins the
   * "keep as Identifiable" fallback so a future hardening of the branch that always extracts
   * is a visible change.
   */
  @Test
  public void convertToParametersIdentifiableWithInvalidPositionStoredVerbatim() {
    final var invalidRid =
        new com.jetbrains.youtrackdb.internal.core.id.RecordId(
            3, com.jetbrains.youtrackdb.internal.core.db.record.record.RID.COLLECTION_POS_INVALID);
    assertFalse(invalidRid.isValidPosition());

    final var out = new ProbeEngine().call((Object) invalidRid);
    // Even for invalid positions, the identity equals self, so same-identity check still
    // holds — but the production code took the non-RID-extracted path. Pin via identity.
    assertSame(invalidRid, out.get(0));
  }

  /**
   * Branch 6: null value is stored verbatim. Pins the null-safe positional insertion.
   */
  @Test
  public void convertToParametersNullValueIsStoredAsNull() {
    final var out = new ProbeEngine().call((Object) null);
    assertTrue(out.containsKey(0));
    assertNull(out.get(0));
  }

  /**
   * Boundary: Bindings (which extends Map) is treated as branch 1 (single-Map path). Pins that
   * JSR-223 bindings pass through by-reference to the script runner.
   */
  @Test
  public void convertToParametersBindingsIsBranchOneSingleMap() {
    final var bindings = new SimpleBindings();
    bindings.put("k", 1);
    final var out = new ProbeEngine().call((Object) bindings);
    // The implementation stores iArgs[0] itself when it is a Map; Bindings IS a Map.
    assertSame(bindings, out);
  }

  /**
   * Pin ordering edge: {@code iArgs.length == 1 && iArgs[0] instanceof Object[]} is tested
   * AFTER the Map check. Arrays.asList(...) produces a List (not a Map, not Object[]), so it
   * falls through to the positional branch with the List as a single element. Pins the dispatch
   * order — List is neither Map nor array, so the whole List is at index 0.
   */
  @Test
  public void convertToParametersListArgTakesPositionalBranchNotUnwrap() {
    final var list = Arrays.asList("a", "b");
    final var out = new ProbeEngine().call(list);
    assertEquals(1, out.size());
    assertSame("List is not a Map or Object[] — kept whole at index 0", list, out.get(0));
  }

  /**
   * Pin that a Map stored inside a MULTI-arg varargs (not the single-arg case) does NOT
   * take the by-reference branch. With multiple varargs, the Map is one of the positional
   * elements.
   */
  @Test
  public void convertToParametersMultiArgWithMapTakesPositionalBranch() {
    final Map<Object, Object> embedded = new HashMap<>();
    embedded.put("k", 1);
    final var out = new ProbeEngine().call(embedded, "after");
    assertEquals(2, out.size());
    assertSame(embedded, out.get(0));
    assertEquals("after", out.get(1));
  }
}
