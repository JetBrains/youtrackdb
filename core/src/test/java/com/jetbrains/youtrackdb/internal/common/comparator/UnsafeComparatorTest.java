package com.jetbrains.youtrackdb.internal.common.comparator;

import java.util.Comparator;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link UnsafeByteArrayComparator} byte array comparison.
 *
 * @since 11.07.12
 */
public class UnsafeComparatorTest {

  private final UnsafeByteArrayComparator comparator = UnsafeByteArrayComparator.INSTANCE;

  @Test
  public void testOneByteArray() {
    final var keyOne = new byte[] {1};
    final var keyTwo = new byte[] {2};

    assertCompareTwoKeys(comparator, keyOne, keyTwo);
  }

  @Test
  public void testOneLongArray() {
    final var keyOne = new byte[] {0, 1, 0, 0, 0, 0, 0, 0};
    final var keyTwo = new byte[] {1, 0, 0, 0, 0, 0, 0, 0};

    assertCompareTwoKeys(comparator, keyOne, keyTwo);
  }

  @Test
  public void testOneLongArrayAndByte() {
    final var keyOne = new byte[] {1, 1, 0, 0, 0, 0, 0, 0, 0};
    final var keyTwo = new byte[] {1, 1, 0, 0, 0, 0, 0, 0, 1};

    assertCompareTwoKeys(comparator, keyOne, keyTwo);
  }

  @Test
  public void testOneArraySmallerThanOther() {
    final var keyOne =
        new byte[] {
            1, 1, 0, 0, 1, 0,
        };
    final var keyTwo = new byte[] {1, 1, 0, 0, 1, 0, 0, 0, 1};

    assertCompareTwoKeys(comparator, keyOne, keyTwo);
  }

  // Verifies that two identical arrays compare as equal.
  @Test
  public void testEqualArraysReturnZero() {
    final var a = new byte[] {10, 20, 30, 40, 50, 60, 70, 80};
    final var b = new byte[] {10, 20, 30, 40, 50, 60, 70, 80};
    Assert.assertEquals(0, comparator.compare(a, b));
  }

  // --- ByteArrayComparator (safe, non-Unsafe) tests ---

  // Verifies that ByteArrayComparator orders single-byte arrays correctly.
  @Test
  public void testByteArrayComparatorSingleByte() {
    final var bac = ByteArrayComparator.INSTANCE;
    final var a = new byte[] {5};
    final var b = new byte[] {10};
    Assert.assertTrue(bac.compare(a, b) < 0);
    Assert.assertTrue(bac.compare(b, a) > 0);
  }

  // Verifies that ByteArrayComparator returns zero for identical arrays.
  @Test
  public void testByteArrayComparatorEqualArrays() {
    final var bac = ByteArrayComparator.INSTANCE;
    final var a = new byte[] {1, 2, 3};
    final var b = new byte[] {1, 2, 3};
    Assert.assertEquals(0, bac.compare(a, b));
  }

  // Verifies that ByteArrayComparator handles different-length arrays (length difference wins).
  @Test
  public void testByteArrayComparatorDifferentLengths() {
    final var bac = ByteArrayComparator.INSTANCE;
    final var shorter = new byte[] {1, 2};
    final var longer = new byte[] {1, 2, 3};
    Assert.assertTrue(bac.compare(shorter, longer) < 0);
    Assert.assertTrue(bac.compare(longer, shorter) > 0);
  }

  // Verifies that ByteArrayComparator compares bytes as unsigned values.
  @Test
  public void testByteArrayComparatorUnsignedComparison() {
    final var bac = ByteArrayComparator.INSTANCE;
    // 0xFF unsigned = 255 > 0x01 unsigned = 1
    final var a = new byte[] {(byte) 0x01};
    final var b = new byte[] {(byte) 0xFF};
    Assert.assertTrue(bac.compare(a, b) < 0);
    Assert.assertTrue(bac.compare(b, a) > 0);
  }

  // --- UnsafeByteArrayComparatorV2 tests ---

