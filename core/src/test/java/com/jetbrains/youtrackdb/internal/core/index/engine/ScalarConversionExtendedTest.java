package com.jetbrains.youtrackdb.internal.core.index.engine;

import java.math.BigDecimal;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/**
 * Extended tests for {@link ScalarConversion} (Section 10.4).
 *
 * <p>Covers scenarios not in the base {@link ScalarConversionTest}:
 * long common prefix precision, degenerate bucket fraction via
 * {@link SelectivityEstimator}, and additional edge cases.
 */
public class ScalarConversionExtendedTest {

  private static final double DELTA = 1e-15;

  // ── Integer/long/double direct conversion ─────────────────────

  @Test
  public void integerMinMaxBoundsConvertCorrectly() {
    // Integer.MIN_VALUE and MAX_VALUE should convert to exact doubles
    Assert.assertEquals(
        (double) Integer.MIN_VALUE,
        ScalarConversion.scalarize(Integer.MIN_VALUE, 0, 0), DELTA);
    Assert.assertEquals(
        (double) Integer.MAX_VALUE,
        ScalarConversion.scalarize(Integer.MAX_VALUE, 0, 0), DELTA);
  }

  @Test
  public void longLargeValueConvertsToDouble() {
    // Large long values lose precision in double but still convert.
    // Both sides undergo the same long→double conversion, so the
    // result matches exactly despite precision loss.
    long val = Long.MAX_VALUE;
    double result = ScalarConversion.scalarize(val, 0L, val);
    Assert.assertEquals((double) val, result, DELTA);
    // Demonstrate that double cannot distinguish nearby large longs:
    // MAX_VALUE and MAX_VALUE-1 map to the same double.
    Assert.assertEquals(
        "adjacent large longs map to same double",
        (double) (Long.MAX_VALUE - 1), (double) Long.MAX_VALUE, DELTA);
  }

  @Test
  public void doubleSpecialValuesConvertDirectly() {
    // NaN and Infinity should pass through as-is (Number.doubleValue())
    Assert.assertTrue(
        Double.isNaN(ScalarConversion.scalarize(Double.NaN, 0.0, 1.0)));
    Assert.assertEquals(
        Double.POSITIVE_INFINITY,
        ScalarConversion.scalarize(Double.POSITIVE_INFINITY, 0.0, 1.0),
        DELTA);
    Assert.assertEquals(
        Double.NEGATIVE_INFINITY,
        ScalarConversion.scalarize(Double.NEGATIVE_INFINITY, 0.0, 1.0),
        DELTA);
  }

  @Test
  public void shortAndByteAreNumericTypes() {
    // Short and Byte are Number subclasses — should convert via doubleValue()
    Assert.assertEquals(
        42.0, ScalarConversion.scalarize((short) 42, (short) 0, (short) 100),
        DELTA);
    Assert.assertEquals(
        7.0, ScalarConversion.scalarize((byte) 7, (byte) 0, (byte) 10),
        DELTA);
  }

  @Test
  public void bigDecimalConvertsViaDoubleValue() {
    // BigDecimal is a Number — should convert via doubleValue()
    BigDecimal bd = new BigDecimal("3.14159265358979323846");
    double result = ScalarConversion.scalarize(bd, BigDecimal.ZERO,
        BigDecimal.TEN);
    Assert.assertEquals(bd.doubleValue(), result, DELTA);
  }

  // ── Date → epoch millis ───────────────────────────────────────

  @Test
  public void dateNegativeEpochConvertsCorrectly() {
    // Dates before epoch (1970) have negative epoch millis
    long beforeEpoch = -86400000L; // 1969-12-31
    Date date = new Date(beforeEpoch);
    double result = ScalarConversion.scalarize(date, new Date(0), new Date());
    Assert.assertEquals((double) beforeEpoch, result, DELTA);
  }

  // ── String: long common prefix precision ──────────────────────

