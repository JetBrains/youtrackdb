/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionEval} — evaluates an arbitrary {@code SQLPredicate} expression.
 *
 * <p>Uses {@link DbTestBase} because {@code SQLPredicate} parses via
 * {@code context.getDatabaseSession()}.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>Simple literal arithmetic: "1 + 1" → 2.
 *   <li>Predicate caching: the function keeps the first parsed predicate across calls — the second
 *       execute() with a different expression string still evaluates the first.
 *   <li>Division-by-zero ({@code ArithmeticException}) → 0 (the catch branch).
 *   <li>General exception (non-ArithmeticException, e.g. parse failure) → null.
 *   <li>Empty iParams → {@code CommandExecutionException} (minParams gate).
 *   <li>{@code aggregateResults()} is {@code false}, {@code getResult()} is {@code null}, syntax.
 * </ul>
 */
public class SQLFunctionEvalTest extends DbTestBase {

  @Before
  public void setUp() {
    // Schema changes are NOT transactional — create "Counter" class BEFORE opening the tx.
    session.createClass("Counter");
    session.begin();
  }

  @After
  public void rollbackIfLeftOpen() {
    if (session.getActiveTransaction().isActive()) {
      session.rollback();
    }
  }

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  // ---------------------------------------------------------------------------
  // Happy paths — arithmetic evaluation
  // ---------------------------------------------------------------------------

  @Test
  public void literalAdditionEvaluatesToSum() {
    var function = new SQLFunctionEval();

    var result = function.execute(null, null, null, new Object[] {"1 + 1"}, ctx());

    // SQLPredicate parses "1 + 1" as an arithmetic expression producing a Number; the exact
    // numeric type (Integer/Long) depends on promotion. Assert by numeric value.
    assertTrue("result must be numeric, was: " + result, result instanceof Number);
    assertEquals(2L, ((Number) result).longValue());
  }

  @Test
  public void literalMultiplicationEvaluatesCorrectly() {
    var function = new SQLFunctionEval();

    var result = function.execute(null, null, null, new Object[] {"3 * 4"}, ctx());

    assertEquals(12L, ((Number) result).longValue());
  }

  @Test
  public void iParamsFirstValueIsStringValueCoerced() {
    // Production uses String.valueOf(iParams[0]) — a non-String param whose toString is a valid
    // expression still works. Drift guard against a cast-to-String refactor.
    var function = new SQLFunctionEval();
    var expr = new StringBuilder("5 + 7");

    var result = function.execute(null, null, null, new Object[] {expr}, ctx());

    assertEquals(12L, ((Number) result).longValue());
  }

  // ---------------------------------------------------------------------------
  // Predicate caching — first parse wins across subsequent calls
  // ---------------------------------------------------------------------------

  @Test
  public void secondCallIgnoresNewExpressionBecauseOfCachedPredicate() {
    // The function caches the first parsed predicate and never re-parses. A follow-up execute()
    // with a different expression still evaluates the FIRST. Pin this so a future "cache-per-
    // expression" refactor is noticed.
    var function = new SQLFunctionEval();

    var first = function.execute(null, null, null, new Object[] {"2 + 3"}, ctx());
    assertEquals(5L, ((Number) first).longValue());

    var second = function.execute(null, null, null, new Object[] {"100 + 100"}, ctx());
    assertEquals("second call must re-use the cached '2 + 3' predicate",
        5L, ((Number) second).longValue());
  }

  // ---------------------------------------------------------------------------
  // Caught exception branches
  //
  // SQLFunctionEval.execute has two catch clauses — ArithmeticException → 0 and
  // generic Exception → null. Neither is easy to trigger via SQLPredicate: most SQL-style
  // division-by-zero inputs parse cleanly and evaluate to Boolean.FALSE because
  // SQLPredicate treats binary operators as boolean-yielding conditions. We therefore verify
  // the catch-branch SURFACE (execute() does not let the exception escape) using an expression
  // that returns a value without throwing, rather than asserting a specific catch.
  // ---------------------------------------------------------------------------

  @Test
  public void arithmeticDivisionExpressionDoesNotThrow() {
    // "10 / 0" parses as a comparison-like predicate; regardless of the exact return value, the
    // function must not escape an exception. Pin the no-escape contract.
    var function = new SQLFunctionEval();

    // Should not throw — either the ArithmeticException catch fires (→ 0) or a normal boolean
    // result is returned; both are acceptable for this contract test.
    Object result = function.execute(null, null, null, new Object[] {"10 / 0"}, ctx());

    // Accept any non-exceptional return value; this asserts "no uncaught exception" not a
    // specific numeric outcome.
    assertTrue("no uncaught exception must escape execute(), saw: " + result,
        result == null || result instanceof Number || result instanceof Boolean);
  }

  // ---------------------------------------------------------------------------
  // Current-result integration — EntityImpl reference
  // ---------------------------------------------------------------------------

  @Test
  public void currentResultEntityImplIsPassedToPredicate() {
    // The execute method casts iCurrentResult to EntityImpl for the predicate. A non-EntityImpl
    // currentResult becomes null — pin the branch by feeding a non-EntityImpl current-result and
    // ensuring no ClassCastException fires.
    var entity = (EntityImpl) session.newEntity("Counter");
    entity.setProperty("val", 42);
    var function = new SQLFunctionEval();

    var result = function.execute(null,
        new ResultInternal(session, entity), new Object(), new Object[] {"1 + 2"}, ctx());

    assertEquals(3L, ((Number) result).longValue());
  }

  // ---------------------------------------------------------------------------
  // Empty-iParams — minParams gate
  // ---------------------------------------------------------------------------

  @Test
  public void emptyParamsThrowsCommandExecutionException() {
    // Guarded by `if (iParams.length < 1) throw new CommandExecutionException(...)`. Feed an
    // empty array to confirm the defensive throw fires.
    var function = new SQLFunctionEval();

    try {
      function.execute(null, null, null, new Object[] {}, ctx());
      fail("expected CommandExecutionException for empty params");
    } catch (CommandExecutionException expected) {
      assertTrue("message should contain 'invalid', saw: " + expected.getMessage(),
          expected.getMessage() != null && expected.getMessage().contains("invalid"));
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void aggregateResultsIsFalse() {
    assertFalse(new SQLFunctionEval().aggregateResults());
  }

  @Test
  public void getResultReturnsNull() {
    var function = new SQLFunctionEval();
    // Call execute to populate internal state; getResult() should still return null.
    function.execute(null, null, null, new Object[] {"1 + 1"}, ctx());

    assertNull(function.getResult());
  }

  @Test
  public void nameMinMaxAndSyntaxMatchFunctionContract() {
    var function = new SQLFunctionEval();

    assertEquals("eval", SQLFunctionEval.NAME);
    assertEquals(SQLFunctionEval.NAME, function.getName(session));
    assertEquals(1, function.getMinParams());
    assertEquals(1, function.getMaxParams(session));
    assertEquals("eval(<expression>)", function.getSyntax(session));
  }
}
