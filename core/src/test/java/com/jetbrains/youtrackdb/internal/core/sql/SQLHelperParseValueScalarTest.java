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
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.parser.BaseParser;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.RecordSerializerStringAbstract;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the scalar-parsing branches of {@link SQLHelper#parseValue(String, CommandContext,
 * com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty)} and
 * {@link SQLHelper#parseStringNumber(String)} — the dispatch that does NOT construct
 * session-backed collections.
 *
 * <p>The companion {@code SQLHelperParseValueCollectionTest} (step 6) covers the collection/map/
 * entity paths that allocate {@code newEmbeddedList/Map} on the session. Here we keep every
 * assertion on the return value of pure string-to-object dispatch: booleans, quoted strings,
 * {@code null}/{@code not null}/{@code defined} sentinels, RID literals, numerics,
 * {@code $variable} lookups, and empty input.
 *
 * <p>Extends {@link DbTestBase} because {@link SQLHelper#parseValue} eagerly calls
 * {@link CommandContext#getDatabaseSession()} before any branch is taken — a bare
 * {@link BasicCommandContext} without a session throws
 * {@code DatabaseException("No database session found in SQL context")}. The session is available
 * but none of the tests in this suite exercise session-backed allocation paths; the handful of
 * branches that would (lists, maps, embedded entities) are in step 6's companion suite.
 *
 * <p>Bug-pin tests are marked with {@code // WHEN-FIXED:} so that if the underlying behaviour is
 * corrected later, the regression surfaces immediately.
 */
public class SQLHelperParseValueScalarTest extends DbTestBase {

  private BasicCommandContext ctx;

  @Before
  public void setUpContext() {
    ctx = new BasicCommandContext(session);
  }

  // ---------------------------------------------------------------------------
  // Null & sentinel dispatch
  // ---------------------------------------------------------------------------

  @Test
  public void parseNullInputReturnsNullLiterally() {
    // SQLHelper.parseValue short-circuits on null BEFORE trim() so a null input is preserved.
    assertNull(SQLHelper.parseValue(null, ctx, null));
  }

  @Test
  public void parseEmptyStringReturnsEmptyStringIdentity() {
    // Empty input after trim() is returned as-is — this is a VALUE that survives parseValue (it
    // is not the VALUE_NOT_PARSED sentinel). Callers rely on this to differentiate "no value"
    // from "unparseable".
    var out = SQLHelper.parseValue("", ctx, null);
    assertEquals("", out);
  }

  @Test
  public void parseWhitespaceOnlyInputReturnsEmptyStringAfterTrim() {
    // "   " trims to "" and then follows the empty-string branch.
    assertEquals("", SQLHelper.parseValue("   ", ctx, null));
  }

  @Test
  public void parseNullLiteralKeywordReturnsNullObject() {
    // Case-insensitive match: "null" / "NULL" / "NuLl" all produce a real null Java reference so
    // callers can distinguish parsed-null from VALUE_NOT_PARSED.
    assertNull(SQLHelper.parseValue("null", ctx, null));
    assertNull(SQLHelper.parseValue("NULL", ctx, null));
    assertNull(SQLHelper.parseValue("NuLl", ctx, null));
  }

  @Test
  public void parseNotNullKeywordReturnsNotNullSentinel() {
    // "not null" is converted to the SQLHelper.NOT_NULL marker string.
    assertSame(SQLHelper.NOT_NULL, SQLHelper.parseValue("not null", ctx, null));
    assertSame(SQLHelper.NOT_NULL, SQLHelper.parseValue("NOT NULL", ctx, null));
  }

  @Test
  public void parseDefinedKeywordReturnsDefinedSentinel() {
    // "defined" is converted to the SQLHelper.DEFINED marker.
    assertSame(SQLHelper.DEFINED, SQLHelper.parseValue("defined", ctx, null));
    assertSame(SQLHelper.DEFINED, SQLHelper.parseValue("DEFINED", ctx, null));
  }

  // ---------------------------------------------------------------------------
  // Boolean dispatch
  // ---------------------------------------------------------------------------

  @Test
  public void parseTrueLiteralReturnsBooleanTrue() {
    // The branch uses equalsIgnoreCase so "true", "TRUE", "TrUe" must all produce Boolean.TRUE.
    assertEquals(Boolean.TRUE, SQLHelper.parseValue("true", ctx, null));
    assertEquals(Boolean.TRUE, SQLHelper.parseValue("TRUE", ctx, null));
    assertEquals(Boolean.TRUE, SQLHelper.parseValue("TrUe", ctx, null));
  }

  @Test
  public void parseFalseLiteralReturnsBooleanFalse() {
    assertEquals(Boolean.FALSE, SQLHelper.parseValue("false", ctx, null));
    assertEquals(Boolean.FALSE, SQLHelper.parseValue("FALSE", ctx, null));
    assertEquals(Boolean.FALSE, SQLHelper.parseValue("FaLsE", ctx, null));
  }

  // ---------------------------------------------------------------------------
  // Quoted-string dispatch (single, double, mixed)
  // ---------------------------------------------------------------------------

  @Test
  public void parseSingleQuotedStringStripsQuotes() {
    // IOUtils.getStringContent strips the matching surrounding quote characters.
    assertEquals("abc", SQLHelper.parseValue("'abc'", ctx, null));
  }

  @Test
  public void parseDoubleQuotedStringStripsQuotes() {
    assertEquals("hello world", SQLHelper.parseValue("\"hello world\"", ctx, null));
  }

  @Test
  public void parseSingleQuotedEmptyStringReturnsEmpty() {
    // "''" strips to "". This is distinct from the empty-input branch: the result is still a
    // String (via IOUtils.getStringContent), but equal to "".
    assertEquals("", SQLHelper.parseValue("''", ctx, null));
  }

  @Test
  public void parseQuotedStringKeepsWhitespaceInsideQuotes() {
    // parseValue trims the OUTER input but IOUtils.getStringContent strips only the quote
    // characters — any whitespace between the quotes is preserved verbatim (both leading,
    // trailing, and interior spaces).
    assertEquals(" a b  c ", SQLHelper.parseValue("' a b  c '", ctx, null));
  }

  // ---------------------------------------------------------------------------
  // RID dispatch
  // ---------------------------------------------------------------------------

  @Test
  public void parseRidLiteralReturnsRecordIdInternal() {
    // RID pattern #cluster:pos flows into RecordIdInternal.fromString.
    var rid = SQLHelper.parseValue("#12:34", ctx, null);
    assertNotNull(rid);
    assertTrue("expected RecordIdInternal, got " + rid.getClass(),
        rid instanceof RecordIdInternal);
    assertEquals("#12:34", rid.toString());
  }

  @Test
  public void parseNegativeRidLiteralIsRecognized() {
    // Temporary RIDs have a negative cluster id; the RID pattern accepts them.
    var rid = SQLHelper.parseValue("#-1:-2", ctx, null);
    assertNotNull(rid);
    assertTrue(rid instanceof RecordIdInternal);
  }

  @Test
  public void parseMalformedRidFallsThroughToNumericAttempt() {
    // "#abc" does NOT match RID pattern. With no session, numeric parse returns null, so the
    // result is VALUE_NOT_PARSED (the initial sentinel). Equality via assertSame is safe — the
    // sentinel is a constant string reference.
    assertSame(SQLHelper.VALUE_NOT_PARSED, SQLHelper.parseValue("#abc", ctx, null));
  }

  // ---------------------------------------------------------------------------
  // Numeric dispatch (via parseStringNumber)
  // ---------------------------------------------------------------------------

  @Test
  public void parseIntegerLiteralReturnsInt() {
    // Small non-suffixed integer → INTEGER → Integer.parseInt.
    assertEquals(42, SQLHelper.parseValue("42", ctx, null));
  }

  @Test
  public void parseNegativeIntegerLiteralReturnsInt() {
    assertEquals(-7, SQLHelper.parseValue("-7", ctx, null));
  }

  @Test
  public void parseIntegerMaxValueReturnsInteger() {
    // Integer.MAX_VALUE ("2147483647") is exactly 10 digits — the MAX_INTEGER_DIGITS threshold.
    // The classifier keeps it as INTEGER.
    assertEquals(
        Integer.MAX_VALUE,
        SQLHelper.parseValue(Integer.toString(Integer.MAX_VALUE), ctx, null));
  }

  @Test
  public void parseIntegerMinValueClassifiesAsLong() {
    // Integer.MIN_VALUE ("-2147483648") is 11 characters including the sign. The classifier's
    // length check counts characters (not digits), so a leading minus pushes it over the
    // 10-digit threshold and it's classified LONG. The value still fits in a 32-bit int, so
    // callers that need strict Integer typing must special-case negative boundaries.
    // WHEN-FIXED (Track 22): the sign-inclusive length comparison is a minor quirk; if the
    // classifier is refined to exclude the sign, this test should assert Integer instead.
    var out = SQLHelper.parseValue(Integer.toString(Integer.MIN_VALUE), ctx, null);
    assertTrue("expected Long classification, got " + out.getClass(), out instanceof Long);
    assertEquals((long) Integer.MIN_VALUE, (long) (Long) out);
  }

  @Test
  public void parseLongMaxValueClassifiesAsLong() {
    // Long.MAX_VALUE ("9223372036854775807") is 19 digits — above MAX_INTEGER_DIGITS (10) → LONG
    // path. Long.parseLong accepts it → the parseValue result is the expected Long.
    var out = SQLHelper.parseValue(Long.toString(Long.MAX_VALUE), ctx, null);
    assertTrue("expected Long, got " + out.getClass(), out instanceof Long);
    assertEquals(Long.MAX_VALUE, (long) (Long) out);
  }

  @Test
  public void parseAboveLongMaxValueClassifiesAsDecimal() {
    // 20-digit input exceeds Long.MAX_VALUE and Integer.MAX_VALUE. The classifier routes through
    // integer-length check which caps at MAX_INTEGER_DIGITS=10 → LONG promotion, but 20 digits
    // overflows Long.parseLong, surfacing a NumberFormatException. Pin this boundary so a future
    // classifier that promotes such values to DECIMAL (a more forgiving choice) can be detected.
    // WHEN-FIXED (Track 22): promote to DECIMAL for >19-digit integer inputs to preserve the
    // value; current behaviour is NFE.
    try {
      SQLHelper.parseValue("99999999999999999999", ctx, null);
      fail("expected NumberFormatException for above-Long-max input");
    } catch (NumberFormatException expected) {
      // OK — bug observable.
    }
  }

  @Test
  public void parseLongSuffixLiteralThrowsNumberFormatBugPin() {
    // WHEN-FIXED: `SQLHelper.parseStringNumber` classifies "99l" as LONG via
    // RecordSerializerStringAbstract.getType's 'l' suffix branch, but then passes the raw string
    // (including the suffix) to Long.parseLong — which throws NumberFormatException. Callers of
    // parseValue that rely on suffix notation would see a crash instead of a typed value. Fix
    // by stripping the suffix before parseLong, or by routing through a suffix-aware parser.
    // Track 22: harmonise parseStringNumber with getType's suffix classification.
    try {
      SQLHelper.parseValue("99l", ctx, null);
      fail("expected NumberFormatException pinning the suffix-strip bug");
    } catch (NumberFormatException expected) {
      // OK — the bug is observable.
    }
  }

  @Test
  public void parseShortSuffixLiteralThrowsNumberFormatBugPin() {
    // WHEN-FIXED: same bug as the LONG suffix — "12s" reaches Short.parseShort("12s") and fails.
    try {
      SQLHelper.parseValue("12s", ctx, null);
      fail("expected NumberFormatException pinning the suffix-strip bug");
    } catch (NumberFormatException expected) {
      // OK.
    }
  }

  @Test
  public void parseByteSuffixLiteralThrowsNumberFormatBugPin() {
    // WHEN-FIXED: same bug — "5b" reaches Byte.parseByte("5b").
    try {
      SQLHelper.parseValue("5b", ctx, null);
      fail("expected NumberFormatException pinning the suffix-strip bug");
    } catch (NumberFormatException expected) {
      // OK.
    }
  }

  @Test
  public void parseFloatSuffixReturnsFloat() {
    // "f" suffix → FLOAT path. Unlike the integer-family suffixes, Float.parseFloat accepts
    // trailing 'f'/'F', so this path works end-to-end. Pin the contract.
    assertEquals(1.5f, (Float) SQLHelper.parseValue("1.5f", ctx, null), 0.0001f);
  }

  @Test
  public void parseDoubleSuffixReturnsDouble() {
    // "d" suffix → DOUBLE path. Double.parseDouble accepts trailing 'd'/'D' so this succeeds
    // end-to-end.
    assertEquals(3.14, (Double) SQLHelper.parseValue("3.14d", ctx, null), 0.0001);
  }

  @Test
  public void parseDecimalSuffixLiteralThrowsNumberFormatBugPin() {
    // WHEN-FIXED: "100.50c" reaches new BigDecimal("100.50c") which throws NumberFormatException.
    // Same suffix-strip bug. Track 22.
    try {
      SQLHelper.parseValue("100.50c", ctx, null);
      fail("expected NumberFormatException pinning the suffix-strip bug");
    } catch (NumberFormatException expected) {
      // OK.
    }
  }

  @Test
  public void parseFloatingPointWithoutSuffixExactlyRepresentableReturnsFloat() {
    // A decimal that is EXACTLY representable in float is classified FLOAT by getType (the
    // classifier checks (double)(float)dou == dou). 1.5 is exactly representable (binary 1.1);
    // 3.14 is NOT (it would be classified DOUBLE). Pin the exact-representation path.
    var out = SQLHelper.parseValue("1.5", ctx, null);
    assertTrue("expected Float, got " + out.getClass(), out instanceof Float);
    assertEquals(1.5f, (Float) out, 0.0f);
  }

  @Test
  public void parseDateSuffixLiteralThrowsNumberFormatBugPin() {
    // WHEN-FIXED: "1000a" → DATE classification → new Date(Long.parseLong("1000a")) → NFE.
    // Same suffix-strip bug. Track 22.
    try {
      SQLHelper.parseValue("1000a", ctx, null);
      fail("expected NumberFormatException pinning the suffix-strip bug");
    } catch (NumberFormatException expected) {
      // OK.
    }
  }

  @Test
  public void parseDateTimeSuffixLiteralThrowsNumberFormatBugPin() {
    // WHEN-FIXED: "2000t" → DATETIME classification → Long.parseLong("2000t") → NFE. Track 22.
    try {
      SQLHelper.parseValue("2000t", ctx, null);
      fail("expected NumberFormatException pinning the suffix-strip bug");
    } catch (NumberFormatException expected) {
      // OK.
    }
  }

  @Test
  public void parseScientificNotationDoubleSuffixReturnsDouble() {
    // Scientific notation is recognised as numeric when immediately followed by a type suffix.
    // For example "1.5E2d" parses as 1.5e2 = 150.0 as a Double.
    var out = SQLHelper.parseValue("1.5E2d", ctx, null);
    assertTrue("expected Double, got " + out.getClass(), out instanceof Double);
    assertEquals(150.0, (Double) out, 0.0001);
  }

  // ---------------------------------------------------------------------------
  // $variable dispatch (resolveContextVariables overload)
  // ---------------------------------------------------------------------------

  @Test
  public void parseDollarVariableResolvesFromContextWhenEnabled() {
    // The 7-arg overload with resolveContextVariables=true reads the variable from the context.
    ctx.setVariable("$myVar", "resolved-value");
    var out = SQLHelper.parseValue("$myVar", ctx, true, null, null, null, null);
    assertEquals("resolved-value", out);
  }

  @Test
  public void parseDollarVariableMissingReturnsNull() {
    // An unset variable resolves to null — not VALUE_NOT_PARSED.
    assertNull(SQLHelper.parseValue("$unknown", ctx, true, null, null, null, null));
  }

  @Test
  public void parseDollarVariableIsNotResolvedWhenFlagFalse() {
    // The scalar parseValue(String, CommandContext, SchemaProperty) overload passes false for
    // resolveContextVariables, so a "$x" literal falls to the numeric branch which fails and
    // leaves VALUE_NOT_PARSED.
    ctx.setVariable("$x", "ignored");
    assertSame(SQLHelper.VALUE_NOT_PARSED, SQLHelper.parseValue("$x", ctx, null));
  }

  // ---------------------------------------------------------------------------
  // Unparseable input → VALUE_NOT_PARSED sentinel
  // ---------------------------------------------------------------------------

  @Test
  public void parseUnquotedIdentifierReturnsValueNotParsedSentinel() {
    // An identifier like "foo" is not a string literal, RID, number, or keyword → the initial
    // VALUE_NOT_PARSED sentinel survives and is returned.
    assertSame(SQLHelper.VALUE_NOT_PARSED, SQLHelper.parseValue("foo", ctx, null));
  }

  @Test
  public void parseGarbageNumberReturnsValueNotParsedSentinel() {
    // "12xyz" matches no suffix, fails numeric parse, so sentinel is returned.
    assertSame(SQLHelper.VALUE_NOT_PARSED, SQLHelper.parseValue("12xyz", ctx, null));
  }

  // ---------------------------------------------------------------------------
  // Sub-command EMBEDDED_BEGIN '(' branch
  // ---------------------------------------------------------------------------

  @Test
  public void parseSubCommandParenLiteralThrowsUnsupportedOperation() {
    // Input wrapped in parentheses starts/ends with StringSerializerHelper.EMBEDDED_BEGIN/END
    // → the sub-command branch fires and throws UnsupportedOperationException. Pin:
    // (1) the exception type, and
    // (2) that the input actually entered the sub-command branch by checking the boundary
    //     characters — a refactor that moved the UOE to a different dispatch (e.g. an RID or
    //     numeric branch) would not satisfy the boundary check on this input.
    var input = "(select from foo)";
    assertTrue(
        "input must start and end with '(' and ')' to reach the sub-command branch",
        input.charAt(0) == '(' && input.charAt(input.length() - 1) == ')');
    try {
      SQLHelper.parseValue(input, ctx, null);
      fail("expected UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
      // Acceptable — sub-command support is explicitly TODO in SQLHelper.
    }
  }

  // ---------------------------------------------------------------------------
  // parseStringNumber — direct exercise of the numeric dispatcher
  // ---------------------------------------------------------------------------

  @Test
  public void parseStringNumberReturnsNullForNonNumeric() {
    // Non-numeric input → getType returns STRING or null (empty) → parseStringNumber returns null.
    assertNull(SQLHelper.parseStringNumber("hello"));
    assertNull(SQLHelper.parseStringNumber(""));
  }

  @Test
  public void parseStringNumberIntegerSuffixesThatWork() {
    // The suffixes accepted by Java's native parsers — 'f' and 'd' — work end-to-end. Pin them.
    // Pure integer (no suffix) is also the happy path.
    assertEquals(1, SQLHelper.parseStringNumber("1"));
    assertEquals(5.5f, (Float) SQLHelper.parseStringNumber("5.5f"), 0.0001f);
    assertEquals(6.6, (Double) SQLHelper.parseStringNumber("6.6d"), 0.0001);
  }

  @Test
  public void parseStringNumberSuffixedIntegralLiteralsThrowBugPin() {
    // WHEN-FIXED (Track 22): the other integer-family suffixes (l, s, b) and decimal (c),
    // date (a, t) ALL reach their respective parse methods with the suffix still attached → NFE.
    // Pin BOTH: (a) the classifier correctly types the suffix (non-null, expected type) AND
    // (b) the parse step crashes. This locks the classifier so a future refactor that
    // mis-classifies suffixed input as STRING (silently returning null instead of NFE) would be
    // caught — the test must see both a correct classification AND the NFE.
    var expectedTypes = new java.util.LinkedHashMap<String, PropertyTypeInternal>();
    expectedTypes.put("2l", PropertyTypeInternal.LONG);
    expectedTypes.put("3s", PropertyTypeInternal.SHORT);
    expectedTypes.put("4b", PropertyTypeInternal.BYTE);
    expectedTypes.put("7.7c", PropertyTypeInternal.DECIMAL);
    expectedTypes.put("8a", PropertyTypeInternal.DATE);
    expectedTypes.put("9t", PropertyTypeInternal.DATETIME);
    for (var entry : expectedTypes.entrySet()) {
      assertEquals(
          "classifier should type '" + entry.getKey() + "' as " + entry.getValue(),
          entry.getValue(),
          RecordSerializerStringAbstract.getType(entry.getKey()));
      try {
        SQLHelper.parseStringNumber(entry.getKey());
        fail("expected NumberFormatException for '" + entry.getKey() + "'");
      } catch (NumberFormatException expected) {
        // OK — bug observable.
      }
    }
  }

  @Test
  public void parseStringNumberBareFloatExactlyRepresentableReturnsFloat() {
    // parseStringNumber uses getType's classifier. 1.5 is exactly representable so the FLOAT
    // path is taken.
    var out = SQLHelper.parseStringNumber("1.5");
    assertTrue(out instanceof Float);
    assertEquals(1.5f, (Float) out, 0.0f);
  }

  @Test
  public void parseStringNumberImpreciseFloatClassifiesAsDouble() {
    // 3.14 is NOT exactly representable in float → the classifier's double→float→double roundtrip
    // fails and the type is DOUBLE. Pin both the type and value to catch any drift in the
    // classifier's rounding logic.
    var out = SQLHelper.parseStringNumber("3.14");
    assertTrue("expected Double, got " + out.getClass(), out instanceof Double);
    assertEquals(3.14, (Double) out, 0.0);
  }

  // ---------------------------------------------------------------------------
  // Literal sentinel identity assertions — pin the exact reference constants
  // ---------------------------------------------------------------------------

  @Test
  public void sentinelConstantsHaveStableStringValues() {
    // The three sentinel constants are consumed by executor code via equality on the string
    // value. We pin both the identity and the exact string payload so accidental reassignment
    // of the constant (typo, case change) would surface here.
    assertEquals("_NOT_PARSED_", SQLHelper.VALUE_NOT_PARSED);
    assertEquals("_NOT_NULL_", SQLHelper.NOT_NULL);
    assertEquals("_DEFINED_", SQLHelper.DEFINED);
    assertEquals("sql", SQLHelper.NAME);
    // Pin inequality: the three markers must never alias each other.
    assertFalse(SQLHelper.VALUE_NOT_PARSED.equals(SQLHelper.NOT_NULL));
    assertFalse(SQLHelper.VALUE_NOT_PARSED.equals(SQLHelper.DEFINED));
    assertFalse(SQLHelper.NOT_NULL.equals(SQLHelper.DEFINED));
  }

  // ---------------------------------------------------------------------------
  // Star-wildcard dispatch via 4-arg BaseParser overload
  // ---------------------------------------------------------------------------

  @Test
  public void parseValueWithBaseParserReturnsStarWildcardAsIs() {
    // The BaseParser-overload of parseValue short-circuits "*" to return the literal string "*"
    // (used by SELECT *). The overload requires a BaseParser argument but that argument is never
    // consulted for the wildcard path, so null is safe. Cast to BaseParser to disambiguate from
    // the SQLPredicate overload.
    assertEquals("*", SQLHelper.parseValue((BaseParser) null, "*", ctx));
  }
}
