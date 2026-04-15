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

  @Test
  public void testToByteArray() {
    // toByteArray should return the underlying byte array.
    var bytes = new byte[] {10, 20, 30};
    var binary = new Binary(bytes);
    assertArrayEquals(bytes, binary.toByteArray());
  }
}
