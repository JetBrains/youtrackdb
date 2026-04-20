/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLPredicate;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionCount;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionIfNull;
import com.jetbrains.youtrackdb.internal.core.sql.method.SQLMethodRuntime;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodSize;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionRuntime} — the runtime-side wrapper around a {@link SQLFunction}
 * that handles parameter binding, parameter resolution against the current record/context,
 * arity validation, and delegation to
 * {@link SQLFunction#execute(Object, Result, Object, Object[], CommandContext)}.
 *
 * <p>This file lives under {@code sql/functions/} because it covers {@code SQLFunctionRuntime}
 * (the JaCoCo package for that class). Logically it belongs to Track 7 Step 4 — a Track 6
 * deferral (the class is coupled to the SQL parser for its primary constructor, but its
 * {@code SQLFunctionRuntime(SQLFunction)} programmatic path is the non-parser entry point that
 * is unit-testable in isolation).
 *
 * <p>The parser-bound constructor
 * {@link SQLFunctionRuntime#SQLFunctionRuntime(DatabaseSessionEmbedded, BaseParser, String)}
 * and the {@link SQLFunctionRuntime#setRoot(DatabaseSessionEmbedded, BaseParser, String)}
 * method are exercised indirectly by full SQL-execution integration tests — explicitly out of
 * scope for Track 7 Step 4 (deferred to Track 8's executor coverage, where SQL queries produce
 * fully-parsed SQLFunctionRuntime instances).
 *
 * <p>Extends {@link DbTestBase} because {@link SQLFunctionRuntime#setParameters} calls
 * {@link com.jetbrains.youtrackdb.internal.core.sql.SQLHelper#parseValue(BaseParser, String,
 * CommandContext)} which in turn resolves {@link CommandContext#getDatabaseSession()}. A bare
 * {@code new BasicCommandContext()} without a session raises
 * {@code DatabaseException("No database session found in SQL context")}.
 *
 * <p>Covered branches / contracts:
 *
 * <ul>
 *   <li>{@link SQLFunctionRuntime#SQLFunctionRuntime(SQLFunction)} — programmatic constructor
 *       sets {@code function} only; {@code configuredParameters} / {@code runtimeParameters}
 *       remain {@code null} until {@code setParameters} seeds them.</li>
 *   <li>{@link SQLFunctionRuntime#aggregateResults()} and {@link SQLFunctionRuntime#filterResult()}
 *       — direct delegation to the wrapped function.</li>
 *   <li>{@link SQLFunctionRuntime#setParameters(CommandContext, Object[], boolean)}:
 *       null element storage, single/double-quoted quote stripping, numeric / {@code true} /
 *       {@code false} / {@code null} literal conversion, non-String pass-through,
 *       bracket-prefixed-raw pass-through, unparseable-identifier → SQLFilterItemField
 *       promotion, {@code iEvaluate=false} short-circuit, runtimeParameters allocation
 *       mirroring configuredParameters length, and the "live type" skip rule (SQLFilterItemField
 *       and SQLFunctionRuntime slots are NOT seeded into runtimeParameters at setParameters
 *       time — execute() resolves them).</li>
 *   <li>{@link SQLFunctionRuntime#execute(Object, Result, Object, CommandContext)} resolver
 *       branches: {@link SQLFilterItemField} / nested {@link SQLFunctionRuntime} /
 *       {@link SQLFilterItemVariable} / {@link SQLPredicate} / String-starts-with-quote /
 *       non-quote-string pass-through / null pass-through / non-String pass-through.</li>
 *   <li>Arity validation: too-few, too-many, equal min=max single-number message, unequal
 *       min-max range message, {@code maxParams == 0} arity-check-skipped branch,
 *       {@code maxParams == -1} upper-bound-skipped branch, and invocation-ordering
 *       (arity check fires BEFORE function.execute dispatch).</li>
 *   <li>{@link SQLFunctionRuntime#getResult(DatabaseSessionEmbedded)} — fresh
 *       {@code BasicCommandContext}, delegates to {@code function.getResult()}, with
 *       transformValue applied (operationsChain is null in the programmatic path).</li>
 *   <li>{@link SQLFunctionRuntime#setResult(Object)} — delegates to
 *       {@code function.setResult}.</li>
 *   <li>{@link SQLFunctionRuntime#getValue(Result, Object, CommandContext)} — wraps execute.</li>
 *   <li>{@link SQLFunctionRuntime#getRoot(DatabaseSessionEmbedded)} — returns function name.</li>
 *   <li>{@link SQLFunctionRuntime#getFunction()} / {@link SQLFunctionRuntime#getConfiguredParameters()}
 *       / {@link SQLFunctionRuntime#getRuntimeParameters()} — simple getters.</li>
 * </ul>
 *
 * <p>Latent-bug pins documented below with {@code // WHEN-FIXED:} markers are deferred to
 * Track 22.
 */
public class SQLFunctionRuntimeTest extends DbTestBase {

  private BasicCommandContext ctx;

  @Before
  public void setUpCtx() {
    ctx = new BasicCommandContext(session);
  }

  // ---------------------------------------------------------------------------
  // Constructors & simple getters
  // ---------------------------------------------------------------------------

  @Test
  public void constructorSQLFunctionOnlySetsFunctionField() {
    // The programmatic constructor bypasses SQLFilterItemAbstract's parser-based super-ctor;
    // configuredParameters / runtimeParameters must remain null until setParameters is invoked.
    var fn = new SQLFunctionIfNull();
    var runtime = new SQLFunctionRuntime(fn);
    assertSame(fn, runtime.getFunction());
    assertNull(runtime.getConfiguredParameters());
    assertNull(runtime.getRuntimeParameters());
  }

  @Test
  public void getRootReturnsWrappedFunctionName() {
    // SQLFilterItemAbstract.asString uses getRoot to render the fully-qualified chain. Pin that
    // it's the wrapped function's identifier (not a human-readable description).
    var runtime = new SQLFunctionRuntime(new SQLFunctionIfNull());
    assertEquals(SQLFunctionIfNull.NAME, runtime.getRoot(session));
  }

  @Test
  public void aggregateResultsDelegatesToFunction() {
    // SQLFunctionCount.aggregateResults() → true; SQLFunctionIfNull.aggregateResults() → false.
    // Pin both outcomes so a regression that hard-codes either value would surface here.
    var aggregate = new SQLFunctionRuntime(new SQLFunctionCount());
    assertTrue(aggregate.aggregateResults());
    var nonAggregate = new SQLFunctionRuntime(new SQLFunctionIfNull());
    assertFalse(nonAggregate.aggregateResults());
  }

  @Test
  public void filterResultDelegatesToFunction() {
    // Both built-in functions return false by default (inherited from SQLFunctionAbstract).
    // Use a RecordingFunction to pin the true-path too.
    var fn = new RecordingFunction("filter-fn");
    fn.filterResult = true;
    assertTrue(new SQLFunctionRuntime(fn).filterResult());
    fn.filterResult = false;
    assertFalse(new SQLFunctionRuntime(fn).filterResult());
  }

  // ---------------------------------------------------------------------------
  // setParameters — literal conversions and pass-through rules
  // ---------------------------------------------------------------------------

  @Test
  public void setParametersSingleQuotedStringStripsQuotes() {
    // Single-quoted input routes through SQLHelper.parseValue → IOUtils.getStringContent. The
    // outcome is the unquoted payload — both configured and runtime arrays hold it.
    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.setParameters(ctx, new Object[] {"'abc'"}, true);
    assertArrayEquals(new Object[] {"abc"}, runtime.getConfiguredParameters());
    // Copy-static-values loop propagates to runtimeParameters because neither type is a live
    // SQLFilterItemField/SQLFunctionRuntime.
    assertArrayEquals(new Object[] {"abc"}, runtime.getRuntimeParameters());
  }

  @Test
  public void setParametersDoubleQuotedStringStripsQuotes() {
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {"\"hello\""}, true);
    assertArrayEquals(new Object[] {"hello"}, runtime.getConfiguredParameters());
  }

  @Test
  public void setParametersNumericStringParsedAsNumber() {
    // SQLHelper.parseValue promotes numeric literals — "7" becomes Integer.
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {"7"}, true);
    assertEquals(7, runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersNullLiteralStringBecomesJavaNull() {
    // Case-insensitive "null" literal is promoted to Java null.
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {"null"}, true);
    assertNull(runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersTrueLiteralStringBecomesBooleanTrue() {
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {"true"}, true);
    assertEquals(Boolean.TRUE, runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersFalseLiteralStringBecomesBooleanFalse() {
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {"false"}, true);
    assertEquals(Boolean.FALSE, runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersNullElementStoredAsNull() {
    // iParameters[i] == null takes the else branch — stores null verbatim. Regression guard
    // for a silent drop to empty string or "null" literal.
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {null}, true);
    assertNull(runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersNonStringPassThrough() {
    // Non-String params are stored as-is (setParameters only routes Strings through parseValue).
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {42L}, true);
    assertEquals(42L, runtime.getConfiguredParameters()[0]);
  }

  @Test
  public void setParametersUnparseableIdentifierBecomesFilterItemField() {
    // An unquoted identifier that isn't a literal/number/RID/boolean/variable is promoted to a
    // fresh SQLFilterItemField so execute() can resolve it against the current record. Pin the
    // actual conversion so a regression that keeps the raw string surfaces here — a raw string
    // would miss the resolver branch entirely.
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {"someIdentifier"}, true);
    var converted = runtime.getConfiguredParameters()[0];
    assertTrue(
        "expected unparseable identifier to be promoted to SQLFilterItemField, got: "
            + (converted == null ? "null" : converted.getClass().getName()),
        converted instanceof SQLFilterItemField);
  }

  @Test
  public void setParametersEvaluateFalseSkipsLiteralConversion() {
    // iEvaluate=false bypasses the parseValue routing entirely. The raw inputs flow into
    // configuredParameters unchanged — Strings stay as Strings, not promoted to
    // SQLFilterItemField / Boolean / Integer / null.
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    runtime.setParameters(ctx, new Object[] {"true", "null", "7", "someField"}, false);
    assertArrayEquals(
        new Object[] {"true", "null", "7", "someField"}, runtime.getConfiguredParameters());
    assertArrayEquals(
        new Object[] {"true", "null", "7", "someField"}, runtime.getRuntimeParameters());
  }

  @Test
  public void setParametersAllocatesRuntimeParametersMatchingConfigured() {
    // runtimeParameters is always sized to configuredParameters.length even when every slot is
    // a "live" type (SQLFilterItemField or SQLFunctionRuntime) that is null-seeded until
    // execute() fires the resolver.
    var runtime = new SQLFunctionRuntime(new RecordingFunction("fn"));
    var fld = new SQLFilterItemField(session, "f", null);
    runtime.setParameters(ctx, new Object[] {fld}, true);
    var runtimeParams = runtime.getRuntimeParameters();
    assertNotNull(runtimeParams);
    assertEquals(1, runtimeParams.length);
    // SQLFilterItemField slot is not seeded — execute() resolves it later.
    assertNull(runtimeParams[0]);
  }

  @Test
  public void setParametersNestedSQLFunctionRuntimeSlotStaysNullAtSetupTime() {
    // The copy-static-values loop explicitly excludes SQLFunctionRuntime configured slots —
    // they must be resolved on each execute() call because the nested function may observe a
    // different record/context each time.
    var inner = new RecordingFunction("inner");
    var innerRuntime = new SQLFunctionRuntime(inner);
    innerRuntime.configuredParameters = new Object[0];
    innerRuntime.runtimeParameters = new Object[0];

    var outer = new SQLFunctionRuntime(new RecordingFunction("outer"));
    outer.setParameters(ctx, new Object[] {innerRuntime}, true);

    // configuredParameters stores the nested runtime; runtimeParameters[0] stays null.
    assertSame(innerRuntime, outer.getConfiguredParameters()[0]);
    assertNull(outer.getRuntimeParameters()[0]);
  }

  @Test
  public void setParametersInvokesFunctionConfig() {
    // setParameters calls function.config(configuredParameters) exactly once. Pin this because
    // function.config is the SQLFunctionAbstract hook where stateful aggregators (e.g., custom
    // stat functions) capture their setup parameters. A regression that reorders / drops the
    // call would silently break aggregators. Assert both invocation count (1) and the fact that
    // the config argument reference equals the internal configuredParameters array.
    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.setParameters(ctx, new Object[] {"'x'", 2}, true);
    assertEquals(1, fn.configCallCount);
    assertSame(runtime.getConfiguredParameters(), fn.lastConfigArgs);
  }

  // ---------------------------------------------------------------------------
  // execute — resolver branches
  // ---------------------------------------------------------------------------

  @Test
  public void executeWithNoParametersDelegatesToFunctionReturn() {
    // Empty configuredParameters + empty runtimeParameters → resolver loop is a no-op; function
    // is invoked with an empty iParams array. Pin that the function's return threads back
    // through transformValue unchanged (operationsChain is null in the programmatic path, so
    // transformValue is effectively an identity).
    var fn = new RecordingFunction("fn");
    fn.returnValue = "sentinel";
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[0];
    runtime.runtimeParameters = new Object[0];

    var out = runtime.execute("iThis", null, null, ctx);
    assertEquals("sentinel", out);
    assertTrue(fn.invoked);
    assertEquals(0, fn.lastParams.length);
    assertEquals("iThis", fn.lastIThis);
  }

  @Test
  public void executeSQLFilterItemFieldParameterResolvedFromRecord() {
    // configuredParameters[i] instanceof SQLFilterItemField branch: execute() calls
    // field.getValue(record, ioResult, ctx). Pin both the function's lastParams (resolver
    // output is threaded into iParams) and the function's return (passes back unchanged).
    var fn = new RecordingFunction("echo");
    fn.returnValue = "fn-return";
    var runtime = new SQLFunctionRuntime(fn);
    var fld = new SQLFilterItemField(session, "f", null);
    runtime.setParameters(ctx, new Object[] {fld}, true);

    var record = new ResultInternal(session);
    record.setProperty("f", "resolved-value");

    var out = runtime.execute("iThisIgnored", record, null, ctx);

    assertEquals("fn-return", out);
    // The field resolver MUST replace the configured item with the value in runtimeParameters,
    // and the function must receive the value, not the SQLFilterItemField instance.
    assertArrayEquals(new Object[] {"resolved-value"}, fn.lastParams);
  }

  @Test
  public void executeWithNestedSQLFunctionRuntimeParameterIsInvoked() {
    // configuredParameters[i] instanceof SQLFunctionRuntime branch: outer execute() recurses
    // into inner.execute(iThis, iCurrentRecord, iCurrentResult, iContext) and stashes the
    // nested return into runtimeParameters[i]. Pin invocation (invoked flag) + return threading
    // + iThis propagation. An invoked flag on top of the lastParams assertion is important
    // because RecordingFunction.returnValue defaults to null — a regression that stashed the
    // nested return via some other channel would pass the array assertion without ever calling
    // inner.execute.
    var inner = new RecordingFunction("inner");
    inner.returnValue = "inner-out";
    var innerRuntime = new SQLFunctionRuntime(inner);
    innerRuntime.configuredParameters = new Object[0];
    innerRuntime.runtimeParameters = new Object[0];

    var outer = new RecordingFunction("outer");
    var outerRuntime = new SQLFunctionRuntime(outer);
    outerRuntime.setParameters(ctx, new Object[] {innerRuntime}, true);

    outerRuntime.execute("outer-this", null, null, ctx);

    assertTrue("nested SQLFunctionRuntime.execute must be invoked", inner.invoked);
    assertArrayEquals(new Object[] {"inner-out"}, outer.lastParams);
    // iThis propagates from outer → inner.
    assertEquals("outer-this", inner.lastIThis);
  }

  @Test
  public void executeWithSQLFilterItemVariableParameterResolvesFromContext() {
    // configuredParameters[i] instanceof SQLFilterItemVariable branch: execute() calls
    // variable.getValue(...) which delegates to iContext.getVariable(name).
    var variable = new SQLFilterItemVariable(session, new StubParser("$v"), "$v");
    ctx.setVariable("v", "ctx-value");

    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.setParameters(ctx, new Object[] {variable}, true);

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {"ctx-value"}, fn.lastParams);
  }

  @Test
  public void executeWithSQLPredicateParameterEvaluatesToBoolean() {
    // configuredParameters[i] instanceof SQLPredicate branch: a trivial "1=1" predicate
    // evaluates to Boolean.TRUE regardless of record. Pin that the predicate.evaluate() result
    // is stashed into runtimeParameters[i].
    var pred = new SQLPredicate(ctx, "1=1");

    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    // Bypass setParameters because parseValue doesn't emit SQLPredicate instances — the parser
    // does, via the parser-bound constructor. Direct field assignment is legal per the public
    // contract and matches the SQLMethodRuntimeTest "ASSIGN configuredParameters directly"
    // pattern used to exercise rarely-reached runtime-side branches.
    runtime.configuredParameters = new Object[] {pred};
    runtime.runtimeParameters = new Object[1];

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {Boolean.TRUE}, fn.lastParams);
  }

  @Test
  public void executeSQLPredicateEntityImplBranchIsDeadCodeAgainstResultTypedParam() {
    // WHEN-FIXED: in SQLFunctionRuntime.execute, the SQLPredicate branch passes
    //   (iCurrentRecord instanceof EntityImpl ? (EntityImpl) iCurrentResult : null)
    // as the iCurrentResult argument to SQLPredicate.evaluate. But iCurrentRecord is typed as
    // Result — a Result is NEVER an EntityImpl, so the ternary's true branch is unreachable,
    // and iCurrentResult is always passed as null. Author likely meant
    //   (iCurrentResult instanceof EntityImpl ? (EntityImpl) iCurrentResult : null)
    // which would forward the explicit iCurrentResult argument when the caller provided an
    // entity. Track 22 action: swap iCurrentRecord → iCurrentResult in the instanceof check.
    //
    // This test pins the current (buggy) behaviour by constructing a predicate that evaluates
    // to its own unmodified right side regardless of record/result input — any regression that
    // starts passing iCurrentResult through would be caught by the predicate seeing a non-null
    // entity. We verify indirectly by asserting the current false branch is exercised: pass a
    // Result (not EntityImpl) as iCurrentRecord and a mock EntityImpl as iCurrentResult; the
    // predicate still evaluates because its body is type-independent.
    //
    // Keep the pin light — the production code works for its callers because SQLPredicate.
    // evaluate tolerates a null iCurrentResult. The bug is latent unless a future predicate
    // depends on the iCurrentResult argument.
    var pred = new SQLPredicate(ctx, "1=1");

    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[] {pred};
    runtime.runtimeParameters = new Object[1];

    // Real ResultInternal as iCurrentRecord — it is NOT an EntityImpl, so the bug path fires.
    var record = new ResultInternal(session);
    runtime.execute("iThis", record, "someIrrelevantCurrentResult", ctx);
    assertArrayEquals(new Object[] {Boolean.TRUE}, fn.lastParams);
  }

  @Test
  public void executeWithDoubleQuotedStringInConfiguredParametersStripsQuotes() {
    // execute-side String-starts-with-`"` branch: when configured holds a quoted literal that
    // bypassed setParameters, execute() strips the quotes via IOUtils.getStringContent and
    // writes the result to runtimeParameters[i].
    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[] {"\"quoted-payload\""};
    runtime.runtimeParameters = new Object[1];

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {"quoted-payload"}, fn.lastParams);
    // Also assert the runtime-side mutation — the branch updates runtimeParameters[i] in place.
    // A regression that wrote the stripped value to a local variable without mutating the
    // runtime array would still hand the right iParams to function.execute but leave
    // runtime.runtimeParameters stale.
    assertArrayEquals(new Object[] {"quoted-payload"}, runtime.getRuntimeParameters());
  }

  @Test
  public void executeWithSingleQuoteStringInConfiguredParametersStripsQuotes() {
    // Second quote-type branch: starts with `'`.
    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[] {"'single'"};
    runtime.runtimeParameters = new Object[1];

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {"single"}, fn.lastParams);
    assertArrayEquals(new Object[] {"single"}, runtime.getRuntimeParameters());
  }

  @Test
  public void executeWithUnquotedStringInConfiguredParametersPassesThroughVerbatim() {
    // The String-branch in execute() only fires for quote-prefixed strings. A non-quote-prefix
    // string flows through as-is. Pin the outer invariant: runtimeParameters[i] keeps the
    // pre-resolver seed value (set in the `runtimeParameters[i] = configuredParameters[i]`
    // first line of the resolver loop) — i.e., the String itself.
    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[] {"unquoted"};
    runtime.runtimeParameters = new Object[1];

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {"unquoted"}, fn.lastParams);
  }

  @Test
  public void executeWithNullInConfiguredParametersPassesThroughAsNull() {
    // A null in configuredParameters doesn't match any resolver branch (all checks are
    // `instanceof X` which is false for null). The pre-resolver seed
    // `runtimeParameters[i] = configuredParameters[i]` puts null into runtimeParameters[i] and
    // the rest of the loop is a no-op.
    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[] {null};
    runtime.runtimeParameters = new Object[1];

    runtime.execute("iThis", null, null, ctx);
    assertArrayEquals(new Object[] {null}, fn.lastParams);
  }

  @Test
  public void executeWithNestedSQLMethodRuntimeParameterIsStoredAsIs() {
    // Important asymmetry with SQLMethodRuntime: SQLFunctionRuntime.execute's resolver loop
    // does NOT recurse into nested SQLMethodRuntime configured parameters (only
    // SQLFunctionRuntime is recursed). The pre-resolver seed `runtimeParameters[i] =
    // configuredParameters[i]` places the SQLMethodRuntime instance into runtimeParameters[i]
    // unchanged, and the function sees the wrapper, not a resolved value.
    //
    // WHEN-FIXED: if a future refactor adds a SQLMethodRuntime branch symmetric with the
    // SQLMethodRuntime.execute resolver loop (which DOES recurse into nested SQLFunctionRuntime
    // AND nested SQLMethodRuntime), flip this assertion to match the resolved value. Track 22
    // is the candidate fix location because the two resolver loops should be consistent.
    var methodRuntime = new SQLMethodRuntime(new SQLMethodSize());

    var fn = new RecordingFunction("fn");
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[] {methodRuntime};
    runtime.runtimeParameters = new Object[1];

    runtime.execute("iThis", null, null, ctx);
    assertNotNull(fn.lastParams);
    assertEquals(1, fn.lastParams.length);
    assertSame(methodRuntime, fn.lastParams[0]);
  }

  // ---------------------------------------------------------------------------
  // Arity validation
  // ---------------------------------------------------------------------------

  @Test
  public void executeTooFewParametersThrowsRangeFormattedMessage() {
    // SQLFunctionIfNull has min=2, max=3. Call with 1 numeric param to bypass SQLHelper's
    // "promote unparseable string → SQLFilterItemField" behaviour (an unresolved field lookup
    // would fail with a different error and mask the arity check).
    var runtime = new SQLFunctionRuntime(new SQLFunctionIfNull());
    runtime.setParameters(ctx, new Object[] {1}, true);

    try {
      runtime.execute("iThis", null, null, ctx);
      fail("expected CommandExecutionException for insufficient arity");
    } catch (CommandExecutionException e) {
      var msg = e.getMessage();
      assertNotNull(msg);
      // Message template: "Syntax error: function 'NAME' needs MIN-MAX argument(s) while has
      // been received COUNT". Pin all three pieces.
      assertTrue("should name the function: " + msg, msg.contains("'ifnull'"));
      assertTrue("should show range 2-3: " + msg, msg.contains("2-3"));
      assertTrue("should report received count 1: " + msg, msg.contains("received 1"));
    }
  }

  @Test
  public void executeTooManyParametersThrowsRangeFormattedMessage() {
    var runtime = new SQLFunctionRuntime(new SQLFunctionIfNull());
    runtime.setParameters(ctx, new Object[] {1, 2, 3, 4}, true);

    try {
      runtime.execute("iThis", null, null, ctx);
      fail("expected CommandExecutionException for excess arity");
    } catch (CommandExecutionException e) {
      var msg = e.getMessage();
      assertTrue("should name: " + msg, msg.contains("'ifnull'"));
      assertTrue("range 2-3: " + msg, msg.contains("2-3"));
      assertTrue("received 4: " + msg, msg.contains("received 4"));
    }
  }

  @Test
  public void executeEqualMinMaxUsesSingleNumberInMessage() {
    // SQLFunctionCount has min=max=1. Calling with 0 params must format "needs 1 argument"
    // (not "1-1"). Pin the single-number branch of the arity message.
    var runtime = new SQLFunctionRuntime(new SQLFunctionCount());
    runtime.setParameters(ctx, new Object[0], true);

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
    // Outer guard: `if (getMaxParams(session) == -1 || getMaxParams(session) > 0)`. When
    // maxParams == 0 the entire check is skipped — extra runtime params silently pass through
    // to function.execute. Pin the branch. A production fix that special-cases zero would flip
    // this test.
    var fn = new RecordingFunction("noArgs", 0, 0);
    fn.returnValue = "sentinel";
    var runtime = new SQLFunctionRuntime(fn);
    runtime.setParameters(ctx, new Object[] {1, 2}, true);

    var out = runtime.execute("iThis", null, null, ctx);
    assertEquals("sentinel", out);
    assertTrue("function must be invoked when arity check is skipped", fn.invoked);
    assertArrayEquals(new Object[] {1, 2}, fn.lastParams);
  }

  @Test
  public void executeMaxParamsNegativeOneSkipsUpperBoundCheck() {
    // maxParams == -1 is the "unlimited" sentinel: lower bound still enforced, upper bound
    // ignored.
    var variadic = new RecordingFunction("variadic", 1, -1);

    // Too few — still throws.
    var tooFew = new SQLFunctionRuntime(variadic);
    tooFew.setParameters(ctx, new Object[0], true);
    try {
      tooFew.execute("iThis", null, null, ctx);
      fail("expected CommandExecutionException for too few");
    } catch (CommandExecutionException expected) {
      assertTrue(expected.getMessage().contains("'variadic'"));
    }

    // Many — passes. Fresh fn/runtime to avoid stale state aliasing a silent short-circuit.
    var manyFn = new RecordingFunction("variadic", 1, -1);
    manyFn.returnValue = "many-ok";
    var many = new SQLFunctionRuntime(manyFn);
    many.setParameters(ctx, new Object[] {1, 2, 3, 4, 5, 6, 7}, true);
    var out = many.execute("iThis", null, null, ctx);
    assertEquals("many-ok", out);
    assertTrue("function must be invoked when upper-bound check is skipped", manyFn.invoked);
    assertArrayEquals(new Object[] {1, 2, 3, 4, 5, 6, 7}, manyFn.lastParams);
  }

  @Test
  public void executeExactlyAtLowerBoundInvokesFunction() {
    // Boundary: runtimeParameters.length == minParams. The inequality `<` is strict, so an
    // exact-match length must NOT throw.
    var fn = new RecordingFunction("boundary", 2, 3);
    fn.returnValue = "ok";
    var runtime = new SQLFunctionRuntime(fn);
    runtime.setParameters(ctx, new Object[] {1, 2}, true);

    var out = runtime.execute("iThis", null, null, ctx);
    assertEquals("ok", out);
    assertTrue(fn.invoked);
    assertArrayEquals(new Object[] {1, 2}, fn.lastParams);
  }

  @Test
  public void executeExactlyAtUpperBoundInvokesFunction() {
    // Boundary: runtimeParameters.length == maxParams (when max > 0). Strict `>` comparison.
    var fn = new RecordingFunction("boundary", 1, 3);
    fn.returnValue = "ok";
    var runtime = new SQLFunctionRuntime(fn);
    runtime.setParameters(ctx, new Object[] {1, 2, 3}, true);

    var out = runtime.execute("iThis", null, null, ctx);
    assertEquals("ok", out);
    assertTrue(fn.invoked);
    assertArrayEquals(new Object[] {1, 2, 3}, fn.lastParams);
  }

  // ---------------------------------------------------------------------------
  // getResult / setResult / getValue
  // ---------------------------------------------------------------------------

  @Test
  public void getResultAfterAggregateExecuteReturnsRunningTotal() {
    // SQLFunctionCount is an aggregator: execute() returns the running total but the final
    // aggregate value is retrieved via getResult(session). Pin that getResult threads through
    // transformValue (identity in programmatic path) and returns the function's accumulated
    // state.
    var count = new SQLFunctionCount();
    var runtime = new SQLFunctionRuntime(count);
    runtime.configuredParameters = new Object[] {"*"};
    runtime.runtimeParameters = new Object[] {"*"};

    // Four execute() calls with non-null iParams[0] increment the running total.
    runtime.execute("r1", null, null, ctx);
    runtime.execute("r2", null, null, ctx);
    runtime.execute("r3", null, null, ctx);
    runtime.execute("r4", null, null, ctx);

    var result = runtime.getResult(session);
    assertEquals(4L, result);
  }

  @Test
  public void setResultDelegatesToFunction() {
    // SQLFunctionCount.setResult((Number) iResult) restores its running total. Pin delegation:
    // a subsequent getResult returns whatever setResult injected.
    var count = new SQLFunctionCount();
    var runtime = new SQLFunctionRuntime(count);
    runtime.setResult(42L);
    assertEquals(42L, runtime.getResult(session));
  }

  @Test
  public void getValueDelegatesToExecuteWithRecordAsIThis() {
    // SQLFunctionRuntime.getValue(iRecord, iCurrentResult, iContext) calls
    //   execute(iRecord, iRecord, null, iContext)
    // using the record as BOTH iThis and iCurrentRecord. Pin this channeling by observing
    // RecordingFunction.lastIThis — it must equal the record, not the explicit iCurrentResult.
    var fn = new RecordingFunction("echo");
    fn.returnValue = "out";
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[0];
    runtime.runtimeParameters = new Object[0];

    var record = new ResultInternal(session);
    record.setProperty("a", "b");

    var out = runtime.getValue(record, "unused-iCurrentResult", ctx);
    assertEquals("out", out);
    assertSame("getValue must pass iRecord as iThis", record, fn.lastIThis);
    // Function MUST have been invoked — guards against a silent null-short-circuit regression.
    assertTrue(fn.invoked);
  }

  @Test
  public void getValueCatchesRecordNotFoundExceptionReturnsNull() {
    // getValue's try/catch swallows RecordNotFoundException → null. Pin by making the wrapped
    // function throw RecordNotFoundException; getValue must NOT propagate it.
    var fn = new RecordingFunction("throwRNF");
    fn.throwRNF = true;
    var runtime = new SQLFunctionRuntime(fn);
    runtime.configuredParameters = new Object[0];
    runtime.runtimeParameters = new Object[0];

    // Use a real record so fn.lastIThis is non-null if the catch path is reached correctly.
    var record = new ResultInternal(session);
    assertNull(runtime.getValue(record, null, ctx));
    // Verify the function was in fact invoked (not a trivial "return null without trying").
    assertTrue("function must be invoked before RNF swallow", fn.invoked);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link SQLFunction} that records its inputs and returns a configurable value. Mirrors
   * SQLMethodRuntimeTest.ProbeMethod in spirit — configurable arity and return value, recorded
   * observations, invoked flag. The {@code throwRNF} knob lets us exercise getValue's
   * RecordNotFoundException swallow branch without relying on a real dangling RID.
   */
  private static final class RecordingFunction implements SQLFunction {

    final String name;
    final int minParams;
    final int maxParams;

    // Tuning knobs (set by the test before invoking SQLFunctionRuntime):
    Object returnValue;
    boolean filterResult;
    boolean throwRNF;

    // Recorded observations:
    Object lastIThis;
    Object[] lastParams;
    boolean invoked;
    int configCallCount;
    Object[] lastConfigArgs;

    RecordingFunction(String name) {
      this(name, 0, 0);
    }

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
      this.invoked = true;
      this.lastIThis = iThis;
      this.lastParams = iParams;
      if (throwRNF) {
        throw new com.jetbrains.youtrackdb.api.exception.RecordNotFoundException(
            "synthetic", new com.jetbrains.youtrackdb.internal.core.id.RecordId(-1, -1));
      }
      return returnValue;
    }

    @Override
    public void config(Object[] configuredParameters) {
      this.configCallCount++;
      this.lastConfigArgs = configuredParameters;
    }

    @Override
    public boolean aggregateResults() {
      return false;
    }

    @Override
    public boolean filterResult() {
      return filterResult;
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
   * input text is a pre-tokenized identifier that requires no real parsing.
   *
   * <p>Pinning {@code Locale.ENGLISH} avoids the Turkish-locale trap where {@code "i".toUpperCase()}
   * → {@code "İ"} (U+0130), breaking case comparisons in downstream parser helpers. Matches the
   * {@code Locale.ENGLISH} convention used throughout SQL dispatch and in
   * SQLMethodRuntimeTest.StubParser.
   */
  private static final class StubParser extends BaseParser {

    StubParser(String text) {
      this.parserText = text;
      this.parserTextUpperCase = text.toUpperCase(Locale.ENGLISH);
    }

    @Override
    protected void throwSyntaxErrorException(String dbName, String iText) {
      throw new IllegalStateException(
          "StubParser does not implement syntax-error reporting (dbName="
              + dbName
              + ", text="
              + iText
              + ")");
    }
  }
}