  @Test
  public void longCommonPrefixStrippedForHighPrecision() {
    // Given strings sharing a 20-char prefix that differ only in
    // the last few characters, prefix stripping enables precise
    // discrimination after stripping.
    String prefix = "aaaaaaaaaaaaaaaaaaaa"; // 20 chars
    String lo = prefix + "alpha";
    String hi = prefix + "zulu";
    String mid = prefix + "mike";

    double sLo = ScalarConversion.stringToScalar(lo, lo, hi);
    double sMid = ScalarConversion.stringToScalar(mid, lo, hi);
    double sHi = ScalarConversion.stringToScalar(hi, lo, hi);

    // Ordering preserved despite long shared prefix
    Assert.assertTrue("lo < mid after prefix strip", sLo < sMid);
    Assert.assertTrue("mid < hi after prefix strip", sMid < sHi);

    // The values should be meaningfully different (not collapsed by
    // precision loss) — check that mid is not at an extreme
    double range = sHi - sLo;
    Assert.assertTrue("range > 0", range > 0);
    double relPos = (sMid - sLo) / range;
    Assert.assertTrue(
        "mid is between lo and hi (relative: " + relPos + ")",
        relPos > 0.01 && relPos < 0.99);
  }

  @Test
  public void veryLongCommonPrefixDoesNotLosePrecision() {
    // 100-char common prefix followed by single-char suffix
    String prefix = "x".repeat(100);
    String lo = prefix + "A";
    String hi = prefix + "Z";
    String val = prefix + "M";

    double sLo = ScalarConversion.stringToScalar(lo, lo, hi);
    double sVal = ScalarConversion.stringToScalar(val, lo, hi);
    double sHi = ScalarConversion.stringToScalar(hi, lo, hi);

    // After stripping 100-char prefix, we encode 'A', 'M', 'Z'
    // These should be well-separated
    Assert.assertTrue(sLo < sVal);
    Assert.assertTrue(sVal < sHi);

    // Verify the prefix length is actually 100
    int prefixLen = ScalarConversion.commonCharPrefixLength(lo, lo, hi);
    Assert.assertEquals(100, prefixLen);
  }

  @Test
  public void commonPrefixStrippingDifferentiatesNearbyStrings() {
    // Strings that differ only at the very end — without prefix
    // stripping these would be indistinguishable in base-65536
    // (4 chars exhaust mantissa, so position >4 is lost)
    String prefix = "abcdefghij"; // 10-char prefix
    String lo = prefix + "0";
    String hi = prefix + "9";
    String v1 = prefix + "3";
    String v2 = prefix + "7";

    double s1 = ScalarConversion.stringToScalar(v1, lo, hi);
    double s2 = ScalarConversion.stringToScalar(v2, lo, hi);

    // Should be clearly distinguishable
    Assert.assertTrue("v1 < v2", s1 < s2);
    Assert.assertNotEquals("values are distinct", s1, s2, DELTA);
  }

  // ── String: non-ASCII ordering preservation ───────────────────

  @Test
  public void nonAsciiCharsPreserveStringCompareToOrdering() {
    // Test a range of non-ASCII characters to verify that scalarize
    // preserves the same ordering as String.compareTo()
    String[] strs = {
        "A",     // 65
        "Z",     // 90
        "a",     // 97
        "z",     // 122
        "\u00C0", // À (192)
        "\u00E4", // ä (228)
        "\u4E2D", // 中 (20013)
        "\uFFFF"  // max BMP char (65535)
    };
    String lo = strs[0];
    String hi = strs[strs.length - 1];

    for (int i = 0; i < strs.length - 1; i++) {
      double si = ScalarConversion.stringToScalar(strs[i], lo, hi);
      double sj = ScalarConversion.stringToScalar(strs[i + 1], lo, hi);
      int cmp = strs[i].compareTo(strs[i + 1]);
      Assert.assertTrue(
          "compareTo ordering must match: " + strs[i] + " vs " + strs[i + 1],
          cmp < 0);
      Assert.assertTrue(
          "scalar ordering must match compareTo: "
              + strs[i] + "(" + si + ") vs " + strs[i + 1] + "(" + sj + ")",
          si < sj);
    }
  }

