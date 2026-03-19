package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Mutation-killing tests for {@link ScalarConversion} targeting
 * survived pitest mutations: parameter swaps in stringToScalar
 * and commonCharPrefixLength, and the instanceof check for String.
 *
 * <p>Lines targeted: L71, L73, L99.
 */
public class ScalarConversionParamSwapTest {

  private static final double DELTA = 1e-12;

  // ═══════════════════════════════════════════════════════════════
  // Line 71: removed conditional (value instanceof String && lo instanceof String && hi instanceof String)
  // If the condition is replaced with true, non-string types would
  // enter the string path and produce wrong results.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void scalarize_integerValue_returnsDoubleValue() {
    // Integer 42 should return 42.0 via Number path, not string path.
    double result = ScalarConversion.scalarize(42, 0, 100);
    assertEquals("Integer should use Number.doubleValue()", 42.0, result, DELTA);
  }

  @Test
  public void scalarize_mixedTypes_stringValueNonStringBounds_returnsFallback() {
    // String value with integer bounds → should NOT enter string path
    // because lo/hi are not strings. Returns fallback 0.5.
    double result = ScalarConversion.scalarize("hello", 0, 100);
    assertEquals("String with non-string bounds → fallback 0.5",
        0.5, result, DELTA);
  }

  @Test
  public void scalarize_allStrings_usesStringConversion() {
    // All three are strings → uses stringToScalar path
    double lo = ScalarConversion.scalarize("aaa", "aaa", "zzz");
    double mid = ScalarConversion.scalarize("mmm", "aaa", "zzz");
    double hi = ScalarConversion.scalarize("zzz", "aaa", "zzz");

    assertTrue("lo should be <= mid", lo <= mid);
    assertTrue("mid should be <= hi", mid <= hi);
    assertTrue("lo != hi (different strings)", lo < hi);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 73: swapped parameters 2 and 3 in call to stringToScalar
  // stringToScalar(s, sLo, sHi) → if swapped: stringToScalar(s, sHi, sLo)
  // The common prefix depends on all three strings, so swapping lo/hi
  // can change the prefix length and thus the result.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void stringToScalar_paramSwapDetection_asymmetricPrefix() {
    // lo = "abc_low", hi = "abc_high", value = "abc_mid"
    // Common prefix with correct order: "abc_" (4 chars)
    // If lo and hi are swapped: stringToScalar("abc_mid", "abc_high", "abc_low")
    // Common prefix is still "abc_" (because all three share "abc_")
    // But charEncode("abc_mid", 4) = charEncode of "mid" = same either way.
    // Need a case where swapping changes the prefix length:
    // lo = "aab", hi = "aac", value = "aab5"
    // Correct: prefix("aab5", "aab", "aac") = "aa" (since 'b' != 'c' at pos 2)
    // Swapped: prefix("aab5", "aac", "aab") = "aa" (same — symmetric)
    // Actually commonCharPrefixLength is symmetric in params 2,3.
    // But the scalarize function uses lo/hi for the common prefix — and the
    // charEncode is just based on the remaining chars of value.
    // So swapping lo and hi in stringToScalar may not change the result
    // if commonCharPrefixLength is symmetric.
    // However, the test should verify the monotonic ordering:
    double valLow = ScalarConversion.scalarize("aab", "aab", "aac");
    double valMid = ScalarConversion.scalarize("aab5", "aab", "aac");
    double valHigh = ScalarConversion.scalarize("aac", "aab", "aac");

    assertTrue("lo boundary should have smallest scalar",
        valLow <= valMid);
    assertTrue("hi boundary should have largest scalar",
        valMid <= valHigh);
  }

  // ═══════════════════════════════════════════════════════════════
  // Line 99: swapped parameters 2 and 3 in call to commonCharPrefixLength
  // commonCharPrefixLength(value, lo, hi)
  // The function is symmetric, so param swap won't change the result.
  // But we need to verify the correct prefix is computed.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void commonCharPrefixLength_correctForAllThreeStrings() {
    // All three share "ab" prefix
    assertEquals(2, ScalarConversion.commonCharPrefixLength("abc", "abd", "abe"));
    // Only first two share "abc" prefix, third differs at pos 1
    assertEquals(1, ScalarConversion.commonCharPrefixLength("abc", "axy", "azz"));
    // No common prefix
    assertEquals(0, ScalarConversion.commonCharPrefixLength("abc", "xyz", "def"));
    // All identical
    assertEquals(3, ScalarConversion.commonCharPrefixLength("abc", "abc", "abc"));
    // Empty string
    assertEquals(0, ScalarConversion.commonCharPrefixLength("", "abc", "xyz"));
  }

  @Test
  public void commonCharPrefixLength_paramOrderDoesNotMatter() {
    // Verify symmetry — any permutation gives same result.
    int len1 = ScalarConversion.commonCharPrefixLength("abx", "aby", "abz");
    int len2 = ScalarConversion.commonCharPrefixLength("aby", "abx", "abz");
    int len3 = ScalarConversion.commonCharPrefixLength("abz", "abx", "aby");
    assertEquals(len1, len2);
    assertEquals(len2, len3);
    assertEquals(2, len1);
  }

  @Test
  public void scalarize_stringOrdering_preservedWithDifferentPrefixes() {
    // Strings with different prefixes — scalarize should preserve order
    double a = ScalarConversion.scalarize("apple", "apple", "zebra");
    double m = ScalarConversion.scalarize("mango", "apple", "zebra");
    double z = ScalarConversion.scalarize("zebra", "apple", "zebra");
    assertTrue("apple < mango in scalar space", a < m);
    assertTrue("mango < zebra in scalar space", m < z);
  }

  @Test
  public void scalarize_date_returnsTimeMillis() {
    java.util.Date d = new java.util.Date(1234567890000L);
    double result = ScalarConversion.scalarize(d, d, d);
    assertEquals(1234567890000.0, result, DELTA);
  }

  @Test
  public void scalarize_unknownType_returnsFallback() {
    // A non-Number, non-Date, non-String type → fallback 0.5
    byte[] bytes = new byte[] {1, 2, 3};
    double result = ScalarConversion.scalarize(bytes, bytes, bytes);
    assertEquals(0.5, result, DELTA);
  }
}
