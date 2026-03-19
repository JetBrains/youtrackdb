package com.jetbrains.youtrackdb.internal.core.index.engine;

import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link ScalarConversion}.
 *
 * <p>Covers: numeric types (int, long, double, float), Date → epoch millis,
 * String → base-65536 encoding with common prefix stripping, ordering
 * preservation (ASCII and non-ASCII), unknown type fallback, edge cases
 * (empty strings, single-char strings, identical strings).
 */
public class ScalarConversionTest {

  private static final double DELTA = 1e-15;

  // ── Numeric types ─────────────────────────────────────────────────

  @Test
  public void integerValueReturnsDoubleValue() {
    // Given an integer value with arbitrary bounds
    // When scalarized, it returns the numeric double value directly
    double result = ScalarConversion.scalarize(42, 0, 100);
    Assert.assertEquals(42.0, result, DELTA);
  }

  @Test
  public void longValueReturnsDoubleValue() {
    double result = ScalarConversion.scalarize(100_000_000L, 0L, 200_000_000L);
    Assert.assertEquals(1.0E8, result, DELTA);
  }

  @Test
  public void doubleValueReturnsItself() {
    double result = ScalarConversion.scalarize(3.14, 0.0, 10.0);
    Assert.assertEquals(3.14, result, DELTA);
  }

  @Test
  public void floatValueReturnsDoubleValue() {
    double result = ScalarConversion.scalarize(2.5f, 0.0f, 5.0f);
    Assert.assertEquals(2.5, result, 1e-6);
  }

  @Test
  public void negativeNumberReturnsNegativeDouble() {
    double result = ScalarConversion.scalarize(-10, -20, 0);
    Assert.assertEquals(-10.0, result, DELTA);
  }

  // ── Date type ─────────────────────────────────────────────────────

  @Test
  public void dateReturnsEpochMillis() {
    // Given a specific date
    long epochMs = 1704067200000L; // 2024-01-01T00:00:00Z
    Date date = new Date(epochMs);
    // When scalarized, it returns epoch millis as double
    double result = ScalarConversion.scalarize(date, new Date(0), new Date());
    Assert.assertEquals((double) epochMs, result, DELTA);
  }

  @Test
  public void dateEpochZeroReturnsZero() {
    Date date = new Date(0);
    double result = ScalarConversion.scalarize(date, new Date(0), new Date());
    Assert.assertEquals(0.0, result, DELTA);
  }

  // ── Unknown type fallback ─────────────────────────────────────────

  @Test
  public void unknownTypeReturnsMidpoint() {
    // Given a non-numeric, non-string, non-date value (e.g., byte array)
    // When scalarized, it returns 0.5 (midpoint assumption)
    double result = ScalarConversion.scalarize(
        new byte[] {1, 2, 3}, new byte[] {0}, new byte[] {4, 5});
    Assert.assertEquals(0.5, result, DELTA);
  }

  @Test
  public void stringValueWithNonStringBoundsReturnsMidpoint() {
    // When value is a String but bounds are not, falls back to 0.5
    double result = ScalarConversion.scalarize("hello", 0, 100);
    Assert.assertEquals(0.5, result, DELTA);
  }

  // ── String type: basic encoding ───────────────────────────────────

  @Test
  public void stringToScalarWithNoCommonPrefix() {
    // "abc" with bounds "aaa" and "zzz" — common prefix length is 0
    // (because 'a', 'a', 'z' are not all equal at index 0)
    // Wait — 'a' == 'a' but != 'z' → prefix = 0
    double result = ScalarConversion.stringToScalar("abc", "aaa", "zzz");
    double expected = ScalarConversion.charEncode("abc", 0);
    Assert.assertEquals(expected, result, DELTA);
  }

  @Test
  public void stringToScalarWithCommonPrefix() {
    // "prefix_abc", "prefix_aaa", "prefix_zzz" share "prefix_" (7 chars)
    // but then 'a', 'a', 'z' differ → prefix = 7
    double result = ScalarConversion.stringToScalar(
        "prefix_abc", "prefix_aaa", "prefix_zzz");
    double expected = ScalarConversion.charEncode("prefix_abc", 7);
    Assert.assertEquals(expected, result, DELTA);
  }

  @Test
  public void stringToScalarIdenticalStringsReturnSameValue() {
    // When all three strings are identical, prefix strips everything
    double result = ScalarConversion.stringToScalar("hello", "hello", "hello");
    // charEncode with offset == length → returns 0.0
    Assert.assertEquals(0.0, result, DELTA);
  }

  @Test
  public void charEncodeEmptyStringReturnsZero() {
    double result = ScalarConversion.charEncode("", 0);
    Assert.assertEquals(0.0, result, DELTA);
  }

  @Test
  public void charEncodeOffsetBeyondLengthReturnsZero() {
    double result = ScalarConversion.charEncode("abc", 5);
    Assert.assertEquals(0.0, result, DELTA);
  }

  @Test
  public void charEncodeSingleCharAtOffset() {
    // 'A' is char code 65
    double result = ScalarConversion.charEncode("A", 0);
    Assert.assertEquals(65.0 / 65536.0, result, DELTA);
  }