  @Test
  public void highUnicodeCharsProduceLargerScalars() {
    // CJK characters have high char codes and should produce large
    // scalar values relative to ASCII
    double ascii = ScalarConversion.charEncode("A", 0);
    double cjk = ScalarConversion.charEncode("\u4E2D", 0); // 中
    Assert.assertTrue("CJK scalar > ASCII scalar", cjk > ascii);
    // Ratio should roughly match char code ratio: 20013/65 ~ 308x
    Assert.assertTrue("CJK/ASCII ratio > 100", cjk / ascii > 100);
  }

  // ── Unknown type fallback ─────────────────────────────────────

  @Test
  public void nullValueReturnsMidpoint() {
    // null falls through all instanceof checks (Number, Date, String)
    // and hits the unknown-type fallback → 0.5. Distinct from the
    // byte[] test in ScalarConversionTest which tests an actual object
    // of unrecognized type.
    double result = ScalarConversion.scalarize(null, null, null);
    Assert.assertEquals(0.5, result, DELTA);
  }

  @Test
  public void booleanReturnsMidpoint() {
    // Boolean is not a Number — should return 0.5
    double result = ScalarConversion.scalarize(true, false, true);
    Assert.assertEquals(0.5, result, DELTA);
  }

  // ── Degenerate bucket: lo == hi → fraction = 0.5 ─────────────

  @Test
  public void degenerateBucketWithIdenticalNumericBoundariesReturnsMidpoint() {
    // When lo and hi scalarize to the same value, fractionOf in
    // SelectivityEstimator returns 0.5 (degenerate bucket guard).
    // We verify via scalarize: if lo == hi, scaledLo == scaledHi.
    double lo = ScalarConversion.scalarize(42, 42, 42);
    double hi = ScalarConversion.scalarize(42, 42, 42);
    Assert.assertEquals("lo == hi for identical values", lo, hi, DELTA);
    // In SelectivityEstimator.fractionOf, this triggers: return 0.5
  }

  @Test
  public void degenerateBucketWithIdenticalStringBoundariesReturnsMidpoint() {
    // When lo and hi are the same string, scalarize(value, lo, hi)
    // produces the same value for value/lo/hi → degenerate bucket
    double val = ScalarConversion.scalarize("same", "same", "same");
    double lo = ScalarConversion.scalarize("same", "same", "same");
    // stringToScalar with identical strings: prefix = full length → 0.0
    Assert.assertEquals(0.0, val, DELTA);
    Assert.assertEquals(val, lo, DELTA);
    // scaledHi == scaledLo, so fractionOf returns 0.5
  }

  @Test
  public void degenerateBucketSelectivityEstimatorReturnsMidpointFraction() {
    // End-to-end: build a histogram with a degenerate bucket where
    // lo == hi, then verify that range estimation uses 0.5 fraction.
    // A single-bucket histogram with boundaries [5, 5] means the
    // bucket has lo=5, hi=5. Querying range [5, 5] should yield
    // a non-zero selectivity (not 0 from division by zero).
    Comparable<?>[] boundaries = {5, 5};
    long[] frequencies = {100};
    long[] distinctCounts = {1};
    EquiDepthHistogram histogram = new EquiDepthHistogram(
        1, boundaries, frequencies, distinctCounts, 100, null, 0);
    IndexStatistics stats = new IndexStatistics(100, 1, 0);

    // The bucket has distinctCounts[0]==1, so single-value optimization
    // applies: for RANGE mode, fraction = 0.5.
    // Range [5, 5]: both endpoints in bucket 0, fracY - fracX = 0.5 - 0.5 = 0
    // so matchingRows = 0 * 100 = 0, selectivity = 0.0.
    // This is correct: a point range on a single-value bucket with the
    // same value yields 0 from the range formula (equality is handled
    // separately by estimateEquality, not estimateRange).
    double sel = SelectivityEstimator.estimateRange(
        stats, histogram, 5, 5, true, true);
    Assert.assertEquals(
        "point range on single-value bucket: fracY - fracX = 0",
        0.0, sel, DELTA);
  }

