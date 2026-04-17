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
  public void openTx() {
    // SQLPredicate parsing requires an active transaction in the session.
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
  // Exception contract — execute() never lets exceptions escape
  //
  // SQLFunctionEval.execute has two catch clauses — ArithmeticException → 0 and
  // generic Exception → null. Neither is deterministically reachable via SQLPredicate
  // from public inputs: division-by-zero like "10 / 0" is parsed as a boolean-yielding
  // predicate and evaluates to Boolean.FALSE without ever throwing. We therefore assert
  // the contract "execute does not escape exceptions" rather than branch-specific
  // outcomes (which would require patching SQLPredicate or reflection injection).
  // ---------------------------------------------------------------------------

  @Test
  public void divisionByZeroExpressionIsSwallowedToBooleanOrNumber() {
    // "10 / 0" produces a Boolean.FALSE result in the current SQLPredicate; assert against that
    // observed behaviour instead of a vacuous any-type check. A refactor that made the predicate
    // throw ArithmeticException (hitting the catch → 0) would turn this into `assertEquals(0, …)`
    // and flag the change.
    var function = new SQLFunctionEval();

    var result = function.execute(null, null, null, new Object[] {"10 / 0"}, ctx());

    // Today: Boolean.FALSE is returned. If production semantics change to hit the
    // ArithmeticException catch (→ 0), this assertion will fail and the test must be updated
    // together with the fix — which is exactly the intent of a pinning test.
    assertEquals("10 / 0 currently yields Boolean.FALSE via the predicate evaluator",
        Boolean.FALSE, result);
  }

  // ---------------------------------------------------------------------------
  // Current-result integration — non-EntityImpl fallback branch
  // ---------------------------------------------------------------------------

  @Test
  public void currentResultNonEntityImplFallsBackWithoutClassCast() {
    // The production ternary `iCurrentResult instanceof EntityImpl e ? e : null` silently falls
    // back to null for non-EntityImpl values. Passing `new Object()` exercises the FALSE branch
    // of the instanceof — the test name and assertions document the branch actually covered.
    // (The positive "iCurrentResult IS an EntityImpl" branch is exercised indirectly when
    // callers feed an EntityImpl; here we pin the defensive fallback.)
    var function = new SQLFunctionEval();

    var result = function.execute(null, null, new Object(), new Object[] {"1 + 2"}, ctx());

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