  @Test
  public void charEncodeTwoChars() {
    // "AB" → A(65)/65536 + B(66)/65536^2
    double result = ScalarConversion.charEncode("AB", 0);
    double expected = 65.0 / 65536.0 + 66.0 / (65536.0 * 65536.0);
    Assert.assertEquals(expected, result, DELTA);
  }

  @Test
  public void charEncodeMaxFourChars() {
    // Even for a 10-char string, only first 4 chars contribute
    String s = "ABCDEFGHIJ";
    double result = ScalarConversion.charEncode(s, 0);
    double expected = ScalarConversion.charEncode("ABCD", 0);
    Assert.assertEquals(expected, result, DELTA);
  }

  // ── String ordering preservation ──────────────────────────────────

  @Test
  public void scalarPreservesAsciiOrdering() {
    // 'a' < 'b' < 'z' in String.compareTo()
    // Scalar values should maintain the same ordering
    double a = ScalarConversion.stringToScalar("a", "a", "z");
    double b = ScalarConversion.stringToScalar("b", "a", "z");
    double z = ScalarConversion.stringToScalar("z", "a", "z");
    Assert.assertTrue("scalarize('a') < scalarize('b')", a < b);
    Assert.assertTrue("scalarize('b') < scalarize('z')", b < z);
  }

  @Test
  public void scalarPreservesNonAsciiOrdering() {
    // Verify that scalarize preserves String.compareTo() ordering
    // for non-ASCII characters.
    // Java char comparison: 'Z' (90) < 'a' (97) < 'ä' (228)
    String lo = "Z";
    String hi = "\u00E4"; // 'ä'
    double scalarZ = ScalarConversion.stringToScalar("Z", lo, hi);
    double scalarA = ScalarConversion.stringToScalar("a", lo, hi);
    double scalarAuml = ScalarConversion.stringToScalar("\u00E4", lo, hi);

    // Ordering should match String.compareTo(): "Z" < "a" < "ä"
    Assert.assertTrue("Z".compareTo("a") < 0);
    Assert.assertTrue("a".compareTo("\u00E4") < 0);
    Assert.assertTrue(
        "scalarize('Z') < scalarize('a')", scalarZ < scalarA);
    Assert.assertTrue(
        "scalarize('a') < scalarize('ä')", scalarA < scalarAuml);
  }

  @Test
  public void scalarPreservesOrderingWithCommonPrefix() {
    // Strings sharing a common prefix should still maintain ordering
    String lo = "user_alpha";
    String hi = "user_zulu";
    double alpha = ScalarConversion.stringToScalar("user_alpha", lo, hi);
    double mid = ScalarConversion.stringToScalar("user_mike", lo, hi);
    double zulu = ScalarConversion.stringToScalar("user_zulu", lo, hi);
    Assert.assertTrue(alpha < mid);
    Assert.assertTrue(mid < zulu);
  }

  // ── commonCharPrefixLength ────────────────────────────────────────

  @Test
  public void commonPrefixAllIdentical() {
    int len = ScalarConversion.commonCharPrefixLength("abc", "abc", "abc");
    Assert.assertEquals(3, len);
  }

  @Test
  public void commonPrefixNoCommonChars() {
    int len = ScalarConversion.commonCharPrefixLength("abc", "def", "ghi");
    Assert.assertEquals(0, len);
  }

  @Test
  public void commonPrefixPartialMatch() {
    int len = ScalarConversion.commonCharPrefixLength(
        "prefix_x", "prefix_y", "prefix_z");
    Assert.assertEquals(7, len); // "prefix_" is common
  }

  @Test
  public void commonPrefixDifferentLengths() {
    // Shortest string limits the prefix length
    int len = ScalarConversion.commonCharPrefixLength("ab", "abcdef", "abxyz");
    Assert.assertEquals(2, len);
  }

  @Test
  public void commonPrefixEmptyString() {
    int len = ScalarConversion.commonCharPrefixLength("", "abc", "abc");
    Assert.assertEquals(0, len);
  }

  @Test
  public void commonPrefixTwoOfThreeDiffer() {
    // 'a' == 'a' but != 'b' at index 0
    int len = ScalarConversion.commonCharPrefixLength("abc", "axyz", "amnop");
    Assert.assertEquals(1, len);
  }

  // ── commonCharPrefixLength: all empty strings ────────────────────

  @Test
  public void commonCharPrefixLength_emptyStrings() {
    // All three strings are empty. Math.min(0, Math.min(0, 0)) = 0,
    // so the while loop never executes → returns 0.
    int len = ScalarConversion.commonCharPrefixLength("", "", "");
    Assert.assertEquals(0, len);
  }

  // ── charEncode: single-char string encoding formula ───────────────

  @Test
  public void charEncode_shortString() {
    // A single-char string "B" (code 66): encoded as 66 / 65536.
    // Verifies the base-65536 fractional encoding formula.
    double result = ScalarConversion.charEncode("B", 0);
    Assert.assertEquals(66.0 / 65536.0, result, DELTA);
  }

  // ── scalarize: unknown type returns 0.5 ───────────────────────────

  @Test
  public void scalarize_unknownType_returns0point5() {
    // Pass a non-Number, non-Date, non-String type (a plain Object).
    // The fallback branch returns 0.5 (midpoint assumption).
    Object unknownType = new Object();
    double result = ScalarConversion.scalarize(unknownType, unknownType,
        unknownType);
    Assert.assertEquals(0.5, result, DELTA);
  }
}
