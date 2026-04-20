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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionRuntime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@link RuntimeResult} class — a small container used by SELECT projection evaluation
 * to apply index-derived values and evaluate projection expressions into a {@link ResultInternal}.
 *
 * <p>The class has two main responsibilities:
 *
 * <ul>
 *   <li>{@link RuntimeResult#applyValue(String, Object)} and {@link
 *       RuntimeResult#getResult(DatabaseSessionEmbedded)} — instance methods that seed a field
 *       and materialise the accumulated result.</li>
 *   <li>{@link RuntimeResult#getResult(DatabaseSessionEmbedded, ResultInternal, Map)} — the static
 *       helper that resolves {@link SQLFunctionRuntime}-valued projections. Covers: null-value
 *       short-circuit, already-set-property skip (index optimisation preserves existing values),
 *       {@code filterResult()} exclusion of empty records, and the return of the untouched input
 *       when no projection mutations are needed.</li>
 * </ul>
 *
 * <p>Uses {@link DbTestBase} because {@link ResultInternal} construction requires a
 * {@link DatabaseSessionEmbedded}.
 */
public class RuntimeResultTest extends DbTestBase {

  private BasicCommandContext ctx;

  @Before
  public void setUp() {
    ctx = new BasicCommandContext(session);
  }

  // ---------------------------------------------------------------------------
  // Instance constructor + getFieldValue
  // ---------------------------------------------------------------------------

  @Test
  public void constructorStoresFieldValueVerbatim() {
    // The constructor keeps the iFieldValue reference — getFieldValue returns it unchanged.
    // iProgressive is currently unused (the RuntimeResult implementation does not track it),
    // so we pass any int.
    var fieldVal = "any-marker";
    var rr = new RuntimeResult(fieldVal, Map.of(), 42, ctx);
    assertSame(fieldVal, rr.getFieldValue());
  }

  @Test
  public void constructorAcceptsNullFieldValue() {
    // Null field values are permissible — pin that the constructor and getFieldValue tolerate
    // null without NPE.
    var rr = new RuntimeResult(null, Map.of(), 0, ctx);
    assertNull(rr.getFieldValue());
  }

  // ---------------------------------------------------------------------------
  // applyValue + instance getResult
  // ---------------------------------------------------------------------------

  @Test
  public void applyValueSetsPropertyOnUnderlyingResult() {
    // applyValue delegates to value.setProperty — the property is visible on the materialised
    // result. The instance getResult routes through the static getResult with empty projections,
    // so no mutation happens.
    Map<String, Object> projections = new HashMap<>();
    var rr = new RuntimeResult("field-val", projections, 0, ctx);
    rr.applyValue("a", 1);
    rr.applyValue("b", "two");
    var out = rr.getResult(session);
    assertNotNull(out);
    assertEquals(1, (int) out.getProperty("a"));
    assertEquals("two", out.getProperty("b"));
  }

  @Test
  public void applyValueOverwritesPreviousValue() {
    // applyValue is a plain setProperty — a second call with the same field name replaces the
    // first value. Pin this because callers (index-step code paths) rely on last-writer-wins.
    var rr = new RuntimeResult(null, Map.of(), 0, ctx);
    rr.applyValue("a", 1);
    rr.applyValue("a", 2);
    var out = rr.getResult(session);
    assertEquals(2, (int) out.getProperty("a"));
  }

  // ---------------------------------------------------------------------------
  // Static getResult — input null → null
  // ---------------------------------------------------------------------------

  @Test
  public void staticGetResultWithNullInputReturnsNull() {
    // iValue == null short-circuits the entire method. Projections are not consulted at all.
    assertNull(RuntimeResult.getResult(session, null, Map.of("x", new Object())));
  }

  // ---------------------------------------------------------------------------
  // Static getResult — projection already set on iValue → skipped
  // ---------------------------------------------------------------------------

  @Test
  public void staticGetResultPreservesAlreadySetProperty() {
    // If the projection key is ALREADY present on iValue (e.g. set manually by an index step),
    // the SQLFunctionRuntime for that key must NOT run. Pin by using a function whose execution
    // would set a distinct marker — the marker must be absent.
    var iv = new ResultInternal(session);
    iv.setProperty("x", "manual-value");
    var recording = new RecordingFunction("recording");
    recording.result = "should-not-be-written";
    Map<String, Object> proj = new LinkedHashMap<>();
    proj.put("x", new SQLFunctionRuntime(recording));
    var out = RuntimeResult.getResult(session, iv, proj);
    assertNotNull(out);
    assertEquals("manual-value", out.getProperty("x"));
    assertFalse("recording.getResult must not be invoked when property already exists",
        recording.getResultCalled);
  }

  // ---------------------------------------------------------------------------
  // Static getResult — SQLFunctionRuntime evaluates to non-null → property is set
  // ---------------------------------------------------------------------------

  @Test
  public void staticGetResultAppliesFunctionRuntimeValue() {
    // The projection is missing on iValue and the value is an SQLFunctionRuntime. The function's
    // getResult is called and the return value is stored on iValue.
    var iv = new ResultInternal(session);
    var recording = new RecordingFunction("rec");
    recording.result = 99;
    Map<String, Object> proj = new LinkedHashMap<>();
    proj.put("computed", new SQLFunctionRuntime(recording));
    var out = RuntimeResult.getResult(session, iv, proj);
    assertNotNull(out);
    assertEquals(99, (int) out.getProperty("computed"));
    assertTrue("recording.getResult must be invoked when property is missing",
        recording.getResultCalled);
  }

  // ---------------------------------------------------------------------------
  // Static getResult — null function result skips the setProperty call
  // ---------------------------------------------------------------------------

  @Test
  public void staticGetResultSkipsSetPropertyWhenFunctionResultIsNull() {
    // A null function result does not wipe/set the property — the field stays unset.
    var iv = new ResultInternal(session);
    var recording = new RecordingFunction("rec");
    recording.result = null;
    Map<String, Object> proj = new LinkedHashMap<>();
    proj.put("computed", new SQLFunctionRuntime(recording));
    var out = RuntimeResult.getResult(session, iv, proj);
    assertNotNull(out);
    assertFalse(out.getPropertyNames().contains("computed"));
  }

  // ---------------------------------------------------------------------------
  // Static getResult — non-SQLFunctionRuntime projection values are ignored
  // ---------------------------------------------------------------------------

  @Test
  public void staticGetResultIgnoresNonFunctionProjectionValues() {
    // A projection whose value is NOT an SQLFunctionRuntime (e.g. a literal placeholder) does
    // not cause any mutation — iValue is returned unchanged.
    var iv = new ResultInternal(session);
    Map<String, Object> proj = new LinkedHashMap<>();
    proj.put("literal", "plain-string"); // not an SQLFunctionRuntime
    var out = RuntimeResult.getResult(session, iv, proj);
    assertSame(iv, out);
    assertFalse(out.getPropertyNames().contains("literal"));
  }

  // ---------------------------------------------------------------------------
  // Static getResult — filterResult + empty properties returns null
  // ---------------------------------------------------------------------------

  @Test
  public void staticGetResultReturnsNullWhenFilterResultAndEmpty() {
    // The canExcludeResult flag becomes true when ANY SQLFunctionRuntime.filterResult() is true.
    // If the final record has NO properties, the method returns null instead of an empty Result.
    var iv = new ResultInternal(session);
    var filtering = new RecordingFunction("filt");
    filtering.result = null; // do not actually set a property
    filtering.filterResult = true; // but do flag this as a filtering projection
    Map<String, Object> proj = new LinkedHashMap<>();
    proj.put("f", new SQLFunctionRuntime(filtering));
    var out = RuntimeResult.getResult(session, iv, proj);
    assertNull(out);
  }

  @Test
  public void staticGetResultReturnsValueWhenFilterResultButNonEmpty() {
    // canExcludeResult is true but iValue has at least one property from a prior step — the
    // method must NOT return null in that case; iValue survives.
    var iv = new ResultInternal(session);
    iv.setProperty("prior", 1);
    var filtering = new RecordingFunction("filt");
    filtering.result = null; // does not add a property
    filtering.filterResult = true;
    Map<String, Object> proj = new LinkedHashMap<>();
    proj.put("f", new SQLFunctionRuntime(filtering));
    var out = RuntimeResult.getResult(session, iv, proj);
    assertSame(iv, out);
  }

  @Test
  public void staticGetResultReturnsValueWhenNotFiltering() {
    // Neither SQLFunctionRuntime has filterResult() → canExcludeResult stays false → even with
    // an empty property set, iValue is returned unchanged.
    var iv = new ResultInternal(session);
    var nonFiltering = new RecordingFunction("nf");
    nonFiltering.result = null;
    nonFiltering.filterResult = false;
    Map<String, Object> proj = new LinkedHashMap<>();
    proj.put("f", new SQLFunctionRuntime(nonFiltering));
    var out = RuntimeResult.getResult(session, iv, proj);
    assertSame(iv, out);
  }

  // ---------------------------------------------------------------------------
  // Static getResult — empty projections is a no-op
  // ---------------------------------------------------------------------------

  @Test
  public void staticGetResultWithEmptyProjectionsIsNoOp() {
    // Zero projections → the loop body never executes → iValue is returned unchanged and
    // untouched.
    var iv = new ResultInternal(session);
    iv.setProperty("field", "untouched");
    var out = RuntimeResult.getResult(session, iv, Map.of());
    assertSame(iv, out);
    assertEquals("untouched", out.getProperty("field"));
  }

  // ---------------------------------------------------------------------------
  // Test helpers
  // ---------------------------------------------------------------------------

  /**
   * A stub {@link com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction} that records
   * invocations of {@link #getResult()} and lets the test control the return value and
   * {@link #filterResult()} flag. Parameter binding is a no-op — the function never produces a
   * value through {@code execute} because {@code RuntimeResult.getResult} only consults
   * {@code getResult()} / {@code filterResult()} on the SQLFunctionRuntime.
   */
  private static final class RecordingFunction extends SQLFunctionAbstract {

    Object result;
    boolean filterResult = false;
    boolean getResultCalled = false;

    RecordingFunction(String name) {
      super(name, 0, -1);
    }

    @Override
    public Object execute(
        Object iThis, Result iCurrentRecord, Object iCurrentResult, Object[] iParams,
        CommandContext iContext) {
      // Unused — RuntimeResult.getResult reads from getResult(), not execute.
      return null;
    }

    @Override
    public Object getResult() {
      getResultCalled = true;
      return result;
    }

    @Override
    public boolean filterResult() {
      return filterResult;
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return name + "()";
    }
  }
}
