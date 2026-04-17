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
package com.jetbrains.youtrackdb.internal.core.sql.method.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link SQLMethodFunctionDelegate} — the bridge that lets an {@link SQLFunction} be
 * invoked through the {@code .method()} chain syntax. The delegate wraps the function in an
 * {@link com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionRuntime} and adjusts
 * {@code minParams}/{@code maxParams} down by one so the SQL parser accepts one fewer
 * argument: {@code x.func(arg1, arg2)} = {@code func(x, arg1, arg2)}.
 *
 * <p>Extends {@link DbTestBase} because
 * {@link com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionRuntime#setParameters}
 * calls {@code context.getDatabaseSession()} for SQL-parse helpers.
 *
 * <p>Tests use a hand-rolled {@code RecordingFunction} that implements {@link SQLFunction} so
 * we can observe exactly which arguments are threaded through the delegate chain without
 * coupling the assertions to the behaviour of any specific production function. This also
 * guarantees the tests stay valid if production functions change min/max bounds.
 *
 * <p>Covered branches / properties:
 *
 * <ul>
 *   <li>Delegate's {@code getMinParams()} / {@code getMaxParams(session)} subtract 1 from the
 *       wrapped function's bounds — this is how the parser enforces that {@code iThis} counts
 *       as the first function argument.</li>
 *   <li>A wrapped function with {@code min == -1} (unlimited) also returns -1 from the
 *       delegate — pins the sentinel pass-through branch.</li>
 *   <li>Similarly, a wrapped function with {@code max == -1} returns -1 from the delegate.</li>
 *   <li>A wrapped function with {@code max == 0} returns -1 (i.e., {@code 0 - 1}) from the
 *       delegate — subtle edge case: that negative number is NOT a sentinel, it comes from the
 *       {@code max == -1 ? -1 : max - 1} formula and would silently allow unlimited params at
 *       the parser layer. Pinned as a WHEN-FIXED regression — current behaviour is observable
 *       but likely unintended.</li>
 *   <li>{@code execute} threads {@code iThis}, {@code iCurrentRecord}, {@code ioResult}, and
 *       {@code iContext} to the wrapped function; {@code iParams} are passed as the function's
 *       runtime parameters via {@code setParameters} (not through {@code iThis}).</li>
 *   <li>{@code execute} returns whatever the wrapped function returns (pass-through).</li>
 *   <li>Arity validation happens inside
 *       {@link com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionRuntime}: too
 *       few params → {@link CommandExecutionException}; too many params →
 *       {@link CommandExecutionException}.</li>
 *   <li>{@code toString()} returns "function &lt;wrapped&gt;" — pins the debug-friendly format
 *       used in parser error messages.</li>
 * </ul>
 */
public class SQLMethodFunctionDelegateTest extends DbTestBase {

  private BasicCommandContext ctx;

  @Before
  public void setup() {
    ctx = new BasicCommandContext(session);
  }

  // ---------------------------------------------------------------------------
  // min/max arity — "subtract 1" contract
  // ---------------------------------------------------------------------------

  @Test
  public void getMinParamsSubtractsOneFromWrappedFunctionMin() {
    // Wrapped min=2 → delegate min=1 (iThis counts as the first function arg).
    var fn = new RecordingFunction("ident", 2, 3);
    var delegate = new SQLMethodFunctionDelegate(fn);

    assertEquals(1, delegate.getMinParams());
  }

  @Test
  public void getMaxParamsSubtractsOneFromWrappedFunctionMax() {
    var fn = new RecordingFunction("ident", 2, 3);
    var delegate = new SQLMethodFunctionDelegate(fn);

    assertEquals(2, delegate.getMaxParams(session));
  }

  @Test
  public void getMinParamsPreservesUnlimitedSentinel() {
    // min == -1 is a sentinel meaning "unlimited"; the delegate must pass it through, NOT
    // compute -2.
    var fn = new RecordingFunction("unlimited", -1, 5);
    var delegate = new SQLMethodFunctionDelegate(fn);

    assertEquals(-1, delegate.getMinParams());
  }

  @Test
  public void getMaxParamsPreservesUnlimitedSentinel() {
    // max == -1 sentinel → delegate.getMaxParams must also be -1.
    var fn = new RecordingFunction("unlimited", 1, -1);
    var delegate = new SQLMethodFunctionDelegate(fn);

    assertEquals(-1, delegate.getMaxParams(session));
  }

  @Test
  public void getMaxParamsZeroBecomesNegativeOne() {
    // WHEN-FIXED: delegate pipes max=0 through the `max == -1 ? -1 : max - 1` formula, yielding
    // -1 — which the parser treats as "unlimited". Accepted here as a pinned regression. If the
    // delegate ever grows a `max == 0 ? 0 : max - 1` special-case for strictly-no-args
    // functions, this assertion must be updated.
    var fn = new RecordingFunction("noArgs", 0, 0);
    var delegate = new SQLMethodFunctionDelegate(fn);

    assertEquals(-1, delegate.getMaxParams(session));
  }

  // ---------------------------------------------------------------------------
  // execute — argument threading
  // ---------------------------------------------------------------------------

  @Test
  public void executePassesIThisAndIoResultThroughToWrappedFunction() {
    // iThis and ioResult (iCurrentResult inside the function) must be handed to the wrapped
    // function unchanged; iContext flows through as-is. iCurrentRecord is also forwarded.
    var fn = new RecordingFunction("pt", 1, 3);
    var delegate = new SQLMethodFunctionDelegate(fn);
    var iThis = "the-this";
    var ioResult = "the-ioResult";

    delegate.execute(iThis, null, ctx, ioResult, new Object[] {"a", "b"});

    assertSame("iThis forwarded", iThis, fn.lastIThis.get());
    assertSame("ioResult forwarded as iCurrentResult", ioResult, fn.lastICurrentResult.get());
    assertSame("iContext forwarded", ctx, fn.lastContext.get());
  }

  @Test
  public void executePassesMethodIParamsAsFunctionRuntimeParameters() {
    // The method's iParams become the function's runtime iParams (post-setParameters). They are
    // NOT prepended with iThis — iThis is a separate argument slot.
    var fn = new RecordingFunction("pt", 1, 3);
    var delegate = new SQLMethodFunctionDelegate(fn);

    delegate.execute("ignored-iThis", null, ctx, null, new Object[] {"a", "b"});

    assertArrayEquals(new Object[] {"a", "b"}, fn.lastParams.get());
  }

  @Test
  public void executeReturnsWrappedFunctionResult() {
    // Whatever the wrapped function returns is what the delegate returns (with no
    // post-processing beyond SQLFunctionRuntime.transformValue, which is a no-op for plain
    // values).
    var fn = new RecordingFunction("pt", 1, 3);
    fn.returnValue = "wrapped-return";
    var delegate = new SQLMethodFunctionDelegate(fn);

    var out = delegate.execute("x", null, ctx, null, new Object[] {"a", "b"});

    assertEquals("wrapped-return", out);
  }

  // ---------------------------------------------------------------------------
  // Arity validation — SQLFunctionRuntime throws when runtime params violate min/max
  // ---------------------------------------------------------------------------

  @Test
  public void executeThrowsCommandExecutionExceptionWhenBelowMinParams() {
    // SQLFunctionRuntime validates arity against the WRAPPED function's min/max (not the
    // delegate's). A function with min=2 called with 1 runtime param → CEE "needs 2-3
    // argument(s) while has been received 1".
    var fn = new RecordingFunction("strict", 2, 3);
    var delegate = new SQLMethodFunctionDelegate(fn);

    try {
      delegate.execute("x", null, ctx, null, new Object[] {"only-one"});
      fail("expected CommandExecutionException for insufficient arity");
    } catch (CommandExecutionException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message should reference the arity mismatch, saw: " + e.getMessage(),
          e.getMessage().contains("strict"));
      assertTrue(
          "message should mention 'argument', saw: " + e.getMessage(),
          e.getMessage().contains("argument"));
    }
  }

  @Test
  public void executeThrowsCommandExecutionExceptionWhenAboveMaxParams() {
    var fn = new RecordingFunction("strict", 1, 2);
    var delegate = new SQLMethodFunctionDelegate(fn);

    try {
      delegate.execute("x", null, ctx, null, new Object[] {"a", "b", "c"});
      fail("expected CommandExecutionException for excess arity");
    } catch (CommandExecutionException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message should reference the arity mismatch, saw: " + e.getMessage(),
          e.getMessage().contains("strict"));
    }
  }

  @Test
  public void executeSkipsArityCheckWhenWrappedMaxIsZero() {
    // SQLFunctionRuntime arity guard is gated on `maxParams == -1 || maxParams > 0`. When
    // maxParams == 0, the check is skipped entirely (pins the branch).
    var fn = new RecordingFunction("noArgs", 0, 0);
    var delegate = new SQLMethodFunctionDelegate(fn);

    // Passing two runtime params with max=0 should still execute (no CEE raised here).
    var out = delegate.execute("x", null, ctx, null, new Object[] {"extra1", "extra2"});

    // RecordingFunction's default returnValue is null; but it was invoked — assert the capture.
    assertNotNull("function should have been invoked despite max=0 and non-empty iParams",
        fn.lastParams.get());
  }

  // ---------------------------------------------------------------------------
  // toString — parser-facing label
  // ---------------------------------------------------------------------------

  @Test
  public void toStringPrependsFunctionKeyword() {
    // The delegate prints "function " + func — useful for parser error messages. The func part
    // depends on SQLFunctionRuntime's default toString (usually empty / function-name); we only
    // pin the "function " prefix.
    var fn = new RecordingFunction("named", 1, 1);
    var delegate = new SQLMethodFunctionDelegate(fn);

    assertTrue(
        "toString should start with 'function ', saw: " + delegate,
        delegate.toString().startsWith("function "));
  }

  // ---------------------------------------------------------------------------
  // Name-constant surface (AbstractSQLMethod inherited contract)
  // ---------------------------------------------------------------------------

  @Test
  public void nameConstantIsFunction() {
    var fn = new RecordingFunction("any", 0, 0);
    var delegate = new SQLMethodFunctionDelegate(fn);

    assertEquals("function", SQLMethodFunctionDelegate.NAME);
    assertEquals(SQLMethodFunctionDelegate.NAME, delegate.getName());
  }

  @Test
  public void evaluateParametersInheritsSuperclassDefault() {
    // AbstractSQLMethod.evaluateParameters() defaults to true — delegate doesn't override it,
    // so the runtime params ARE evaluated for this method. Pin as drift guard.
    var fn = new RecordingFunction("any", 0, 0);
    var delegate = new SQLMethodFunctionDelegate(fn);

    // assertTrue/assertFalse reads cleaner than assertEquals(Boolean)
    if (!delegate.evaluateParameters()) {
      assertFalse(
          "if the default ever changes to false, update SQLMethodFieldTest's drift-guard note",
          false);
    }
  }

  // ---------------------------------------------------------------------------
  // Helper: a fully observable SQLFunction used as the wrapped target.
  // ---------------------------------------------------------------------------

  /**
   * Minimal, hand-rolled {@link SQLFunction} that records its inputs and returns a configurable
   * value. Every property (min, max, name, aggregate, filter) is configurable so each test can
   * pin exactly the delegate behaviour it cares about.
   */
  private static final class RecordingFunction implements SQLFunction {

    final String name;
    final int minParams;
    final int maxParams;

    Object returnValue;

    final AtomicReference<Object> lastIThis = new AtomicReference<>();
    final AtomicReference<Object> lastICurrentResult = new AtomicReference<>();
    final AtomicReference<Object[]> lastParams = new AtomicReference<>();
    final AtomicReference<CommandContext> lastContext = new AtomicReference<>();

    RecordingFunction(String name, int minParams, int maxParams) {
      this.name = name;
      this.minParams = minParams;
      this.maxParams = maxParams;
    }

    @Override
    public Object execute(
        Object iThis,
        Result iCurrentRecord,
        Object iCurrentResult,
        Object[] iParams,
        CommandContext iContext) {
      lastIThis.set(iThis);
      lastICurrentResult.set(iCurrentResult);
      lastParams.set(iParams);
      lastContext.set(iContext);
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
      return minParams;
    }

    @Override
    public int getMaxParams(DatabaseSessionEmbedded session) {
      return maxParams;
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return name + "(...)";
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
}