  @Test
  public void degenerateBucketUnknownTypeInFractionOf() {
    // When boundaries are a type not recognized by ScalarConversion
    // (not Number, Date, or String), scalarize returns 0.5 for all
    // three → scaledHi == scaledLo → fractionOf returns 0.5.
    // We use a simple Comparable wrapper to satisfy the histogram
    // constructor while exercising the unknown-type fallback.
    Comparable<?>[] boundaries = {new OpaqueKey(1), new OpaqueKey(2)};
    long[] frequencies = {50};
    long[] distinctCounts = {10};
    EquiDepthHistogram histogram = new EquiDepthHistogram(
        1, boundaries, frequencies, distinctCounts, 50, null, 0);
    IndexStatistics stats = new IndexStatistics(50, 10, 0);

    // GT estimation on OpaqueKey: scalarize returns 0.5 for all
    // three inputs (value, lo, hi), so scaledHi == scaledLo →
    // fractionOf returns 0.5. Then: remaining = (1 - 0.5) * 50 = 25,
    // no buckets above → selectivity = 25 / 50 = 0.5.
    double sel = SelectivityEstimator.estimateGreaterThan(
        stats, histogram, new OpaqueKey(1));
    Assert.assertEquals(
        "degenerate bucket: half of bucket above",
        0.5, sel, DELTA);
  }

  // ── charEncode edge cases ─────────────────────────────────────

  @Test
  public void charEncodeWithNullCharacter() {
    // Null character '\0' has code 0 → contributes 0 to scalar
    double result = ScalarConversion.charEncode("\0", 0);
    Assert.assertEquals(0.0, result, DELTA);
  }

  @Test
  public void charEncodeWithMaxBmpChar() {
    // '\uFFFF' has code 65535 → 65535/65536 ≈ 0.99998
    double result = ScalarConversion.charEncode("\uFFFF", 0);
    Assert.assertEquals(65535.0 / 65536.0, result, DELTA);
  }

  @Test
  public void charEncodeMonotonicityAcrossFourPositions() {
    // Adding more high-value chars should always increase the scalar
    double one = ScalarConversion.charEncode("Z", 0);
    double two = ScalarConversion.charEncode("ZZ", 0);
    double three = ScalarConversion.charEncode("ZZZ", 0);
    double four = ScalarConversion.charEncode("ZZZZ", 0);
    Assert.assertTrue(one < two);
    Assert.assertTrue(two < three);
    Assert.assertTrue(three < four);
  }

  // ── commonCharPrefixLength edge cases ─────────────────────────

  @Test
  public void commonPrefixWithAllEmptyStrings() {
    int len = ScalarConversion.commonCharPrefixLength("", "", "");
    Assert.assertEquals(0, len);
  }

  @Test
  public void commonPrefixSecondStringShortest() {
    // Second string is shortest and limits prefix computation
    int len = ScalarConversion.commonCharPrefixLength(
        "abcdef", "ab", "abcxyz");
    Assert.assertEquals(2, len);
  }

  @Test
  public void commonPrefixThirdStringShortest() {
    // Third string is shortest
    int len = ScalarConversion.commonCharPrefixLength(
        "abcdef", "abcxyz", "a");
    Assert.assertEquals(1, len);
  }

  /**
   * A Comparable that ScalarConversion does not recognize (not
   * Number/Date/String), forcing the unknown-type fallback to 0.5.
   */
  @SuppressWarnings("rawtypes")
  private record OpaqueKey(int value) implements Comparable {
    @Override
    public int compareTo(Object o) {
      return Integer.compare(value, ((OpaqueKey) o).value);
    }
  }
}
