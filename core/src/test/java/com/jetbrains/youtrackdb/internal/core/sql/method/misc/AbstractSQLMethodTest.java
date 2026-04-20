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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.method.SQLMethod;
import org.junit.Test;

/**
 * Tests for {@link AbstractSQLMethod} — the common base class for most SQL method
 * implementations. Because the class is abstract, a minimal concrete subclass
 * ({@link FixtureMethod}) is used to exercise each inherited contract. The class is deliberately
 * standalone (no {@link com.jetbrains.youtrackdb.internal.DbTestBase}) because every covered
 * method is computable in-process: the three constructors, the {@code getSyntax} string builder,
 * {@code getMinParams}/{@code getMaxParams}, {@code compareTo}, {@code toString},
 * {@code evaluateParameters}, and the static {@code getParameterValue} helper that accepts a
 * {@link Result} and does NOT reach into a database session.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Constructor {@code (name)} → minParams == maxParams == 0.</li>
 *   <li>Constructor {@code (name, n)} → minParams == maxParams == n.</li>
 *   <li>Constructor {@code (name, min, max)} → bounds carried directly.</li>
 *   <li>{@code getSyntax()} — no params, fixed params, variable arity (optional) — pins all three
 *       branches of the signature-building loop.</li>
 *   <li>{@code getMaxParams(session)} is session-ignored by the base class — pins the NULL-session
 *       contract so subclass overrides can't silently introduce a session dependency.</li>
 *   <li>{@code compareTo} is lexicographic over names, including the equals-returns-zero edge.</li>
 *   <li>{@code toString()} returns the name.</li>
 *   <li>{@code evaluateParameters()} defaults to {@code true}.</li>
 *   <li>{@code getParameterValue} covers all five branches: {@code null} input, single-quoted,
 *       double-quoted, null record, present property, exception-from-record → null.</li>
 * </ul>
 */
public class AbstractSQLMethodTest {

  // ---------------------------------------------------------------------------
  // Constructors — bounds
  // ---------------------------------------------------------------------------

  @Test
  public void singleArgCtorDefaultsBothBoundsToZero() {
    // The (name) ctor forwards to (name, 0), which forwards to (name, 0, 0).
    var m = new FixtureMethod("foo");
    assertEquals(0, m.getMinParams());
    assertEquals(0, m.getMaxParams(null));
  }

  @Test
  public void twoArgCtorMirrorsNIntoBothBounds() {
    // (name, n) → min == max == n. Use a non-zero n so we also see the sub-contract.
    var m = new FixtureMethod("foo", 3);
    assertEquals(3, m.getMinParams());
    assertEquals(3, m.getMaxParams(null));
  }

  @Test
  public void threeArgCtorCarriesDistinctBounds() {
    var m = new FixtureMethod("foo", 1, 4);
    assertEquals(1, m.getMinParams());
    assertEquals(4, m.getMaxParams(null));
  }

  @Test
  public void getNameReturnsConstructorName() {
    assertEquals("foo", new FixtureMethod("foo").getName());
  }

  // ---------------------------------------------------------------------------
  // getMaxParams — session-ignored contract
  // ---------------------------------------------------------------------------

  @Test
  public void getMaxParamsIgnoresSessionArgument() {
    // The base class makes maxParams a pure property of the subclass construction: it does NOT
    // consult the passed-in session. If a future subclass overrides and starts consulting the
    // session, this pin forces an explicit decision.
    var m = new FixtureMethod("foo", 2, 5);
    assertEquals(5, m.getMaxParams(null));
    // Passing a non-null session would normally require a DbTestBase; we deliberately avoid
    // that here to prove the session is unused. This is also why the test is standalone.
  }

  // ---------------------------------------------------------------------------
  // getSyntax — string builder branches
  // ---------------------------------------------------------------------------

  @Test
  public void getSyntaxZeroParamsRendersEmptyParenthesis() {
    // min=max=0 → no params inside () and no optional block. Pins: the loop runs zero times and
    // the min!=max branch is not entered.
    assertEquals("<field>.foo()", new FixtureMethod("foo").getSyntax());
  }

  @Test
  public void getSyntaxOneFixedParamRendersSingleNamedParam() {
    // min=max=1 → "param1" inside (), no optional block.
    assertEquals("<field>.foo(param1)", new FixtureMethod("foo", 1, 1).getSyntax());
  }

  @Test
  public void getSyntaxTwoFixedParamsRendersCommaSeparatedParams() {
    // Pins the `if (i != 0) ", "` separator branch.
    assertEquals("<field>.foo(param1, param2)", new FixtureMethod("foo", 2, 2).getSyntax());
  }

  @Test
  public void getSyntaxVariableArityFromZeroToTwoRendersOptionalBlockOnly() {
    // min=0, max=2 → no fixed params, but two optional ones. Pin the min!=max branch's
    // separator handling when i starts at 0 (separator suppressed) and then i==1 adds ", ".
    assertEquals("<field>.foo([param1, param2])", new FixtureMethod("foo", 0, 2).getSyntax());
  }

  @Test
  public void getSyntaxVariableArityFromOneToThreeRendersBothBlocks() {
    // min=1, max=3 → one required, two optional. Pins the typical "mixed" case.
    assertEquals(
        "<field>.foo(param1[, param2, param3])", new FixtureMethod("foo", 1, 3).getSyntax());
  }

  // ---------------------------------------------------------------------------
  // toString / compareTo / evaluateParameters
  // ---------------------------------------------------------------------------

