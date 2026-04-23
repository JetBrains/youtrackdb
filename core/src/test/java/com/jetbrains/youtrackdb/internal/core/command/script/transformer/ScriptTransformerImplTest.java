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
package com.jetbrains.youtrackdb.internal.core.command.script.transformer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.script.ScriptResultSet;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.MapTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.resultset.ResultSetTransformer;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.IteratorResultSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Behavioral tests for {@link ScriptTransformerImpl}, the default implementation of
 * {@link ScriptTransformer}. Covers the six dispatch branches of
 * {@link ScriptTransformerImpl#toResultSet(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded, Object)}
 * (null, ResultSet, Iterator, registered resultSetTransformer, polyglot {@link Value}, default)
 * and the two dispatch branches of {@link ScriptTransformerImpl#toResult(
 * com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded, Object)} (registered
 * transformer, default). Additional tests pin the registry-manipulation methods
 * ({@link ScriptTransformerImpl#registerResultTransformer} and
 * {@link ScriptTransformerImpl#registerResultSetTransformer}) and the
 * {@link ScriptTransformerImpl#doesHandleResult} probe.
 *
 * <p>Extends {@link TestUtilsFixture} because {@link ResultInternal} is created by the
 * transformer using the passed-in session and {@code setProperty} asserts the session is
 * active via {@code checkSession} when assertions are on. The rollback guard inherited from
 * the fixture handles mid-test transaction cleanup even though no transaction is started here.
 *
 * <p>Polyglot {@link Value} branches use a real GraalVM {@link Context} built per test so we
 * observe genuine {@code isNull / isString / isNumber / isHostObject / hasArrayElements}
 * dispatch rather than a synthetic mock that would let the production branch-dispatch
 * ordering diverge silently.
 */
public class ScriptTransformerImplTest extends TestUtilsFixture {

  private ScriptTransformerImpl transformer;

  /** Fresh polyglot context per test — closed in {@link #closePolyglotContext()}. */
  private Context polyglotContext;

  @Before
  public void initTransformer() {
    transformer = new ScriptTransformerImpl();
    polyglotContext =
        Context.newBuilder("js")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(name -> false)
            .build();
  }

  @After
  public void closePolyglotContext() {
    if (polyglotContext != null) {
      polyglotContext.close();
    }
  }

  // ===========================================================================
  // Constructor — Map.class transformer is always registered post-construction.
  // ===========================================================================

  /**
   * The constructor registers {@link MapTransformer} for {@link Map}{@code .class} unconditionally
   * (after the conditional Nashorn block). Pins that a fresh transformer accepts a Map value via
   * {@code doesHandleResult}. This is the convergence point that all downstream dispatches rely
   * on.
   */
  @Test
  public void constructorRegistersMapTransformerByDefault() {
    assertTrue(
        "fresh ScriptTransformerImpl must handle a Map via the built-in MapTransformer",
        transformer.doesHandleResult(new LinkedHashMap<>()));
  }

  /** A non-Map, non-registered value must NOT be handled by a freshly-constructed transformer. */
  @Test
  public void constructorDoesNotRegisterTransformerForUnrelatedType() {
    assertFalse(transformer.doesHandleResult("plain string"));
    assertFalse(transformer.doesHandleResult(42));
    assertFalse(transformer.doesHandleResult(new int[] {1, 2, 3}));
  }

  // ===========================================================================
  // toResultSet — six dispatch branches in declared order.
  // ===========================================================================

  /**
   * Branch 1: null value → delegates to {@code ScriptResultSets.empty(db)}, returning an empty
   * {@link ScriptResultSet}. Pins the empty-result shape.
   */
  @Test
  public void toResultSetWithNullReturnsEmptyResultSet() {
    try (var rs = transformer.toResultSet(session, null)) {
      assertNotNull("null input must map to a non-null empty ScriptResultSet", rs);
      assertFalse("empty result set has no elements", rs.hasNext());
    }
  }

  /**
   * Branch 2: value is already a {@link ResultSet} → identity pass-through (no wrapping). Pins
   * the "don't re-wrap" invariant; ensures the same reference is returned, not a copy.
   */
  @Test
  public void toResultSetWithExistingResultSetIsIdentity() {
    final Iterator<Object> iter = Arrays.<Object>asList("a", "b").iterator();
    final ResultSet input = new IteratorResultSet(session, iter);

    try (var rs = transformer.toResultSet(session, input)) {
      assertSame("existing ResultSet must be returned verbatim with no re-wrapping", input, rs);
    }
  }

  /**
   * Branch 3: value is a raw {@link Iterator} → wrapped in a {@link ScriptResultSet}. Pins the
   * wrapping + iteration contract: the underlying iterator is drained one Result per element.
   */
  @Test
  public void toResultSetWithIteratorWrapsInScriptResultSet() {
    final var items = Arrays.asList("x", "y");
    try (var rs = transformer.toResultSet(session, items.iterator())) {
      assertTrue(rs instanceof ScriptResultSet);
      assertTrue(rs.hasNext());
      final var first = rs.next();
      assertEquals("x", first.getProperty("value"));
      assertTrue(rs.hasNext());
      final var second = rs.next();
      assertEquals("y", second.getProperty("value"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Branch 4: value's class is registered in {@code resultSetTransformers} → delegate to that
   * transformer (bypassing the default path). Pins the registry-first priority: a registered
   * transformer wins over the defaultResultSet fallback.
   */
  @Test
  public void toResultSetRegisteredTransformerWinsOverDefault() {
    final var sentinel =
        new IteratorResultSet(session, Arrays.<Object>asList("SENTINEL").iterator());
    final ResultSetTransformer<String> stringTransformer = value -> sentinel;
    transformer.registerResultSetTransformer(String.class, stringTransformer);

    try (var rs = transformer.toResultSet(session, "any-string")) {
      assertSame(
          "registered String→ResultSet transformer must be preferred over defaultResultSet",
          sentinel,
          rs);
    }
  }

  /**
   * Branch 5 (default): value's class is not Iterator/ResultSet/Value/registered → fall through
   * to {@code defaultResultSet}, which wraps the value in a singleton-list {@link
   * ScriptResultSet}. Pins that an arbitrary Java object becomes a one-element result stream
   * whose sole Result has a {@code "value"} property carrying the original.
   */
  @Test
  public void toResultSetDefaultWrapsArbitraryValueInSingletonResultSet() {
    try (var rs = transformer.toResultSet(session, 42)) {
      assertTrue(rs.hasNext());
      final var only = rs.next();
      assertEquals(Integer.valueOf(42), only.getProperty("value"));
      assertFalse("singleton wrapper must exhaust after one element", rs.hasNext());
    }
  }

  // ===========================================================================
  // toResultSet — polyglot Value branches (each exercises one isXxx check).
  // ===========================================================================

  /** Value.isNull() branch → {@code null} ResultSet returned. */
  @Test
  public void toResultSetPolyglotNullReturnsNull() {
    final var nullValue = polyglotContext.eval("js", "null");
    assertTrue("precondition: Value.isNull() is true for JS null", nullValue.isNull());

    final var rs = transformer.toResultSet(session, nullValue);
    assertNull("polyglot null maps to a null ResultSet (not empty, actually null)", rs);
  }

  /**
   * Value.isString() branch → unwrap to Java String, then fall through to defaultResultSet. Pins
   * that polyglot strings are coerced and wrapped in a singleton ResultSet.
   */
  @Test
  public void toResultSetPolyglotStringUnwrapsAndWraps() {
    final var strValue = polyglotContext.eval("js", "'hello'");
    assertTrue(strValue.isString());

    try (var rs = transformer.toResultSet(session, strValue)) {
      assertTrue(rs.hasNext());
      final var only = rs.next();
      assertEquals("hello", only.getProperty("value"));
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Value.isNumber() branch → unwrap to Java double. Pins the type-narrowing choice: JS numbers
   * always map to Double regardless of whether the source was integer or float.
   */
  @Test
  public void toResultSetPolyglotNumberCoercesToDouble() {
    final var numValue = polyglotContext.eval("js", "42");
    assertTrue(numValue.isNumber());

    try (var rs = transformer.toResultSet(session, numValue)) {
      assertTrue(rs.hasNext());
      assertEquals(Double.valueOf(42.0), rs.next().getProperty("value"));
    }
  }

  /**
   * Value.isHostObject() branch → unwrap the host object. Use a {@link java.util.Date} payload
   * because:
   * <ul>
   *   <li>{@link java.util.Date} is NOT auto-coerced by GraalVM into a polyglot primitive —
   *       {@code Value.isHostObject()} returns true (unlike Integer/String which are coerced).
   *   <li>{@link java.util.Date} IS a {@code PropertyTypeInternal.isSingleValueType}, so
   *       {@code ResultInternal.setProperty("value", date)} accepts it (unlike AtomicInteger
   *       or ArrayList which would fail at the Result-write step).
   * </ul>
   * Pins both (a) that hostObject unwraps to the original Java instance (identity) and
   * (b) that single-value-type host objects round-trip cleanly through the defaultResultSet
   * path.
   */
  @Test
  public void toResultSetPolyglotHostObjectUnwraps() {
    final var payload = new java.util.Date(1_000_000L);
    polyglotContext.getBindings("js").putMember("host", payload);
    final var hostVal = polyglotContext.eval("js", "host");
    assertTrue(
        "precondition: JS binding of a Java Date is a host-object Value", hostVal.isHostObject());

    try (var rs = transformer.toResultSet(session, hostVal)) {
      assertTrue(rs.hasNext());
      assertSame(
          "host-object Value must unwrap to the same Java instance — pinned identity",
          payload,
          rs.next().getProperty("value"));
    }
  }

  /**
   * Value.hasArrayElements() branch: the production code calls {@code
   * v.getArrayElement(i).asHostObject()} unconditionally — for a pure JS array like
   * {@code [1,2,3]} (primitive-coerced elements), this throws {@link ClassCastException}
   * because {@code Value.asHostObject()} requires an actual host-Object value, not a polyglot
   * number/string. Pins the observed crash shape so Track 22's hardening (probe via
   * {@code isHostObject() ?} before unwrap) is a visible change.
   *
   * <p>WHEN-FIXED: Track 22 — guard with {@code isHostObject()} check before
   * {@code asHostObject()}, or switch to {@code toString()}/{@code as(Object.class)} for the
   * non-host path. Already cross-referenced in Step 4's {@code PolyglotScriptExecutorTest}
   * (TB2 pin): both tests observe the same latent bug from different call sites.
   */
  @Test
  public void toResultSetPolyglotArrayOfPrimitivesThrowsClassCast() {
    final var arrVal = polyglotContext.eval("js", "[1, 2, 3]");
    assertTrue(arrVal.hasArrayElements());

    org.junit.Assert.assertThrows(
        "pure JS array of primitives → asHostObject() CCE — WHEN-FIXED: Track 22",
        ClassCastException.class,
        () -> transformer.toResultSet(session, arrVal));
  }

  /**
   * Array-of-host-objects: a Java {@code List<Date>} bound into JS presents as a polyglot
   * array whose elements ARE genuine host objects — {@code asHostObject()} succeeds on each.
   *
   * <p>Pins the observed (somewhat surprising) ResultSet shape: the array branch first
   * materializes each element via {@code toResult(db, hostObject)} into a {@link
   * java.util.ArrayList}, then assigns {@code value = array}. The subsequent
   * {@code defaultResultSet} wraps the WHOLE list as a singleton Result whose
   * {@code "value"} property is a {@code List<Result>} with one entry per original element.
   * Callers therefore see a one-element ResultSet, not an N-element ResultSet.
   *
   * <p>This is the intended-use case shape produced by {@code PolyglotScriptExecutor}; a
   * Track 22 refactor that streams the inner array elements as top-level Results would break
   * this test and force a re-pin.
   */
  @Test
  public void toResultSetPolyglotArrayOfHostObjectsProducesSingletonResultWithListProperty() {
    final var a = new java.util.Date(100L);
    final var b = new java.util.Date(200L);
    final java.util.List<java.util.Date> hostList = java.util.Arrays.asList(a, b);
    polyglotContext.getBindings("js").putMember("arr", hostList);

    final var arrVal = polyglotContext.eval("js", "arr");
    assertTrue("precondition: bound Java List exposes array elements", arrVal.hasArrayElements());

    try (var rs = transformer.toResultSet(session, arrVal)) {
      assertTrue(rs.hasNext());
      final var only = rs.next();
      final java.util.List<com.jetbrains.youtrackdb.internal.core.query.Result> inner =
          only.getProperty("value");
      assertNotNull("array materialized list must be set as the singleton's value", inner);
      assertEquals(2, inner.size());
      assertSame("inner Result 0 wraps the first Date", a, inner.get(0).getProperty("value"));
      assertSame("inner Result 1 wraps the second Date", b, inner.get(1).getProperty("value"));
      assertFalse("the outer ResultSet is a singleton (wrapping the list)", rs.hasNext());
    }
  }

  /**
   * Value fall-through branch (none of the isNull/hasArrayElements/isHostObject/isString/
   * isNumber checks match) → the Value itself is kept and wrapped in {@code defaultResultSet},
   * which then calls {@code toResult(db, value)} on {@code next()}. Since Value is not a
   * {@code PropertyTypeInternal.isSingleValueType}, {@code ResultInternal.setProperty("value",
   * fnVal)} throws a {@link com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException}.
   * Pins the observed "Invalid property value for Result" error so Track 22's hardening
   * (e.g., coerce Value via toString, or add Value.class transformer) is a visible change.
   *
   * <p>WHEN-FIXED: Track 22 — either register a transformer for {@code org.graalvm.polyglot.Value}
   * or coerce via {@code Value.toString()} before storing.
   */
  @Test
  public void toResultSetPolyglotFunctionFallThroughCrashesOnPropertyWrite() {
    final var fnVal = polyglotContext.eval("js", "(function () { return 1; })");
    assertFalse(fnVal.isNull());
    assertFalse(fnVal.hasArrayElements());
    assertFalse(fnVal.isHostObject());
    assertFalse(fnVal.isString());
    assertFalse(fnVal.isNumber());
    assertTrue("precondition: a JS function is executable (canExecute)", fnVal.canExecute());

    final var rs = transformer.toResultSet(session, fnVal);
    assertTrue(rs.hasNext());
    try {
      org.junit.Assert.assertThrows(
          "Value fall-through fails at property-write (not single-value-type)",
          com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException.class,
          rs::next);
    } finally {
      rs.close();
    }
  }

  // ===========================================================================
  // toResult — two dispatch branches.
  // ===========================================================================

  /**
   * Branch A: a transformer is registered for the value's class (Map.class by default). Pins
   * the delegation contract — the registered MapTransformer's output flows through
   * unchanged.
   */
  @Test
  public void toResultWithMapDelegatesToMapTransformer() {
    final Map<Object, Object> input = new LinkedHashMap<>();
    input.put("name", "foo");
    input.put("value", 42);

    final var result = transformer.toResult(session, input);
    assertNotNull(result);
    assertEquals("foo", result.getProperty("name"));
    assertEquals(Integer.valueOf(42), result.getProperty("value"));
  }

  /**
   * Branch B: no transformer registered → {@code defaultTransformer} wraps the value in a
   * single-property ResultInternal with key {@code "value"}.
   */
  @Test
  public void toResultWithUnregisteredTypeUsesDefaultTransformer() {
    final var result = transformer.toResult(session, 99L);
    assertNotNull(result);
    assertEquals(Long.valueOf(99L), result.getProperty("value"));
  }

  // ===========================================================================
  // getTransformer — lookup contract.
  // ===========================================================================

  /** getTransformer(null) must return null without throwing. Pins the null-guard. */
  @Test
  public void getTransformerWithNullClassReturnsNull() {
    assertNull(transformer.getTransformer(null));
  }

  /**
   * getTransformer scans the registry using {@code isAssignableFrom} — a subtype of a
   * registered class must match. Pins the subtype-matching contract: the default Map.class
   * registration is found via a LinkedHashMap.class lookup.
   */
  @Test
  public void getTransformerReturnsTransformerForSubtypeViaIsAssignableFrom() {
    final var t = transformer.getTransformer(LinkedHashMap.class);
    assertNotNull("Map.class transformer must match LinkedHashMap via isAssignableFrom", t);
  }

  // ===========================================================================
  // registerResultTransformer / registerResultSetTransformer — mutation contract.
  // ===========================================================================

  /**
   * Registering a new ResultTransformer for a specific class makes {@code toResult} dispatch to
   * it. Pins the late-registration contract: after-the-fact registration is picked up by the
   * next call.
   */
  @Test
  public void registerResultTransformerOverridesDefaultForRegisteredClass() {
    final ResultTransformer<String> stringTransformer =
        (db, value) -> {
          final var r = new ResultInternal(db);
          r.setProperty("custom", value);
          return r;
        };
    transformer.registerResultTransformer(String.class, stringTransformer);

    final var result = transformer.toResult(session, "abc");
    assertEquals(
        "late-registered transformer must override the default 'value' property path",
        "abc",
        result.getProperty("custom"));
    assertNull(
        "default 'value' property must NOT be set by the custom transformer",
        result.getProperty("value"));
  }

  /**
   * Registering a ResultSetTransformer for a specific class makes {@code toResultSet} dispatch
   * to it ahead of the defaultResultSet fallback. This is the symmetric test to
   * {@link #toResultSetRegisteredTransformerWinsOverDefault} but focused on the registry API
   * surface (mutation through the public setter).
   */
  @Test
  public void registerResultSetTransformerDispatchesToCustomTransformer() {
    final var marker =
        new IteratorResultSet(session, Arrays.<Object>asList("MARKER").iterator());
    transformer.registerResultSetTransformer(Integer.class, value -> marker);

    try (var rs = transformer.toResultSet(session, 10)) {
      assertSame(
          "registered Integer→ResultSet transformer must dispatch for a raw Integer input",
          marker,
          rs);
    }
  }

  /**
   * doesHandleResult must return {@code true} exactly when a registered transformer exists for
   * the value's class (or a supertype). Negative pin for non-registered types.
   */
  @Test
  public void doesHandleResultIsFalseForUnregisteredType() {
    assertFalse(transformer.doesHandleResult(Long.valueOf(1)));
    assertFalse(transformer.doesHandleResult(new Object()));
  }
}
