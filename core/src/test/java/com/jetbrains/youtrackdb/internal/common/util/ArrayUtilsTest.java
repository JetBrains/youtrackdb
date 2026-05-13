package com.jetbrains.youtrackdb.internal.common.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArrayUtilsTest {

  // --- copyOf(T[], int) ---

  @Test
  public void testCopyOfObjectArraySameSize() {
    // Copying an Object[] with the same size produces an equal but distinct array.
    var source = new String[] {"a", "b", "c"};
    var result = ArrayUtils.copyOf(source, 3);
    assertArrayEquals(source, result);
    assertNotSame(source, result);
  }

  @Test
  public void testCopyOfObjectArraySmallerSize() {
    // Copying to a smaller size truncates the array.
    var source = new String[] {"a", "b", "c"};
    var result = ArrayUtils.copyOf(source, 2);
    assertArrayEquals(new String[] {"a", "b"}, result);
  }

  @Test
  public void testCopyOfObjectArrayLargerSize() {
    // Copying to a larger size pads with nulls.
    var source = new String[] {"a", "b"};
    var result = ArrayUtils.copyOf(source, 4);
    assertEquals(4, result.length);
    assertEquals("a", result[0]);
    assertEquals("b", result[1]);
    assertEquals(null, result[2]);
    assertEquals(null, result[3]);
  }

  // --- copyOf(U[], int, Class) with typed array ---

  @Test
  public void testCopyOfTypedArray() {
    // Copying with a typed class should produce an array of that type.
    var source = new Object[] {"a", "b"};
    var result = ArrayUtils.copyOf(source, 2, String[].class);
    assertEquals(2, result.length);
    assertEquals("a", result[0]);
    assertEquals("b", result[1]);
    assertTrue(result instanceof String[]);
  }

  @Test
  public void testCopyOfObjectArrayClass() {
    // Copying with Object[].class should produce a plain Object[].
    var source = new String[] {"a", "b"};
    var result = ArrayUtils.copyOf(source, 3, Object[].class);
    assertEquals(3, result.length);
    assertEquals("a", result[0]);
    assertEquals("b", result[1]);
    assertEquals(null, result[2]);
  }

  // --- copyOfRange(S[], int, int) ---

  @Test
  public void testCopyOfRangeObjectArray() {
    // Copying a range should return the sub-array.
    var source = new String[] {"a", "b", "c", "d"};
    var result = ArrayUtils.copyOfRange(source, 1, 3);
    assertArrayEquals(new String[] {"b", "c"}, result);
  }

  @Test
  public void testCopyOfRangeObjectArrayFullRange() {
    var source = new String[] {"a", "b"};
    var result = ArrayUtils.copyOfRange(source, 0, 2);
    assertArrayEquals(source, result);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCopyOfRangeObjectArrayInvalidRange() {
    // begin > end should throw IllegalArgumentException.
    var source = new String[] {"a", "b"};
    ArrayUtils.copyOfRange(source, 2, 1);
  }

  // --- copyOfRange(S[], int, int, Class) with typed array ---

  @Test
  public void testCopyOfRangeWithTypedClass() {
    var source = new Object[] {"a", "b", "c"};
    var result = ArrayUtils.copyOfRange(source, 0, 2, String[].class);
    assertEquals(2, result.length);
    assertEquals("a", result[0]);
    assertEquals("b", result[1]);
    assertTrue(result instanceof String[]);
  }

  @Test
  public void testCopyOfRangeWithObjectArrayClass() {
    var source = new String[] {"a", "b", "c"};
    var result = ArrayUtils.copyOfRange(source, 1, 3, Object[].class);
    assertEquals(2, result.length);
    assertEquals("b", result[0]);
    assertEquals("c", result[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCopyOfRangeWithClassInvalidRange() {
    ArrayUtils.copyOfRange(new String[] {"a"}, 3, 1, String[].class);
  }

  // --- copyOfRange(byte[], int, int) ---

  @Test
  public void testCopyOfRangeByteArray() {
    var source = new byte[] {1, 2, 3, 4, 5};
    var result = ArrayUtils.copyOfRange(source, 1, 4);
    assertArrayEquals(new byte[] {2, 3, 4}, result);
  }

  @Test
  public void testCopyOfRangeByteArrayFullRange() {
    var source = new byte[] {10, 20};
    var result = ArrayUtils.copyOfRange(source, 0, 2);
    assertArrayEquals(source, result);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCopyOfRangeByteArrayInvalidRange() {
    ArrayUtils.copyOfRange(new byte[] {1, 2}, 3, 1);
  }

  // --- copyOf(int[], int) ---

  @Test
  public void testCopyOfIntArraySameSize() {
    var source = new int[] {1, 2, 3};
    var result = ArrayUtils.copyOf(source, 3);
    assertArrayEquals(source, result);
  }

  @Test
  public void testCopyOfIntArraySmallerSize() {
    var source = new int[] {1, 2, 3};
    var result = ArrayUtils.copyOf(source, 2);
    assertArrayEquals(new int[] {1, 2}, result);
  }

  @Test
  public void testCopyOfIntArrayLargerSize() {
    var source = new int[] {1, 2};
    var result = ArrayUtils.copyOf(source, 4);
    assertArrayEquals(new int[] {1, 2, 0, 0}, result);
  }

  // --- contains(int[], int) ---

  @Test
  public void testContainsIntArrayFound() {
    assertTrue(ArrayUtils.contains(new int[] {1, 2, 3}, 2));
  }

  @Test
  public void testContainsIntArrayNotFound() {
    assertFalse(ArrayUtils.contains(new int[] {1, 2, 3}, 5));
  }

  @Test
  public void testContainsIntArrayNull() {
    // Null array should return false.
    assertFalse(ArrayUtils.contains((int[]) null, 1));
  }

  @Test
  public void testContainsIntArrayEmpty() {
    assertFalse(ArrayUtils.contains(new int[] {}, 1));
  }

  // --- contains(T[], T) ---

  @Test
  public void testContainsObjectArrayFound() {
    assertTrue(ArrayUtils.contains(new String[] {"a", "b", "c"}, "b"));
  }

  @Test
  public void testContainsObjectArrayNotFound() {
    assertFalse(ArrayUtils.contains(new String[] {"a", "b"}, "z"));
  }

  @Test
  public void testContainsObjectArrayNull() {
    // Null array should return false.
    assertFalse(ArrayUtils.contains((String[]) null, "a"));
  }

  @Test
  public void testContainsObjectArrayNullElement() {
    // Null elements in the array are skipped; searching for a non-null value.
    assertFalse(ArrayUtils.contains(new String[] {null, null}, "a"));
  }

  @Test
  public void testContainsObjectArrayWithNullsAndMatch() {
    // Array has nulls but also contains the search target.
    assertTrue(ArrayUtils.contains(new String[] {null, "a", null}, "a"));
  }

  // --- hash(Object[]) ---

  @Test
  public void testHashNonNullElements() {
    // Hash should be the sum of element hashCodes.
    var arr = new Object[] {"a", "b"};
    var expected = "a".hashCode() + "b".hashCode();
    assertEquals(expected, ArrayUtils.hash(arr));
  }

  @Test
  public void testHashWithNullElements() {
    // Null elements should be skipped (contribute 0).
    var arr = new Object[] {"a", null, "b"};
    var expected = "a".hashCode() + "b".hashCode();
    assertEquals(expected, ArrayUtils.hash(arr));
  }

  @Test
  public void testHashEmptyArray() {
    assertEquals(0, ArrayUtils.hash(new Object[] {}));
  }

  @Test
  public void testHashAllNulls() {
    assertEquals(0, ArrayUtils.hash(new Object[] {null, null}));
  }

  // --- Boundary: contains(T[], null) ---

  @Test
  public void testContainsObjectArraySearchingForNullReturnsFalse() {
    // contains(T[], T) skips null elements, so searching for null always
    // returns false even when null is present. This documents the limitation.
    assertFalse(ArrayUtils.contains(new String[] {null, "a", null}, null));
  }

  // --- Boundary: copyOf with zero-length target ---

  @Test
  public void testCopyOfObjectArrayZeroSize() {
    // Copying to size 0 should produce an empty array.
    var source = new String[] {"a", "b"};
    var result = ArrayUtils.copyOf(source, 0);
    assertEquals(0, result.length);
  }

  @Test
  public void testCopyOfIntArrayZeroSize() {
    var source = new int[] {1, 2, 3};
    var result = ArrayUtils.copyOf(source, 0);
    assertEquals(0, result.length);
  }

  // --- Boundary: copyOfRange with empty range (begin == end) ---

  @Test
  public void testCopyOfRangeObjectArrayEmptyRange() {
    // begin == end should produce an empty array, not throw.
    var source = new String[] {"a", "b"};
    var result = ArrayUtils.copyOfRange(source, 1, 1);
    assertEquals(0, result.length);
  }

  @Test
  public void testCopyOfRangeByteArrayEmptyRange() {
    var source = new byte[] {1, 2, 3};
    var result = ArrayUtils.copyOfRange(source, 2, 2);
    assertEquals(0, result.length);
  }

  // --- Boundary: copyOfRange with end beyond source length ---

  @Test
  public void testCopyOfRangeObjectArrayEndBeyondSourceLength() {
    // When end > source.length, result is padded with nulls.
    var source = new String[] {"a", "b"};
    var result = ArrayUtils.copyOfRange(source, 1, 5);
    assertEquals(4, result.length);
    assertEquals("b", result[0]);
    assertNull(result[1]);
    assertNull(result[2]);
    assertNull(result[3]);
  }

  @Test
  public void testCopyOfRangeByteArrayEndBeyondSourceLength() {
    // When end > source.length, result is padded with zeros.
    var source = new byte[] {10, 20};
    var result = ArrayUtils.copyOfRange(source, 0, 5);
    assertEquals(5, result.length);
    assertEquals(10, result[0]);
    assertEquals(20, result[1]);
    assertEquals(0, result[2]);
  }
}
