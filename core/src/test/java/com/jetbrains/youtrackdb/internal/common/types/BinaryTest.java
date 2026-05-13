package com.jetbrains.youtrackdb.internal.common.types;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

@SuppressWarnings("deprecation")
public class BinaryTest {

  @Test
  public void testCompareToEqualArrays() {
    // Two Binary objects with identical byte content should compare as 0.
    var b1 = new Binary(new byte[] {1, 2, 3});
    var b2 = new Binary(new byte[] {1, 2, 3});
    assertEquals(0, b1.compareTo(b2));
  }

  @Test
  public void testCompareToFirstLess() {
    // First Binary has a smaller byte at some position — should be negative.
    var b1 = new Binary(new byte[] {1, 2, 3});
    var b2 = new Binary(new byte[] {1, 3, 3});
    assertTrue(b1.compareTo(b2) < 0);
  }

  @Test
  public void testCompareToFirstGreater() {
    // First Binary has a larger byte at some position — should be positive.
    var b1 = new Binary(new byte[] {1, 5, 3});
    var b2 = new Binary(new byte[] {1, 2, 3});
    assertTrue(b1.compareTo(b2) > 0);
  }

  @Test
  public void testCompareToFirstByteDiffers() {
    // Difference at the very first byte.
    var b1 = new Binary(new byte[] {10, 0, 0});
    var b2 = new Binary(new byte[] {20, 0, 0});
    assertTrue(b1.compareTo(b2) < 0);
  }

  @Test
  public void testCompareToLastByteDiffers() {
    // Difference at the last byte.
    var b1 = new Binary(new byte[] {1, 2, 3});
    var b2 = new Binary(new byte[] {1, 2, 4});
    assertTrue(b1.compareTo(b2) < 0);
  }

  @Test
  public void testCompareToSingleByte() {
    var b1 = new Binary(new byte[] {5});
    var b2 = new Binary(new byte[] {5});
    assertEquals(0, b1.compareTo(b2));
  }

  // --- Boundary: empty arrays ---

  @Test
  public void testCompareToEmptyArrays() {
    // Two empty Binary objects should compare as equal.
    var b1 = new Binary(new byte[0]);
    var b2 = new Binary(new byte[0]);
    assertEquals(0, b1.compareTo(b2));
  }

  @Test
  public void testCompareToEmptyVsNonEmpty() {
    // Empty this is shorter — loop doesn't execute, returns 0.
    // Documents the length-mismatch behavior of this deprecated class.
    var empty = new Binary(new byte[0]);
    var nonEmpty = new Binary(new byte[] {1});
    assertEquals(0, empty.compareTo(nonEmpty));
  }

  // --- Boundary: different-length arrays ---

  @Test
  public void testCompareToShorterThisReturnsMisleadingZero() {
    // When this is shorter than other, the method only compares up to
    // this.length and returns 0 for common-prefix inputs. This documents
    // a known limitation of this deprecated class.
    var shorter = new Binary(new byte[] {1, 2});
    var longer = new Binary(new byte[] {1, 2, 3});
    assertEquals(0, shorter.compareTo(longer));
  }

  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void testCompareToLongerThisThrowsWhenOtherIsShorter() {
    // When this is longer than other, the loop accesses beyond other's
    // bounds. Documents a known limitation of this deprecated class.
    var longer = new Binary(new byte[] {1, 2, 3});
    var shorter = new Binary(new byte[] {1, 2});
    longer.compareTo(shorter);
  }

  @Test
  public void testToByteArray() {
    // toByteArray should return the underlying byte array.
    var bytes = new byte[] {10, 20, 30};
    var binary = new Binary(bytes);
    assertArrayEquals(bytes, binary.toByteArray());
  }
}
