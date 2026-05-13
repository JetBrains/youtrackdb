/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Standalone unit tests for the pure (no-session) static surface of
 * {@link RecordSerializerStringAbstract}: {@link RecordSerializerStringAbstract#getType(String)}
 * and the session-independent paths of
 * {@link RecordSerializerStringAbstract#getTypeValue(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded, String)}.
 *
 * <p>These methods drive the legacy CSV-style "auto-detect type from string content" logic that
 * the SQL helper still uses for parameter parsing. Tests pin each detection branch (RID prefix,
 * quote-wrapped string, type-suffix letter, scientific notation, decimal vs. integer
 * autopromotion) so a regression in the parser would fail an exact branch.
 *
 * <p>Map detection ({@code '{'}-{@code '}'}) is session-dependent — covered separately by the
 * DbTestBase counterpart.
 */
public class RecordSerializerStringAbstractStaticsTest {

  // ---------------------------------------------------------- getType(String)

  /** Empty string is the null-type sentinel — pinned to catch a regression that returned STRING. */
  @Test
  public void getTypeReturnsNullForEmptyString() {
    assertNull(RecordSerializerStringAbstract.getType(""));
  }

  @Test
  public void getTypeRecognizesRidPrefix() {
    assertEquals(PropertyTypeInternal.LINK, RecordSerializerStringAbstract.getType("#5:1"));
  }

  @Test
  public void getTypeRecognizesSingleQuotedString() {
    assertEquals(PropertyTypeInternal.STRING, RecordSerializerStringAbstract.getType("'abc'"));
  }

  @Test
  public void getTypeRecognizesDoubleQuotedString() {
    assertEquals(PropertyTypeInternal.STRING, RecordSerializerStringAbstract.getType("\"abc\""));
  }

  @Test
  public void getTypeRecognizesBinaryDelimiter() {
    // BINARY is delimited by underscore on both sides per StringSerializerHelper.BINARY_BEGINEND.
    assertEquals(
        PropertyTypeInternal.BINARY, RecordSerializerStringAbstract.getType("_abc_"));
  }

  @Test
  public void getTypeRecognizesEmbeddedBegin() {
    assertEquals(
        PropertyTypeInternal.EMBEDDED, RecordSerializerStringAbstract.getType("(MyClass@x:1)"));
  }

  @Test
  public void getTypeRecognizesEmbeddedListBegin() {
    assertEquals(
        PropertyTypeInternal.EMBEDDEDLIST, RecordSerializerStringAbstract.getType("[1,2,3]"));
  }

  @Test
  public void getTypeRecognizesEmbeddedSetBegin() {
    assertEquals(
        PropertyTypeInternal.EMBEDDEDSET, RecordSerializerStringAbstract.getType("<1,2,3>"));
  }

  @Test
  public void getTypeRecognizesEmbeddedMapBegin() {
    assertEquals(
        PropertyTypeInternal.EMBEDDEDMAP, RecordSerializerStringAbstract.getType("{a:1}"));
  }

  @Test
  public void getTypeRecognizesBooleanLiteralsCaseInsensitively() {
    assertEquals(PropertyTypeInternal.BOOLEAN, RecordSerializerStringAbstract.getType("true"));
    assertEquals(PropertyTypeInternal.BOOLEAN, RecordSerializerStringAbstract.getType("True"));
    assertEquals(PropertyTypeInternal.BOOLEAN, RecordSerializerStringAbstract.getType("FALSE"));
    assertEquals(PropertyTypeInternal.BOOLEAN, RecordSerializerStringAbstract.getType("false"));
  }

  /** Plain integer in INT range → INTEGER, NOT LONG. */
  @Test
  public void getTypeRecognizesPlainIntegerAsInteger() {
    assertEquals(PropertyTypeInternal.INTEGER, RecordSerializerStringAbstract.getType("42"));
  }

  @Test
  public void getTypeRecognizesNegativeIntegerAsInteger() {
    assertEquals(PropertyTypeInternal.INTEGER, RecordSerializerStringAbstract.getType("-42"));
  }

  @Test
  public void getTypeRecognizesPositiveSignedIntegerAsInteger() {
    assertEquals(PropertyTypeInternal.INTEGER, RecordSerializerStringAbstract.getType("+42"));
  }

  /**
   * Integer at INT_MAX boundary stays INTEGER — pin the comparison so a regression that
   * promotes anything ≥ INT_MAX would be caught.
   */
  @Test
  public void getTypeIntegerBoundaryAtIntMaxStaysInteger() {
    assertEquals(
        PropertyTypeInternal.INTEGER,
        RecordSerializerStringAbstract.getType(String.valueOf(Integer.MAX_VALUE)));
  }

  /** One past INT_MAX (same digit count) auto-promotes to LONG. */
  @Test
  public void getTypeIntegerOnePastIntMaxBoundaryPromotesToLong() {
    assertEquals(
        PropertyTypeInternal.LONG,
        RecordSerializerStringAbstract.getType(String.valueOf((long) Integer.MAX_VALUE + 1)));
  }

  /** A digit-only string longer than INT_MAX's width auto-promotes to LONG. */
  @Test
  public void getTypeIntegerLongerThanIntMaxDigitsPromotesToLong() {
    assertEquals(
        PropertyTypeInternal.LONG,
        RecordSerializerStringAbstract.getType("99999999999")); // 11 digits > 10 of INT_MAX
  }

  // ---- Type-suffix branches (require at least one digit before the suffix letter)

  @Test
  public void getTypeFloatSuffix() {
    assertEquals(PropertyTypeInternal.FLOAT, RecordSerializerStringAbstract.getType("3f"));
  }

  @Test
  public void getTypeDecimalSuffix() {
    assertEquals(PropertyTypeInternal.DECIMAL, RecordSerializerStringAbstract.getType("3c"));
  }

  @Test
  public void getTypeLongSuffix() {
    assertEquals(PropertyTypeInternal.LONG, RecordSerializerStringAbstract.getType("3l"));
  }

  @Test
  public void getTypeDoubleSuffix() {
    assertEquals(PropertyTypeInternal.DOUBLE, RecordSerializerStringAbstract.getType("3d"));
  }

  @Test
  public void getTypeByteSuffix() {
    assertEquals(PropertyTypeInternal.BYTE, RecordSerializerStringAbstract.getType("3b"));
  }

  @Test
  public void getTypeDateSuffix() {
    assertEquals(PropertyTypeInternal.DATE, RecordSerializerStringAbstract.getType("3a"));
  }

  @Test
  public void getTypeDatetimeSuffix() {
    assertEquals(PropertyTypeInternal.DATETIME, RecordSerializerStringAbstract.getType("3t"));
  }

  @Test
  public void getTypeShortSuffix() {
    assertEquals(PropertyTypeInternal.SHORT, RecordSerializerStringAbstract.getType("3s"));
  }

  /**
   * A type-suffix letter that is NOT the last character demotes the result to STRING. Pin this
   * so a regression that ignored the position check would be caught.
   */
  @Test
  public void getTypeRejectsTypeSuffixWhenNotAtEnd() {
    assertEquals(PropertyTypeInternal.STRING, RecordSerializerStringAbstract.getType("3f4"));
    assertEquals(PropertyTypeInternal.STRING, RecordSerializerStringAbstract.getType("3l5"));
    assertEquals(PropertyTypeInternal.STRING, RecordSerializerStringAbstract.getType("3a1"));
  }

  /** A leading non-digit, non-special character is just STRING. */
  @Test
  public void getTypeRecognizesPlainWordAsString() {
    assertEquals(PropertyTypeInternal.STRING, RecordSerializerStringAbstract.getType("abc"));
  }

  /** Leading sign followed by a non-digit is STRING (matches CSV historical behavior). */
  @Test
  public void getTypeLeadingPlusFollowedByNonDigitIsString() {
    assertEquals(PropertyTypeInternal.STRING, RecordSerializerStringAbstract.getType("+abc"));
  }

  /**
   * Decimal numbers fall into FLOAT / DOUBLE / DECIMAL based on round-trip equality with
   * {@link Double#parseDouble(String)} — the FLOAT branch catches values whose
   * {@code float}-rounded form round-trips through {@link Double#toString}.
   */
  @Test
  public void getTypeDecimalRecognizesFloatRange() {
    assertEquals(PropertyTypeInternal.FLOAT, RecordSerializerStringAbstract.getType("1.5"));
  }

  /**
   * A very precise decimal that does NOT round-trip equal to its {@code double} representation
   * is classified as DECIMAL (multi-branch fallthrough).
   */
  @Test
  public void getTypeDecimalRecognizesDecimalWhenNotRoundTripEqual() {
    assertEquals(
        PropertyTypeInternal.DECIMAL,
        RecordSerializerStringAbstract.getType("1234567890.12345678901234567890"));
  }

  /** Scientific notation with leading negative exponent is parsed as DOUBLE. */
  @Test
  public void getTypeScientificNotationParsesAsDouble() {
    assertEquals(PropertyTypeInternal.DOUBLE, RecordSerializerStringAbstract.getType("1.5e-06"));
  }

  /** {@code 'e'} embedded inside a non-decimal token is malformed → STRING. */
  @Test
  public void getTypeMalformedScientificFallsBackToString() {
    assertEquals(PropertyTypeInternal.STRING, RecordSerializerStringAbstract.getType("1e2x"));
  }

  // ----------------------------------------------------- getTypeValue (no DB)

  /** {@code null} input returns {@code null} — explicit guard. */
  @Test
  public void getTypeValueNullInputReturnsNull() {
    assertNull(RecordSerializerStringAbstract.getTypeValue(null, null));
  }

  /** Case-insensitive {@code "NULL"} sentinel returns {@code null}. */
  @Test
  public void getTypeValueNullSentinelReturnsNull() {
    assertNull(RecordSerializerStringAbstract.getTypeValue(null, "NULL"));
    assertNull(RecordSerializerStringAbstract.getTypeValue(null, "null"));
    assertNull(RecordSerializerStringAbstract.getTypeValue(null, "Null"));
  }

  /** Empty string maps to empty string, not null — pin to differentiate from the NULL branch. */
  @Test
  public void getTypeValueEmptyStringReturnsEmptyString() {
    assertEquals("", RecordSerializerStringAbstract.getTypeValue(null, ""));
  }

  /** Quoted string is decoded (unquoted, escapes resolved). */
  @Test
  public void getTypeValueDoubleQuotedStringIsDecoded() {
    assertEquals("hello", RecordSerializerStringAbstract.getTypeValue(null, "\"hello\""));
  }

  /** RID prefix {@code '#'} triggers RID parsing — no session required. */
  @Test
  public void getTypeValueRidStringIsParsedToRecordId() {
    final var result = RecordSerializerStringAbstract.getTypeValue(null, "#5:1");
    assertEquals(RecordIdInternal.fromString("#5:1", false), result);
    assertTrue("must return a RID instance", result instanceof RID);
  }

  @Test
  public void getTypeValueListLiteralIsParsedToCollection() {
    final var result = RecordSerializerStringAbstract.getTypeValue(null, "[1,2,3]");
    assertTrue("must be a List", result instanceof List);
    assertEquals(List.of("1", "2", "3"), result);
  }

  @Test
  public void getTypeValueSetLiteralIsParsedToCollection() {
    final var result = RecordSerializerStringAbstract.getTypeValue(null, "<1,2,3>");
    assertTrue("must be a Set", result instanceof Set);
    assertTrue(((Collection<?>) result).contains("1"));
    assertTrue(((Collection<?>) result).contains("2"));
    assertTrue(((Collection<?>) result).contains("3"));
  }

  @Test
  public void getTypeValueIntegerIsParsedToInteger() {
    assertEquals(Integer.valueOf(42), RecordSerializerStringAbstract.getTypeValue(null, "42"));
  }

  /**
   * Plain digit string that overflows {@code Integer.parseInt} promotes to {@code Long} via
   * the {@link NumberFormatException} catch path.
   */
  @Test
  public void getTypeValueIntegerOverflowFallsBackToLong() {
    final var result = RecordSerializerStringAbstract.getTypeValue(null, "9999999999");
    assertEquals(Long.valueOf(9_999_999_999L), result);
  }

  @Test
  public void getTypeValueLongSuffixReturnsLong() {
    assertEquals(Long.valueOf(42L), RecordSerializerStringAbstract.getTypeValue(null, "42l"));
  }

  @Test
  public void getTypeValueFloatSuffixReturnsFloat() {
    assertEquals(Float.valueOf(1.5f), RecordSerializerStringAbstract.getTypeValue(null, "1.5f"));
  }

  @Test
  public void getTypeValueDoubleSuffixReturnsDouble() {
    assertEquals(
        Double.valueOf(1.5d), RecordSerializerStringAbstract.getTypeValue(null, "1.5d"));
  }

  @Test
  public void getTypeValueDecimalSuffixReturnsBigDecimal() {
    assertEquals(
        new BigDecimal("1.5"), RecordSerializerStringAbstract.getTypeValue(null, "1.5c"));
  }

  @Test
  public void getTypeValueByteSuffixReturnsByte() {
    assertEquals(Byte.valueOf((byte) 7), RecordSerializerStringAbstract.getTypeValue(null, "7b"));
  }

  @Test
  public void getTypeValueShortSuffixReturnsShort() {
    assertEquals(
        Short.valueOf((short) 7), RecordSerializerStringAbstract.getTypeValue(null, "7s"));
  }

  /** {@code 'a'} on epoch milliseconds returns a {@link java.util.Date}. */
  @Test
  public void getTypeValueDateSuffixReturnsDate() {
    final var result = RecordSerializerStringAbstract.getTypeValue(null, "1000a");
    assertEquals(new java.util.Date(1000L), result);
  }

  /** {@code 't'} on epoch milliseconds returns a {@link java.util.Date}. */
  @Test
  public void getTypeValueDatetimeSuffixReturnsDate() {
    final var result = RecordSerializerStringAbstract.getTypeValue(null, "1000t");
    assertEquals(new java.util.Date(1000L), result);
  }

  /**
   * {@code "NaN"} flows through the for-loop's "non-digit at index 0" branch and falls
   * through to {@code return iValue;} as a plain String. The post-loop {@code "NaN"} /
   * {@code "Infinity"} → {@code Double} branch is unreachable for these inputs because the
   * loop short-circuits at the first non-digit. Pinned so a refactor that moved the
   * {@code NaN}/{@code Infinity} check above the loop would be caught.
   *
   * <p>WHEN-FIXED: forwards-to dead-code/cleanup pass — the {@code "NaN".equals} /
   * {@code "Infinity".equals} arms in the post-loop block are unreachable from
   * {@link RecordSerializerStringAbstract#getTypeValue} and can be removed.
   */
  @Test
  public void getTypeValueNaNReturnsOriginalStringNotDouble() {
    assertEquals("NaN", RecordSerializerStringAbstract.getTypeValue(null, "NaN"));
  }

  @Test
  public void getTypeValueInfinityReturnsOriginalStringNotDouble() {
    assertEquals("Infinity", RecordSerializerStringAbstract.getTypeValue(null, "Infinity"));
  }

  /**
   * A leading-sign-only string with NO type-suffix letter following falls through to
   * {@code return iValue;} — the {@code stringStarBySign} flag set at index 0 is reset to
   * {@code false} on any subsequent digit, but if the second char is a non-digit the loop
   * dispatches to the type-suffix branch first.
   *
   * <p>{@code "+xyz"} avoids the date-suffix dispatch ({@code 'a'}/{@code 't'}) because
   * {@code 'x'} is not a recognized type letter — the parser falls through to the generic
   * {@code return iValue;} arm. Verified empirically: {@code "+abc"} would NFE on
   * {@code Long.parseLong("+")} via the date arm.
   */
  @Test
  public void getTypeValueLeadingSignOnlyWithNoTypeSuffixReturnsOriginalString() {
    assertEquals("+xyz", RecordSerializerStringAbstract.getTypeValue(null, "+xyz"));
    assertEquals("-xyz", RecordSerializerStringAbstract.getTypeValue(null, "-xyz"));
  }

  /**
   * Plain word with no special markers is returned as the original string, not parsed.
   */
  @Test
  public void getTypeValuePlainWordReturnsOriginalString() {
    assertEquals("abc", RecordSerializerStringAbstract.getTypeValue(null, "abc"));
  }

  /**
   * Decimal with no type suffix is parsed as {@link BigDecimal}.
   */
  @Test
  public void getTypeValueDecimalNoSuffixReturnsBigDecimal() {
    assertEquals(new BigDecimal("1.5"), RecordSerializerStringAbstract.getTypeValue(null, "1.5"));
  }
}