  // Verifies V2 comparator orders single-byte arrays correctly.
  @Test
  public void testV2SingleByte() {
    final var v2 = UnsafeByteArrayComparatorV2.INSTANCE;
    final var a = new byte[] {1};
    final var b = new byte[] {2};
    Assert.assertTrue(v2.compare(a, b) < 0);
    Assert.assertTrue(v2.compare(b, a) > 0);
  }

  // Verifies V2 comparator returns zero for identical arrays.
  @Test
  public void testV2EqualArrays() {
    final var v2 = UnsafeByteArrayComparatorV2.INSTANCE;
    final var a = new byte[] {5, 6, 7, 8, 9, 10, 11, 12};
    final var b = new byte[] {5, 6, 7, 8, 9, 10, 11, 12};
    Assert.assertEquals(0, v2.compare(a, b));
  }

  // Verifies V2 comparator returns -1 when arrayOne is shorter but shared prefix is equal.
  // Note: V2 iterates using arrayOne.length, so this case is safe (arrayOne is shorter).
  @Test
  public void testV2ShorterFirstArrayReturnsNegative() {
    final var v2 = UnsafeByteArrayComparatorV2.INSTANCE;
    final var shorter = new byte[] {1, 2, 3};
    final var longer = new byte[] {1, 2, 3, 4, 5};
    Assert.assertTrue(v2.compare(shorter, longer) < 0);
  }

  // Verifies V2 comparator detects a difference in the long-word-aligned portion
  // even when arrays differ in length, as long as the difference is within arrayOne bounds.
  @Test
  public void testV2DifferentContentDifferentLength() {
    final var v2 = UnsafeByteArrayComparatorV2.INSTANCE;
    // arrayOne is longer but has a smaller byte at position 0 so the comparison
    // finds the difference before reaching out of bounds on arrayTwo.
    final var a = new byte[] {0, 2, 3, 4, 5};
    final var b = new byte[] {1, 2, 3};
    Assert.assertTrue(v2.compare(a, b) < 0);
  }

  // Verifies V2 comparator with multi-long arrays with a difference in tail bytes.
  @Test
  public void testV2LongAlignedWithTailDiff() {
    final var v2 = UnsafeByteArrayComparatorV2.INSTANCE;
    final var a = new byte[] {1, 1, 0, 0, 0, 0, 0, 0, 5};
    final var b = new byte[] {1, 1, 0, 0, 0, 0, 0, 0, 10};
    Assert.assertTrue(v2.compare(a, b) < 0);
    Assert.assertTrue(v2.compare(b, a) > 0);
  }

  // --- Empty byte array boundary tests ---

  @Test
  public void testUnsafeEmptyArrays() {
    // Empty arrays should compare as equal; empty vs non-empty by length.
    Assert.assertEquals(0, comparator.compare(new byte[0], new byte[0]));
    Assert.assertTrue(comparator.compare(new byte[0], new byte[] {1}) < 0);
    Assert.assertTrue(comparator.compare(new byte[] {1}, new byte[0]) > 0);
  }

  @Test
  public void testByteArrayComparatorEmptyArrays() {
    final var bac = ByteArrayComparator.INSTANCE;
    Assert.assertEquals(0, bac.compare(new byte[0], new byte[0]));
    Assert.assertTrue(bac.compare(new byte[0], new byte[] {1}) < 0);
    Assert.assertTrue(bac.compare(new byte[] {1}, new byte[0]) > 0);
  }

  @Test
  public void testV2EmptyArrays() {
    final var v2 = UnsafeByteArrayComparatorV2.INSTANCE;
    Assert.assertEquals(0, v2.compare(new byte[0], new byte[0]));
    // Safe direction: shorter (empty) as first argument.
    Assert.assertTrue(v2.compare(new byte[0], new byte[] {1}) < 0);
  }

  private void assertCompareTwoKeys(
      final Comparator<byte[]> comparator, byte[] keyOne, byte[] keyTwo) {
    Assert.assertTrue(comparator.compare(keyOne, keyTwo) < 0);
    Assert.assertTrue(comparator.compare(keyTwo, keyOne) > 0);
    //noinspection EqualsWithItself
    Assert.assertEquals(0, comparator.compare(keyTwo, keyTwo));
  }
}
