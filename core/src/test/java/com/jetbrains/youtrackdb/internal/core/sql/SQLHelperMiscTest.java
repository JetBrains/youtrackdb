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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExportException;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the miscellaneous helper methods on {@link SQLHelper} that are not covered by
 * {@link SQLHelperParseValueScalarTest}:
 *
 * <ul>
 *   <li>{@link SQLHelper#getValue(Object)} — the single-argument helper that unwraps
 *       {@link com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItem} values. For a non
 *       filter-item input the input is returned as-is.</li>
 *   <li>{@link SQLHelper#getValue(Object, com.jetbrains.youtrackdb.internal.core.query.Result,
 *       com.jetbrains.youtrackdb.internal.core.command.CommandContext)} — the three-argument
 *       variant, covering the null, numeric-string, quoted-string, and throw branches reachable
 *       without a real Entity.</li>
 *   <li>{@link SQLHelper#getFunction(
 *       com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 *       com.jetbrains.youtrackdb.internal.common.parser.BaseParser, String)} — function-syntax
 *       detection (the positive construction path needs a parser + session and is covered by
 *       Track 6's {@code SQLFunctionRuntimeTest}). Here we pin the no-match returns-null
 *       branches.</li>
 *   <li>The {@link SQLHelper#parseValue(com.jetbrains.youtrackdb.internal.common.parser.BaseParser,
 *       String, com.jetbrains.youtrackdb.internal.core.command.CommandContext)} BaseParser
 *       overloads — the "*" short-circuit, scalar literal dispatch, and positional/named
 *       parameter wrapping. Behaviour that requires an SQLFilterItemField/function is covered in
 *       {@code SQLHelperParseValueCollectionTest} (step 6, DB-backed).</li>
 * </ul>
 *
 * <p>Extends {@link DbTestBase} because {@link SQLHelper#parseValue} eagerly fetches the context's
 * database session. The session is available but none of the tests in this suite construct
 * collections, maps, or embedded entities.
 *
 * <p>The scalar value-dispatch paths (null/not null/defined, booleans, quoted strings, RIDs, and
 * numerics) are covered in depth by {@link SQLHelperParseValueScalarTest}. This suite deliberately
 * avoids duplicating those to keep the tests focused.
 */
public class SQLHelperMiscTest extends DbTestBase {

  private BasicCommandContext ctx;

  @Before
  public void setUpContext() {
    // A session is required because SQLHelper.getValue's String branch consults
    // iContext.getDatabaseSession() for the EntityHelper.getFieldValue call when iRecord is
    // non-null. The tests that exercise the session-less branches still work fine with the
    // non-null session because those branches short-circuit before the session is touched.
    ctx = new BasicCommandContext(session);
  }

  // ---------------------------------------------------------------------------
  // getValue(Object) — single-argument helper
  // ---------------------------------------------------------------------------

  @Test
  public void getValueOneArgNullReturnsNull() {
    // Early null short-circuit — no filter-item dispatch happens.
    assertNull(SQLHelper.getValue(null));
  }

  @Test
  public void getValueOneArgStringPassThrough() {
    // A String is neither null nor an SQLFilterItem — returned identity-preserving.
    var in = "hello";
    assertSame(in, SQLHelper.getValue(in));
  }

  @Test
  public void getValueOneArgIntegerPassThrough() {
    // Non-String, non-filter-item values fall into the default "return iObject" branch.
    var in = Integer.valueOf(42);
    assertSame(in, SQLHelper.getValue(in));
  }

  @Test
  public void getValueOneArgArrayListPassThrough() {
    // Confirm the branch is unconditional for non-filter-item types.
    var in = new ArrayList<String>();
    in.add("x");
    assertSame(in, SQLHelper.getValue(in));
  }

  // ---------------------------------------------------------------------------
  // getValue(Object, Result, CommandContext) — three-argument helper
  // ---------------------------------------------------------------------------

  @Test
  public void getValueThreeArgNullReturnsNull() {
    // The switch's case null → return null. This is a pure dispatch branch.
    assertNull(SQLHelper.getValue(null, null, ctx));
  }

  @Test
  public void getValueThreeArgStringWithNullRecordFallsThrough() {
    // The String case requires (iRecord != null). With a null record, the body is skipped and
    // the outer return iObject delivers the original string.
    var in = "some-field";
    assertSame(in, SQLHelper.getValue(in, null, ctx));
  }

  @Test
  public void getValueThreeArgEmptyStringWithNullRecordFallsThrough() {
    // Empty string likewise skips the body (no iRecord) — pinned because the String branch's
    // condition uses a bitwise "&" instead of the logical "&&", which is a suggestion-severity
    // code smell but here with iRecord=null the behaviour is identical.
    // WHEN-FIXED: replace `iRecord != null & !s.isEmpty()` with `&&` for clarity.
    // Track 22: SQLHelper.getValue three-arg String branch short-circuit.
    assertEquals("", SQLHelper.getValue("", null, ctx));
  }

  @Test
  public void getValueThreeArgIntegerPassThrough() {
    // Integer is neither null, nor SQLFilterItem, nor String — default branch returns iObject.
    var in = Integer.valueOf(7);
    assertSame(in, SQLHelper.getValue(in, null, ctx));
  }

  @Test
  public void getValueThreeArgBooleanPassThrough() {
    // Same as integer: Boolean is a scalar that isn't a filter-item, so the default path kicks in.
    assertSame(Boolean.TRUE, SQLHelper.getValue(Boolean.TRUE, null, ctx));
  }

  @Test
  public void getValueThreeArgFilterItemWithNonEntityThrows() {
    // When the object IS an SQLFilterItem but iRecord is NOT an Entity, SQLHelper throws
    // DatabaseExportException. Pin the exception type — callers (e.g. RecordsReturnHandler)
    // depend on this contract.
    // We pass a non-Entity Result stub (null is also non-Entity, so null works — the pattern
    // match `iRecord instanceof Entity` is false for null).
    var filterItem = new ThrowingSQLFilterItem();
    try {
      SQLHelper.getValue(filterItem, null, ctx);
      fail("expected DatabaseExportException");
    } catch (DatabaseExportException expected) {
      // OK — message content is not contractual so we only check the type.
    }
  }

  // ---------------------------------------------------------------------------
  // getFunction — no-match branches
  // ---------------------------------------------------------------------------

  @Test
  public void getFunctionNoParenthesesReturnsNull() {
    // "field" has no '(' — the outer conditional on beginParenthesis > -1 is false and the
    // method returns null.
    assertNull(SQLHelper.getFunction(null, null, "field"));
  }

  @Test
  public void getFunctionDotBeforeParenthesisReturnsNull() {
    // "obj.foo(" has '.' before '(' → separator < beginParenthesis → the outer guard fails.
    // This is the "method-on-object" path which is handled elsewhere.
    assertNull(SQLHelper.getFunction(null, null, "obj.foo()"));
  }

  @Test
  public void getFunctionStartingWithDigitReturnsNull() {
    // A first-char digit rejects the function match even if parens are present.
    assertNull(SQLHelper.getFunction(null, null, "9foo()"));
  }

  @Test
  public void getFunctionStartingWithSymbolReturnsNull() {
    // The only accepted first-char prefixes are '_' or alphabetic letters. '@' is rejected.
    assertNull(SQLHelper.getFunction(null, null, "@foo()"));
  }

  @Test
  public void getFunctionUnterminatedParenthesisReturnsNull() {
    // Missing ')' → endParenthesis == -1 → falls to the default null return.
    assertNull(SQLHelper.getFunction(null, null, "foo("));
  }

  @Test
  public void getFunctionOpenParenOnlyReturnsNull() {
    // Only '(' with no identifier prefix rejects on first-char check. Note beginParenthesis == 0
    // satisfies the outer guard, but firstChar '(' is neither '_' nor a letter.
    assertNull(SQLHelper.getFunction(null, null, "(foo)"));
  }

  // ---------------------------------------------------------------------------
  // parseValue(BaseParser, String, CommandContext) — overloads
  // ---------------------------------------------------------------------------

  @Test
  public void parseValueBaseParserStarWildcardShortCircuits() {
    // "*" is returned as the literal String "*" regardless of iCommand or context. BaseParser
    // can be null because the wildcard branch fires before any parser access.
    assertEquals("*", SQLHelper.parseValue(
        (com.jetbrains.youtrackdb.internal.common.parser.BaseParser) null, "*", ctx));
  }

  @Test
  public void parseValueBaseParserIntegerLiteralReturnsInt() {
    // Integer literals flow through the scalar parseValue path and bypass both function lookup
    // and field resolution.
    assertEquals(42, SQLHelper.parseValue(
        (com.jetbrains.youtrackdb.internal.common.parser.BaseParser) null, "42", ctx));
  }

  @Test
  public void parseValueBaseParserBooleanLiteralReturnsBoolean() {
    assertEquals(Boolean.TRUE, SQLHelper.parseValue(
        (com.jetbrains.youtrackdb.internal.common.parser.BaseParser) null, "true", ctx));
  }

  @Test
  public void parseValueBaseParserQuotedStringReturnsStripped() {
    // Single-quoted literal is handled by the scalar dispatch — the BaseParser overload never
    // reaches the field-resolution branch that would require a session.
    assertEquals("abc", SQLHelper.parseValue(
        (com.jetbrains.youtrackdb.internal.common.parser.BaseParser) null, "'abc'", ctx));
  }

  @Test
  public void parseValueBaseParserNullLiteralReturnsNull() {
    // "null" keyword routes to the scalar parser which returns a real null reference.
    assertNull(SQLHelper.parseValue(
        (com.jetbrains.youtrackdb.internal.common.parser.BaseParser) null, "null", ctx));
  }

  @Test
  public void parseValueSQLPredicateOverloadParameterPositional() {
    // The 4-arg parseValue(SQLPredicate, BaseParser, String, CommandContext) handles '?' / ':'
    // parameters. With a null SQLPredicate, a positional "?" is wrapped in an
    // SQLFilterItemParameter (no session needed).
    var out = SQLHelper.parseValue(null, null, "?", ctx);
    assertEquals(
        com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemParameter.class,
        out.getClass());
  }

  @Test
  public void parseValueSQLPredicateOverloadParameterNamed() {
    // ':name' is a named parameter — wrapped in the same SQLFilterItemParameter type. The
    // constructor only records the parameter name and does not call back into any session.
    var out = SQLHelper.parseValue(null, null, ":name", ctx);
    assertEquals(
        com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemParameter.class,
        out.getClass());
  }

  // ---------------------------------------------------------------------------
  // Test helpers
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItem}-like stub used
   * to trigger the {@code DatabaseExportException} branch in
   * {@link SQLHelper#getValue(Object, com.jetbrains.youtrackdb.internal.core.query.Result,
   * com.jetbrains.youtrackdb.internal.core.command.CommandContext)}. The type assertion inside
   * that helper uses {@code instanceof SQLFilterItem} — so this no-op stub is sufficient; the
   * filter logic is never reached because iRecord is non-Entity (null here).
   */
  private static final class ThrowingSQLFilterItem
      implements com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItem {

    @Override
    public Object getValue(
        com.jetbrains.youtrackdb.internal.core.query.Result iRecord,
        Object iCurrentResult,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext iContext) {
      // Unreachable from the DatabaseExportException branch — the helper throws before it gets
      // here. Returning a marker keeps the stub complete.
      return "unreachable";
    }
  }
}
