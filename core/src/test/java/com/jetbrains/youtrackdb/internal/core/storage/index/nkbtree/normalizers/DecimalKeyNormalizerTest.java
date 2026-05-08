package com.jetbrains.youtrackdb.internal.core.storage.index.nkbtree.normalizers;

import java.math.BigInteger;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link DecimalKeyNormalizer}, focusing on the package-private
 * {@code unsigned} helper that maps a signed {@code long} to an unsigned
 * {@link BigInteger} by reinterpreting the two's-complement bit pattern.
 * The private {@code scaleToDecimal128}, {@code clampAndRound}, and
 * {@code ensureExactRounding} methods have no production callers and are
 * forwarded to Track 22 for deletion (D4 dead-helper allowance).
 */
public class DecimalKeyNormalizerTest {

  private final DecimalKeyNormalizer normalizer = new DecimalKeyNormalizer();

  // --- unsigned() ---

  /**
   * Zero maps to the BigInteger zero — the unsigned interpretation of 0L is 0.
   */
  @Test
  public void unsignedOfZeroIsZero() {
    Assert.assertEquals(BigInteger.ZERO, normalizer.unsigned(0L));
  }

  /**
   * A positive value maps to itself — unsigned and two's-complement positive
   * values are identical for non-negative longs.
   */
  @Test
  public void unsignedOfPositivePreservesValue() {
    Assert.assertEquals(BigInteger.valueOf(42L), normalizer.unsigned(42L));
  }

  /**
   * -1L has all bits set; its unsigned interpretation is 2^64 - 1
   * (i.e. {@code Long.MAX_VALUE * 2 + 1}).
   */
  @Test
  public void unsignedOfMinusOneIsMaxUnsigned() {
    // 0xFFFF_FFFF_FFFF_FFFF == 2^64 - 1 == Long.MAX_VALUE * 2 + 1
    var expected = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);
    Assert.assertEquals(expected, normalizer.unsigned(-1L));
  }

  /**
   * Long.MIN_VALUE (0x8000_0000_0000_0000) has its sign bit set and all other
   * bits clear; its unsigned interpretation is exactly 2^63.
   */
  @Test
  public void unsignedOfLongMinValueIsTwoPow63() {
    var expected = BigInteger.TWO.pow(63);
    Assert.assertEquals(expected, normalizer.unsigned(Long.MIN_VALUE));
  }

  /**
   * Long.MAX_VALUE (0x7FFF_FFFF_FFFF_FFFF) is the largest positive long;
   * its unsigned interpretation equals its signed value (2^63 - 1).
   */
  @Test
  public void unsignedOfLongMaxValueIsTwoPow63MinusOne() {
    var expected = BigInteger.TWO.pow(63).subtract(BigInteger.ONE);
    Assert.assertEquals(expected, normalizer.unsigned(Long.MAX_VALUE));
  }
}
