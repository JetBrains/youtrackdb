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
import com.jetbrains.youtrackdb.internal.common.parser.BaseParser;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExportException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemParameter;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the miscellaneous helper methods on {@link SQLHelper} that are not covered by
 * {@link SQLHelperParseValueScalarTest}:
 *
 * <ul>
 *   <li>{@link SQLHelper#getValue(Object)} — the single-argument helper that unwraps
 *       {@link SQLFilterItem} values. For a non filter-item input the input is returned as-is.
 *       </li>
 *   <li>{@link SQLHelper#getValue(Object, Result, CommandContext)} — the three-argument variant,
 *       covering the null, filter-item (throws on non-Entity), numeric-string, quoted-string,
 *       and field-resolution branches.</li>
 *   <li>{@link SQLHelper#getFunction(
 *       com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded, BaseParser, String)} —
 *       function-syntax detection. The positive construction path is covered by Track 6's
 *       {@code SQLFunctionRuntimeTest}; here we pin both the no-match branches and the
 *       underscore-prefix positive branch.</li>
 *   <li>The {@link SQLHelper#parseValue(BaseParser, String, CommandContext)} BaseParser overloads
 *       — the "*" short-circuit, scalar literal dispatch, and positional/named parameter
 *       wrapping. Behaviour that requires an SQLFilterItemField/function is covered in
 *       {@code SQLHelperParseValueCollectionTest} (step 6, DB-backed).</li>
 * </ul>
 *
 * <p>Extends {@link DbTestBase} because {@link SQLHelper#parseValue} eagerly fetches the context's
 * database session. The session is available but none of the tests in this suite construct
 * collections, maps, or embedded entities (those are in step 6's companion suite).
 *
 * <p>The scalar value-dispatch paths (null/not null/defined, booleans, quoted strings, RIDs, and
 * numerics) are covered in depth by {@link SQLHelperParseValueScalarTest}. This suite deliberately
 * avoids duplicating those to keep the tests focused.
 */
public class SQLHelperMiscTest extends DbTestBase {

  private BasicCommandContext ctx;

  @Before
  public void setUpContext() {
    // A session is required because SQLHelper.parseValue eagerly calls getDatabaseSession(). The
    // tests that exercise the session-less branches still work with the non-null session because
    // those branches short-circuit before the session is consulted.
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
    // the outer return iObject delivers the original string identity-preserving.
    var in = "some-field";
    assertSame(in, SQLHelper.getValue(in, null, ctx));
  }

  @Test
  public void getValueThreeArgEmptyStringWithNullRecordFallsThrough() {
    // Empty string likewise skips the body (no iRecord). Pin with assertSame — the empty String
    // literal must be returned identity-preserving.
    // WHEN-FIXED (Track 22): replace `iRecord != null & !s.isEmpty()` with `&&` for clarity.
    // Track 22: SQLHelper.getValue three-arg String branch short-circuit.
    var empty = "";
    assertSame(empty, SQLHelper.getValue(empty, null, ctx));
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
    // DatabaseExportException with the exact contract message. Callers (e.g. RecordsReturnHandler)
    // depend on this contract.
    var filterItem = new NonEntityTriggeringFilterItem();
    try {
      SQLHelper.getValue(filterItem, null, ctx);
      fail("expected DatabaseExportException");
    } catch (DatabaseExportException expected) {
      assertEquals("Record is not an entity", expected.getMessage());
    }
  }

  @Test
  public void getValueThreeArgFieldNameResolvesAgainstEntityResult() {
    // The 3-arg getValue's String branch is the live field-resolution path: when iRecord is an
    // Entity and the String is a plain identifier (not quoted, not digit-leading), it routes
    // through EntityHelper.getFieldValue to look up the named property. session.newEntity /
    // setProperty require an active transaction — wrap with begin/rollback.
    session.begin();
    try {
      var entity = session.newEntity();
      entity.setProperty("foo", "bar");
      assertEquals("bar", SQLHelper.getValue("foo", entity, ctx));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getValueThreeArgQuotedStringFallsThroughEvenWithEntity() {
    // With iRecord present but the String is quoted ('x' or "x"), IOUtils.isStringContent returns
    // true → the body is skipped and the raw quoted string is returned. This pins that quoted
    // literals bypass field resolution.
    session.begin();
    try {
      var entity = session.newEntity();
      entity.setProperty("foo", "bar");
      var quoted = "'foo'";
      assertSame(quoted, SQLHelper.getValue(quoted, entity, ctx));
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getValueThreeArgDigitLeadingStringFallsThroughEvenWithEntity() {
    // Strings that start with a digit are treated as numeric literals (not field names) and the
    // body skips field resolution. YouTrackDB rejects property names starting with a digit, so
    // we cannot actually set a "7field" property on the entity — but the getValue code path
    // SHOULD bypass any property lookup attempt entirely because of the digit-check at
    // Character.isDigit(s.charAt(0)). The test is a pure dispatch pin: assertSame confirms the
    // input string is returned identity-preserving WITHOUT any property-access attempt.
    session.begin();
    try {
      var entity = session.newEntity();
      entity.setProperty("foo", "unused");
      var digit = "7field";
      assertSame(digit, SQLHelper.getValue(digit, entity, ctx));
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // getFunction — no-match branches + underscore-prefix positive
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

  @Test
  public void getFunctionUnderscorePrefixedNameEntersConstructionBranch() {
    // Positive path for the `'_' || isLetter` OR expression: underscore-prefixed identifiers are
    // recognised as function calls. The SQLFunctionRuntime ctor then attempts to resolve the
    // function by name via SQLEngine — since "_nonexistent" is not registered, construction
    // throws a CommandSQLParsingException. Symbol-prefixed names (e.g. "@foo()") are rejected
    // BEFORE construction and return null silently; the throw is evidence that the '_' branch
    // fired and construction was attempted.
    var name = "_nonexistent_function_" + java.util.UUID.randomUUID().toString().replace("-", "");
    try {
      SQLHelper.getFunction(session, null, name + "()");
      fail("expected CommandSQLParsingException because the function name is not registered");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException expected) {
      // OK — the '_' branch dispatched to SQLFunctionRuntime construction, which then failed
      // because the function is unknown. If getFunction had instead returned null (i.e. the
      // '_' branch was broken), this fail() would execute.
    }
  }

  // ---------------------------------------------------------------------------
  // parseValue(BaseParser, String, CommandContext) — overloads
  // ---------------------------------------------------------------------------

  @Test
  public void parseValueBaseParserStarWildcardShortCircuits() {
    // "*" is returned as the literal String "*" regardless of iCommand or context. BaseParser
    // can be null because the wildcard branch fires before any parser access.
    assertEquals("*", SQLHelper.parseValue((BaseParser) null, "*", ctx));
  }

  @Test
  public void parseValueBaseParserIntegerLiteralReturnsInt() {
    // Integer literals flow through the scalar parseValue path and bypass both function lookup
    // and field resolution.
    assertEquals(42, SQLHelper.parseValue((BaseParser) null, "42", ctx));
  }

  @Test
  public void parseValueBaseParserBooleanLiteralReturnsBoolean() {
    assertEquals(Boolean.TRUE, SQLHelper.parseValue((BaseParser) null, "true", ctx));
  }

  @Test
  public void parseValueBaseParserQuotedStringReturnsStripped() {
    // Single-quoted literal is handled by the scalar dispatch — the BaseParser overload never
    // reaches the field-resolution branch that would require a session.
    assertEquals("abc", SQLHelper.parseValue((BaseParser) null, "'abc'", ctx));
  }

  @Test
  public void parseValueBaseParserNullLiteralReturnsNull() {
    // "null" keyword routes to the scalar parser which returns a real null reference.
    assertNull(SQLHelper.parseValue((BaseParser) null, "null", ctx));
  }

  @Test
  public void parseValueSQLPredicateOverloadParameterPositional() {
    // The 4-arg parseValue(SQLPredicate, BaseParser, String, CommandContext) handles '?' / ':'
    // parameters. With a null SQLPredicate, a positional "?" is wrapped in an
    // SQLFilterItemParameter (no session needed).
    var out = SQLHelper.parseValue(null, null, "?", ctx);
    assertEquals(SQLFilterItemParameter.class, out.getClass());
  }

  @Test
  public void parseValueSQLPredicateOverloadParameterNamed() {
    // ':name' is a named parameter — wrapped in the same SQLFilterItemParameter type. The
    // constructor only records the parameter name and does not call back into any session.
    var out = SQLHelper.parseValue(null, null, ":name", ctx);
    assertEquals(SQLFilterItemParameter.class, out.getClass());
  }

  // ---------------------------------------------------------------------------
  // Test helpers
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link SQLFilterItem} implementation used to trigger the {@code DatabaseExportException}
   * branch in {@link SQLHelper#getValue(Object, Result, CommandContext)}. The helper throws
   * BEFORE calling this stub's {@code getValue} because iRecord is non-Entity (null) — so the
   * stub method body is unreachable. Named "NonEntityTriggering" because the stub itself does
   * nothing; the exception comes from the helper's type-check failing.
   *
   * <p>Kept as a separate inner class (rather than shared across the test module) because
   * {@link RuntimeResultTest}'s {@code RecordingFunction} stub covers a different contract
   * (SQLFunction, not SQLFilterItem) and merging them would obscure each test's intent.
   */
  private static final class NonEntityTriggeringFilterItem implements SQLFilterItem {

    @Override
    public Object getValue(Result iRecord, Object iCurrentResult, CommandContext iContext) {
      // Unreachable — the caller throws DatabaseExportException before this is invoked.
      return "unreachable";
    }
  }
}