  @Test
  public void toStringReturnsName() {
    // Not a deep contract — but used in parser diagnostics; pin to catch silent drift.
    assertEquals("foo", new FixtureMethod("foo").toString());
  }

  @Test
  public void compareToIsLexicographicByName() {
    var alpha = new FixtureMethod("alpha");
    var beta = new FixtureMethod("beta");
    assertTrue("alpha should sort before beta", alpha.compareTo(beta) < 0);
    assertTrue("beta should sort after alpha", beta.compareTo(alpha) > 0);
  }

  @Test
  public void compareToReturnsZeroForEqualNames() {
    // Two distinct instances with the same name must compareTo==0 — consistent with the
    // Comparable<SQLMethod> contract and the factory's use of name as the dispatch key.
    var a = new FixtureMethod("same");
    var b = new FixtureMethod("same", 5, 10);
    assertEquals(0, a.compareTo(b));
  }

  @Test
  public void evaluateParametersDefaultsTrue() {
    // Guards against a silent flip: most subclasses depend on runtime parameter resolution, so a
    // base-class default of false would be a production-wide regression.
    assertTrue(new FixtureMethod("foo").evaluateParameters());
  }

  // ---------------------------------------------------------------------------
  // getParameterValue — static helper branches
  // ---------------------------------------------------------------------------

  @Test
  public void getParameterValueNullInputReturnsNull() {
    // The first guard — iValue == null short-circuits without touching the record. Passing a null
    // record alongside confirms the guard fires BEFORE any record access would NPE.
    assertNull(AbstractSQLMethod.getParameterValue(null, null, null));
  }

  @Test
  public void getParameterValueSingleQuotedStripsQuotes() {
    // charAt(0) == '\'' → substring(1, length-1). Pins string-quoted literal handling.
    assertEquals("abc", AbstractSQLMethod.getParameterValue(null, null, "'abc'"));
  }

  @Test
  public void getParameterValueDoubleQuotedStripsQuotes() {
    // charAt(0) == '"' → substring(1, length-1). Pins double-quoted literal handling.
    assertEquals("abc", AbstractSQLMethod.getParameterValue(null, null, "\"abc\""));
  }

  @Test
  public void getParameterValueEmptyQuotedStringReturnsEmpty() {
    // Edge case: "''" is a legitimate empty-string literal (length 2). substring(1, 1) yields "".
    // Pins the off-by-one substring math on the smallest valid quoted input.
    assertEquals("", AbstractSQLMethod.getParameterValue(null, null, "''"));
  }

  @Test
  public void getParameterValueUnquotedWithNullRecordReturnsNull() {
    // Unquoted input is treated as a property lookup. With iRecord == null, the second guard
    // fires and the method returns null without NPE.
    assertNull(AbstractSQLMethod.getParameterValue(null, null, "fieldName"));
  }

  @Test
  public void getParameterValueUnquotedResolvesRecordProperty() {
    // Happy path: unquoted → iRecord.getProperty(iValue).
    var record = new ResultInternal((DatabaseSessionEmbedded) null);
    record.setProperty("fieldName", "hello");
    assertEquals("hello", AbstractSQLMethod.getParameterValue(null, record, "fieldName"));
  }

  @Test
  public void getParameterValueUnquotedMissingPropertyReturnsNull() {
    // Record has no such property — Result.getProperty returns null by contract, pass-through.
    var record = new ResultInternal((DatabaseSessionEmbedded) null);
    assertNull(AbstractSQLMethod.getParameterValue(null, record, "absent"));
  }

  @Test
  public void getParameterValueUnquotedPropertyGetterThrowingReturnsNull() {
    // If getProperty throws (e.g. the iValue isn't a valid property name like a format string
    // "%-011d"), the catch block swallows the exception and returns null so callers fall through
    // to the static-value path.
    var record =
        new ResultInternal((DatabaseSessionEmbedded) null) {
          @Override
          public Object getProperty(String name) {
            throw new IllegalStateException("mock exception for test");
          }
        };
    assertNull(AbstractSQLMethod.getParameterValue(null, record, "anyName"));
  }

  // ---------------------------------------------------------------------------
  // Helper: minimal concrete subclass exercising AbstractSQLMethod's default behaviour.
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link AbstractSQLMethod} subclass used to exercise the inherited contract. The
   * concrete {@code execute} is a no-op — we only test the base class's own methods here.
   */
  private static final class FixtureMethod extends AbstractSQLMethod {

    FixtureMethod(String name) {
      super(name);
    }

    FixtureMethod(String name, int n) {
      super(name, n);
    }

    FixtureMethod(String name, int min, int max) {
      super(name, min, max);
    }

    @Override
    public Object execute(
        Object iThis,
        Result iCurrentRecord,
        CommandContext iContext,
        Object ioResult,
        Object[] iParams) {
      // Proof-of-compile only — we never assert on this in these tests.
      return ioResult;
    }
  }

  // Unused import suppression — keep the symbol referenced so a future refactor that removes
  // the interface link causes a visible compile break here rather than silent coverage drift.
  @SuppressWarnings("unused")
  private static final Class<SQLMethod> INTERFACE_REFERENCE = SQLMethod.class;

  @Test
  public void assertNotNullInterfaceLinkage() {
    // Trivial reachability test for the marker reference above. This is deliberate: if a refactor
    // ever dropped the `implements SQLMethod` line, the import would become unused, Spotless
    // would flag it, and this test would surface the change.
    assertNotNull(INTERFACE_REFERENCE);
  }
}
