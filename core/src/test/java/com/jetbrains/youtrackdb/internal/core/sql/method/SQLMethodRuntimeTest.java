/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.sql.method;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.parser.BaseParser;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemVariable;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionRuntime;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodSize;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodTrim;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodRuntime} — the runtime-side wrapper around a {@link SQLMethod} that
 * handles parameter binding, parameter resolution against the current record/context, arity
 * validation, and delegation to {@link SQLMethod#execute(Object, Result, CommandContext, Object,
 * Object[])}.
 *
 * <p>The tests deliberately use the programmatic constructor
 * {@link SQLMethodRuntime#SQLMethodRuntime(SQLMethod)} + {@link SQLMethodRuntime#setParameters}
 * path: the parser-bound constructor ({@link SQLMethodRuntime#SQLMethodRuntime(
 * DatabaseSessionEmbedded, BaseParser, String)}) is exercised indirectly via full SQL-execution
 * integration tests — out of Track 7 Step 3's scope (see Step 3 scope note about the parser
 * path being deferred to Track 8).
 *
 * <p>Extends {@link DbTestBase} because {@link SQLMethodRuntime#setParameters} calls
 * {@link SQLHelper#parseValue(String, CommandContext, ...)} which calls
 * {@link CommandContext#getDatabaseSession()}, and because {@code iContext.getDatabaseSession()}
 * must return a valid session for the arity-check code path.
 *
 * <p>Covered branches / contracts (cross-referenced against the numbered parameter-resolver
 * switch inside {@link SQLMethodRuntime#execute}):
 *
 * <ul>
 *   <li>{@code iThis == null} early-return → result is {@code null} and the wrapped method is
 *       NOT invoked.</li>
 *   <li>{@code configuredParameters == null} — skipped resolver; method invoked with whatever
 *       {@code runtimeParameters} holds (null in this test).</li>
 *   <li>Resolver branches: {@link SQLFilterItemField}, nested {@link SQLMethodRuntime}, nested
 *       {@link SQLFunctionRuntime}, {@link SQLFilterItemVariable}, and String-starts-with-quote
 *       — each is exercised in isolation.</li>
 *   <li>{@code evaluateParameters() == false} — resolver is short-circuited; runtime params
 *       equal configured params verbatim.</li>
 *   <li>Arity validation: too-few / too-many / equal min=max message / unequal range message /
 *       {@code maxParams == 0} → arity check skipped.</li>
 *   <li>{@code setParameters}: null no-op / quote-stripped / bracket-prefixed-raw-passthrough /
 *       unparseable-kept-raw / numeric-coerced / {@code null}-stringified-to-Java-null /
 *       non-String-pass-through / {@code runtimeParameters} allocated to match
 *       {@code configuredParameters}.</li>
 *   <li>{@code getValue} delegates to {@code execute} and catches
 *       {@link RecordNotFoundException} → null.</li>
 *   <li>{@code getRoot} returns the wrapped method's name.</li>
 *   <li>{@code compareTo} delegates to the wrapped {@link SQLMethod#compareTo(SQLMethod)}.</li>
 * </ul>
 */
public class SQLMethodRuntimeTest extends DbTestBase {

  private BasicCommandContext ctx;

  @Before
  public void setUp() {
    ctx = new BasicCommandContext(session);
  }

  // ---------------------------------------------------------------------------
  // Constructors & simple getters
  // ---------------------------------------------------------------------------

  @Test
  public void constructorSQLMethodOnlySetsMethodField() {
    var m = new SQLMethodSize();
    var runtime = new SQLMethodRuntime(m);
    assertSame(m, runtime.getMethod());
    // Programmatic constructor leaves configuredParameters/runtimeParameters as NULL until
    // setParameters is called — this is the contract transformValue relies on.
    assertNull(runtime.getConfiguredParameters());
    assertNull(runtime.getRuntimeParameters());
  }

  @Test
  public void getRootReturnsWrappedMethodName() {
    // SQLMethodRuntime.getRoot is used by SQLFilterItemAbstract.asString to render the chain in
    // error messages — pin that it's the method's identifier, not a human-readable description.
    var runtime = new SQLMethodRuntime(new SQLMethodSize());
    assertEquals(SQLMethodSize.NAME, runtime.getRoot(session));
  }

  @Test
  public void compareToDelegatesToWrappedMethod() {
    // SQLMethodRuntime.compareTo is used for deterministic ordering of operator chains in plan
    // output. Delegating to the wrapped SQLMethod keeps the ordering stable across runtime
    // instances of the same logical method.
    var sizeRuntime = new SQLMethodRuntime(new SQLMethodSize());
    var trimRuntime = new SQLMethodRuntime(new SQLMethodTrim());
    assertTrue(sizeRuntime.compareTo(trimRuntime) < 0); // "size" < "trim"
    assertTrue(trimRuntime.compareTo(sizeRuntime) > 0);
    assertEquals(0, sizeRuntime.compareTo(new SQLMethodRuntime(new SQLMethodSize())));
  }

  // ---------------------------------------------------------------------------
  // setParameters — conversions and guards
  // ---------------------------------------------------------------------------

  @Test
  public void setParametersNullIsNoOp() {
    // The `if (iParameters != null)` guard early-returns without touching internal state.
    var runtime = new SQLMethodRuntime(new SQLMethodSize());
    runtime.setParameters(session, null, true);
    // configuredParameters/runtimeParameters remain null; no allocation happened.
    assertNull(runtime.getConfiguredParameters());
    assertNull(runtime.getRuntimeParameters());
  }

  @Test
  public void setParametersQuotedStringStripsQuotes() {
    // Single-quoted input is routed through SQLHelper.parseValue → IOUtils.getStringContent.
    // The outcome is the unquoted payload — the execute-side quote branch is documented
    // separately in executeWithQuotedStringInConfiguredParametersStripsQuotes.
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {"'abc'"}, true);
    assertArrayEquals(new Object[] {"abc"}, runtime.getConfiguredParameters());
    // runtimeParameters seeded with the same value because it's neither
    // SQLFilterItemField nor SQLMethodRuntime.
    assertArrayEquals(new Object[] {"abc"}, runtime.getRuntimeParameters());
  }

  @Test
  public void setParametersDoubleQuotedStringStripsQuotes() {
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {"\"hello\""}, true);
    assertArrayEquals(new Object[] {"hello"}, runtime.getConfiguredParameters());
  }

  @Test
  public void setParametersNumericStringParsedAsNumber() {
    // SQLHelper.parseValue promotes numeric literals — the String "7" becomes an Integer.
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {"7"}, true);
    assertEquals(7, runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersNullLiteralStringBecomesJavaNull() {
    // "null" (case-insensitive) → Java null, NOT the string "null". Pins the contrast with the
    // JSON-parsing world where "null" is a distinct token.
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {"null"}, true);
    assertNull(runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersTrueLiteralStringBecomesBooleanTrue() {
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {"true"}, true);
    assertEquals(Boolean.TRUE, runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersFalseLiteralStringBecomesBooleanFalse() {
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {"false"}, true);
    assertEquals(Boolean.FALSE, runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersBracketPrefixedStringKeptRaw() {
    // `!iParameters[i].toString().startsWith("[")` guard: bracket-starting strings are NEVER
    // routed through SQLHelper.parseValue. They are expected to be resolved by the method
    // itself (e.g., collection accessors). Pin the raw pass-through so a regression that drops
    // the guard — causing parseValue to recursively walk "[a,b]" — is caught.
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {"[a,b]"}, true);
    assertEquals("[a,b]", runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersUnparseableIdentifierBecomesFilterItemField() {
    // An unquoted identifier that isn't recognized as a literal/number/RID/boolean/variable is
    // treated as a FIELD reference by SQLHelper.parseValue — it returns a freshly constructed
    // SQLFilterItemField so execute() can resolve it against the current record. Pinning the
    // actual conversion (not a "kept raw" assumption) guards against a silent drop to raw
    // string, which would cause the resolver branch to miss the field-lookup entirely.
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {"someIdentifier"}, true);
    var converted = runtime.getConfiguredParameters()[0];
    assertTrue(
        "expected unparseable identifier to be promoted to SQLFilterItemField, got: "
            + (converted == null ? "null" : converted.getClass().getName()),
        converted instanceof SQLFilterItemField);
  }

  @Test
  public void setParametersNullElementStoredAsNull() {
    // `iParameters[i] == null` path stores null — regression guard for a silent drop to empty
    // string or "null".
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {null}, true);
    assertNull(runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersNonStringPassThrough() {
    // A non-String param is stored as-is (not routed through SQLHelper.parseValue). This pins
    // that already-resolved literals (Integer, Long, etc.) survive round-trip unchanged.
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {42L}, true);
    assertEquals(42L, runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersAllocatesRuntimeParametersMatchingConfigured() {
    // runtimeParameters is always sized to configuredParameters.length even when every slot is
    // a "live" type (SQLFilterItemField / SQLMethodRuntime) that ends up null-seeded.
    var fld = new SQLFilterItemField(session, "f", null);
    var runtime = new SQLMethodRuntime(new SQLMethodTrim());
    runtime.setParameters(session, new Object[] {fld}, true);
    var runtimeParams = runtime.getRuntimeParameters();
    assertNotNull(runtimeParams);
    assertEquals(1, runtimeParams.length);
    // SQLFilterItemField slot is not seeded in setParameters — execute() resolves it.
    assertNull(runtimeParams[0]);
  }

  // ---------------------------------------------------------------------------
  // execute — early-return and null-configuredParameters branches
  // ---------------------------------------------------------------------------

  @Test
  public void executeWithIThisNullReturnsNull() {
    // The `if (iThis == null) return null` guard must fire BEFORE any resolver or arity check.
    var m = new SQLMethodSize();
    var runtime = new SQLMethodRuntime(m);
    assertNull(runtime.execute(null, null, null, ctx));
  }

  @Test
  public void executeWithNullConfiguredParametersSkipsResolverAndCallsMethod() {
    // Programmatic path — no setParameters invoked. configuredParameters remains null and the
    // method sees a null iParams array. SQLMethodSize tolerates null params (it ignores them).
    var runtime = new SQLMethodRuntime(new SQLMethodSize());
    // Size of a plain String "abc" is 0 (per SQLMethodSize: non-Identifiable non-MultiValue →
    // MultiValue.getSize returns 0). Pin reachability of the no-arity-check branch.
    var out = runtime.execute("abc", null, null, ctx);
    assertEquals(0, out);
  }

  // ---------------------------------------------------------------------------
  // execute — resolver branches
  // ---------------------------------------------------------------------------

  @Test
  public void executeWithSQLFilterItemFieldParameterResolvedFromRecord() {
    // setParameters stores the filter item in configuredParameters; execute() calls its
    // getValue against the record. We verify by having the "trim" method return our own
    // mock-method-visible param — but since SQLMethodTrim is a real implementation, easier to
    // use a ProbeMethod that just echoes back the first iParam.
    var probe = new ProbeMethod("probe");
    var runtime = new SQLMethodRuntime(probe);
    var fld = new SQLFilterItemField(session, "f", null);
    runtime.setParameters(session, new Object[] {fld}, true);

    var record = new ResultInternal(session);
    record.setProperty("f", "resolved-value");
    probe.returnValue = "method-return";
    var out = runtime.execute("iThisDoesNotMatter", record, null, ctx);

    // Method return is pass-through from the wrapped method — pin so a regression that adds a
    // post-processing step (e.g. unexpected toString()) surfaces here.
    assertEquals("method-return", out);
    // Runtime parameters reflect the resolver output — pin to detect a regression where the
    // filter item's getValue bypass stashed the filter item itself instead of the value.
    assertArrayEquals(new Object[] {"resolved-value"}, probe.lastParams);
  }

  @Test
  public void executeWithNestedSQLMethodRuntimeParameterIsInvoked() {
    // When a param is itself an SQLMethodRuntime, execute() recurses into the nested method's
    // execute. Build: outer(iThis) where iThis="outer-this" and inner.execute(iThis, ...) →
    // returns "inner-out". Outer.iParams must contain "inner-out".
    var inner = new ProbeMethod("inner");
    inner.returnValue = "inner-out";
    var innerRuntime = new SQLMethodRuntime(inner);

    var outer = new ProbeMethod("outer");
    var outerRuntime = new SQLMethodRuntime(outer);
    outerRuntime.setParameters(session, new Object[] {innerRuntime}, true);

    outerRuntime.execute("outer-this", null, null, ctx);
    assertArrayEquals(new Object[] {"inner-out"}, outer.lastParams);
    // Inner probe must have been called with the outer's iThis — the nested-SQLMethodRuntime
    // branch propagates iThis downward.
    assertEquals("outer-this", inner.lastIThis);
  }

  @Test
  public void executeWithNestedSQLFunctionRuntimeParameterIsInvoked() {
    // Nested SQLFunctionRuntime branch. Use a RecordingFunction to observe the call and return
    // a marker value. The outer probe must receive that marker as its param.
    var fn = new RecordingFunction("fn");
    fn.returnValue = "fn-out";
    var fnRuntime = new SQLFunctionRuntime(fn);
    // SQLFunctionRuntime.execute requires configuredParameters to be non-null (it iterates over
    // it). Seed an empty array.
    fnRuntime.configuredParameters = new Object[0];
    fnRuntime.runtimeParameters = new Object[0];

    var outer = new ProbeMethod("outer");
    var outerRuntime = new SQLMethodRuntime(outer);
    outerRuntime.setParameters(session, new Object[] {fnRuntime}, true);

    outerRuntime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {"fn-out"}, outer.lastParams);
  }

  @Test
  public void executeWithSQLFilterItemVariableParameterResolvesFromContext() {
    // SQLFilterItemVariable delegates to iContext.getVariable(name). Build one via the public
    // parser-based ctor with a no-op BaseParser; the super-ctor's smartSplit tolerates a simple
    // identifier after the "$" is stripped.
    var variable =
        new SQLFilterItemVariable(session, new StubParser("$v"), "$v");
    ctx.setVariable("v", "ctx-value");

    var probe = new ProbeMethod("probe");
    var runtime = new SQLMethodRuntime(probe);
    runtime.setParameters(session, new Object[] {variable}, true);

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {"ctx-value"}, probe.lastParams);
  }

  @Test
  public void executeWithQuotedStringInConfiguredParametersStripsQuotes() {
    // The String-branch in execute() fires on literal quoted strings that bypass setParameters.
    // Because setParameters would normally strip quotes via SQLHelper.parseValue, we ASSIGN
    // configuredParameters directly to exercise the rarely-reached runtime-side branch — this
    // is legal because configuredParameters is a public field (part of the parser contract).
    var probe = new ProbeMethod("probe", 1, 1);
    var runtime = new SQLMethodRuntime(probe);
    runtime.configuredParameters = new Object[] {"\"quoted-payload\""};
    runtime.runtimeParameters = new Object[1]; // matching size, content replaced by execute()

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {"quoted-payload"}, probe.lastParams);
  }

  @Test
  public void executeWithSingleQuoteStringInConfiguredParametersStripsQuotes() {
    // Second quote-type branch: starts with `'`.
    var probe = new ProbeMethod("probe", 1, 1);
    var runtime = new SQLMethodRuntime(probe);
    runtime.configuredParameters = new Object[] {"'single'"};
    runtime.runtimeParameters = new Object[1];

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {"single"}, probe.lastParams);
  }

  @Test
  public void executeSkipsResolverWhenEvaluateParametersFalse() {
    // ProbeMethod has configurable evaluateParameters. When false, execute() skips the entire
    // resolver switch — configured params flow into runtime params unchanged. Pin that a
    // SQLFilterItemField in the slot is NOT resolved.
    var probe = new ProbeMethod("probe", 1, 1);
    probe.evaluateParameters = false;
    var runtime = new SQLMethodRuntime(probe);
    var fld = new SQLFilterItemField(session, "f", null);
    runtime.setParameters(session, new Object[] {fld}, true);

    var record = new ResultInternal(session);
    record.setProperty("f", "ignored-value");
    runtime.execute("iThis", record, null, ctx);

    // The filter item must NOT have been resolved. Probe receives the SQLFilterItemField
    // instance itself as the iParam, NOT the property value.
    assertNotNull(probe.lastParams);
    assertEquals(1, probe.lastParams.length);
    assertSame(fld, probe.lastParams[0]);
  }

  // ---------------------------------------------------------------------------
  // Arity validation
  // ---------------------------------------------------------------------------

  @Test
  public void executeTooFewParametersThrowsRangeFormattedMessage() {
    // Use numeric (non-String) params to bypass SQLHelper.parseValue's "promote unparseable
    // identifiers to SQLFilterItemField" behaviour — unresolved field items would fail with a
    // different error ("cannot be resolved because current record is NULL") and mask the arity
    // check. Numbers pass through setParameters as-is.
    var probe = new ProbeMethod("multi", 2, 3);
    var runtime = new SQLMethodRuntime(probe);
    runtime.setParameters(session, new Object[] {1}, true);

    try {
      runtime.execute("iThis", null, null, ctx);
      fail("expected CommandExecutionException for insufficient arity");
    } catch (CommandExecutionException e) {
      var msg = e.getMessage();
      assertNotNull(msg);
      // The message template: "Syntax error: function 'NAME' needs MIN-MAX argument(s) while
      // has been received COUNT". Pin all three pieces: name, range, observed count.
      assertTrue("message should name the method: " + msg, msg.contains("'multi'"));
      assertTrue("message should show range 2-3: " + msg, msg.contains("2-3"));
      assertTrue("message should report received count 1: " + msg, msg.contains("received 1"));
    }
  }

  @Test
  public void executeTooManyParametersThrowsRangeFormattedMessage() {
    var probe = new ProbeMethod("multi", 1, 2);
    var runtime = new SQLMethodRuntime(probe);
    runtime.setParameters(session, new Object[] {1, 2, 3}, true);

    try {
      runtime.execute("iThis", null, null, ctx);
      fail("expected CommandExecutionException for excess arity");
    } catch (CommandExecutionException e) {
      var msg = e.getMessage();
      assertTrue("should name: " + msg, msg.contains("'multi'"));
      assertTrue("range 1-2: " + msg, msg.contains("1-2"));
      assertTrue("received 3: " + msg, msg.contains("received 3"));
    }
  }

  @Test
  public void executeEqualMinMaxUsesSingleNumberInMessage() {
    // When min == max, the message formats as a single number — not "N-N". Pins the
    // `params = "" + method.getMinParams()` branch.
    var probe = new ProbeMethod("exact", 1, 1);
    var runtime = new SQLMethodRuntime(probe);
    runtime.setParameters(session, new Object[0], true);

    try {
      runtime.execute("iThis", null, null, ctx);
      fail("expected CommandExecutionException");
    } catch (CommandExecutionException e) {
      var msg = e.getMessage();
      assertTrue("single-number arity: " + msg, msg.contains("needs 1 argument"));
      assertFalse("must NOT show 1-1 range: " + msg, msg.contains("1-1"));
    }
  }

  @Test
  public void executeMaxParamsZeroSkipsArityCheck() {
    // The arity-check outer guard: `if (method.getMaxParams(session) == -1 ||
    // method.getMaxParams(session) > 0)`. When maxParams == 0 (no args allowed), the whole
    // check is skipped — extra runtime params silently pass through to method.execute. Pin the
    // branch. A production fix that special-cases zero would flip this test.
    //
    // Use numeric params to avoid the SQLFilterItemField promotion path for unparseable
    // identifiers — we want the arity branch to be the only thing under test.
    var probe = new ProbeMethod("noArgs", 0, 0);
    var runtime = new SQLMethodRuntime(probe);
    runtime.setParameters(session, new Object[] {1, 2}, true);

    // No exception is raised; extra params are forwarded to the method.
    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {1, 2}, probe.lastParams);
  }

  @Test
  public void executeMaxParamsNegativeOneSkipsUpperBoundCheck() {
    // maxParams == -1 is the "unlimited" sentinel: lower bound is still enforced, upper bound
    // is ignored. Pin both halves by: (a) failing on too few, (b) succeeding on an arbitrarily
    // large count. Numeric params again to avoid field-promotion.
    var probe = new ProbeMethod("variadic", 1, -1);
    var runtime = new SQLMethodRuntime(probe);

    // Too few — still throws (min=1).
    runtime.setParameters(session, new Object[0], true);
    try {
      runtime.execute("iThis", null, null, ctx);
      fail("expected CommandExecutionException — min=1 still enforced");
    } catch (CommandExecutionException expected) {
      assertTrue(expected.getMessage().contains("'variadic'"));
    }

    // Many — passes (max=-1, unbounded). Use a fresh runtime because the first setParameters
    // seeded an empty configured array.
    var manyRuntime = new SQLMethodRuntime(probe);
    manyRuntime.setParameters(session, new Object[] {1, 2, 3, 4, 5, 6, 7}, true);
    manyRuntime.execute("iThis", null, null, ctx);
    assertEquals(7, probe.lastParams.length);
  }

  // ---------------------------------------------------------------------------
  // getValue — delegation and RecordNotFoundException catch
  // ---------------------------------------------------------------------------

  @Test
  public void getValueDelegatesToExecute() {
    // getValue(record, result, ctx) calls execute(record, record, null, ctx) — record serves as
    // both iThis and iCurrentRecord. Use a ProbeMethod so we can observe the forwarding.
    var probe = new ProbeMethod("probe");
    probe.returnValue = "value-out";
    var runtime = new SQLMethodRuntime(probe);

    var record = new ResultInternal(session);
    record.setProperty("anything", "ignored");
    var out = runtime.getValue(record, null, ctx);

    assertEquals("value-out", out);
    assertSame("iThis must be the record", record, probe.lastIThis);
  }

  @Test
  public void getValueCatchesRecordNotFoundExceptionAndReturnsNull() {
    // When the wrapped method raises RecordNotFoundException (common for SQLMethodField on a
    // dangling link), getValue returns null instead of propagating. This is the one exception
    // type SQLMethodRuntime swallows — others must propagate untouched.
    var throwingProbe =
        new ProbeMethod("rnf") {
          @Override
          public Object execute(
              Object iThis,
              Result iCurrentRecord,
              CommandContext iContext,
              Object ioResult,
              Object[] iParams) {
            // RecordNotFoundException's public ctors all require a RID argument; use the
            // message-accepting ctor with a null RID to produce a throwable that still carries
            // a descriptive message but does not require an actual record.
            throw new RecordNotFoundException((String) null, null, "simulated RNFE");
          }
        };
    var runtime = new SQLMethodRuntime(throwingProbe);
    var record = new ResultInternal(session);
    assertNull(runtime.getValue(record, null, ctx));
  }

  @Test
  public void getValuePropagatesNonRecordNotFoundExceptions() {
    // Only RecordNotFoundException is caught by getValue; other exceptions must propagate. Pin
    // the selective catch semantics so a broad catch-all regression is immediately visible.
    var throwingProbe =
        new ProbeMethod("boom") {
          @Override
          public Object execute(
              Object iThis,
              Result iCurrentRecord,
              CommandContext iContext,
              Object ioResult,
              Object[] iParams) {
            throw new IllegalStateException("should propagate");
          }
        };
    var runtime = new SQLMethodRuntime(throwingProbe);
    try {
      runtime.getValue(new ResultInternal(session), null, ctx);
      fail("expected IllegalStateException to propagate");
    } catch (IllegalStateException expected) {
      assertEquals("should propagate", expected.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Helper classes
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link SQLMethod} that records its inputs and returns a configurable value. Its
   * arity (min/max) and {@code evaluateParameters()} are configurable per test so each branch
   * of {@link SQLMethodRuntime#execute} can be pinned in isolation without relying on any
   * production method's accidental parameter count.
   */
  private static class ProbeMethod implements SQLMethod {

    final String name;
    final int minParams;
    final int maxParams;

    Object returnValue;
    boolean evaluateParameters = true;

    Object lastIThis;
    Object[] lastParams;

    ProbeMethod(String name) {
      this(name, 0, 0);
    }

    ProbeMethod(String name, int minParams, int maxParams) {
      this.name = name;
      this.minParams = minParams;
      this.maxParams = maxParams;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getSyntax() {
      return name + "()";
    }

    @Override
    public int getMinParams() {
      return minParams;
    }

    @Override
    public int getMaxParams(DatabaseSessionEmbedded session) {
      return maxParams;
    }

    @Override
    public Object execute(
        Object iThis,
        Result iCurrentRecord,
        CommandContext iContext,
        Object ioResult,
        Object[] iParams) {
      this.lastIThis = iThis;
      this.lastParams = iParams;
      return returnValue;
    }

    @Override
    public boolean evaluateParameters() {
      return evaluateParameters;
    }

    @Override
    public int compareTo(SQLMethod o) {
      return name.compareTo(o.getName());
    }
  }

  /**
   * Minimal {@link SQLFunction} used as the payload for the nested {@link SQLFunctionRuntime}
   * resolver branch. We only need it to return a configurable value and tolerate any inputs.
   */
  private static final class RecordingFunction implements SQLFunction {

    final String name;
    Object returnValue;

    RecordingFunction(String name) {
      this.name = name;
    }

    @Override
    public Object execute(
        Object iThis,
        Result iCurrentRecord,
        Object iCurrentResult,
        Object[] iParams,
        CommandContext iContext) {
      return returnValue;
    }

    @Override
    public void config(Object[] configuredParameters) {
      // no-op
    }

    @Override
    public boolean aggregateResults() {
      return false;
    }

    @Override
    public boolean filterResult() {
      return false;
    }

    @Override
    public String getName(DatabaseSessionEmbedded session) {
      return name;
    }

    @Override
    public int getMinParams() {
      return 0;
    }

    @Override
    public int getMaxParams(DatabaseSessionEmbedded session) {
      return 0;
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return name + "()";
    }

    @Override
    public Object getResult() {
      return returnValue;
    }

    @Override
    public void setResult(Object iResult) {
      this.returnValue = iResult;
    }
  }

  /**
   * Concrete {@link BaseParser} seeded with a fixed {@code parserText}. Used to satisfy the
   * {@link SQLFilterItemVariable} super-constructor's need for a parser reference even when the
   * input text is a pre-tokenized identifier that requires no real parsing. The smartSplit call
   * inside {@link com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemAbstract}
   * consumes only the {@code iText} argument — {@code parserText} is used solely for
   * error-reporting paths we don't trigger in these tests.
   */
  private static final class StubParser extends BaseParser {

    StubParser(String text) {
      this.parserText = text;
      this.parserTextUpperCase = text.toUpperCase();
    }

    @Override
    protected void throwSyntaxErrorException(String dbName, String iText) {
      // Test-only stub: never called on the happy paths exercised here. Throwing is preferable
      // to a silent no-op — if a future refactor starts depending on this hook during a
      // SQLFilterItemVariable construction, the failure will be loud.
      throw new IllegalStateException(
          "StubParser does not implement syntax-error reporting (dbName="
              + dbName
              + ", text="
              + iText
              + ")");
    }
  }
}
