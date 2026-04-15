package com.jetbrains.youtrackdb.internal.common.util;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

public class CollectionsTest {

  // --- indexOf(List, T, Comparator) ---

  @Test
  public void testIndexOfListWithComparatorFound() {
    // Should return the index of the first matching element.
    List<String> list = Arrays.asList("alpha", "beta", "gamma");
    int index = Collections.indexOf(list, "beta", Comparator.naturalOrder());
    assertEquals(1, index);
  }

  @Test
  public void testIndexOfListWithComparatorNotFound() {
    // Should return -1 when no element matches.
    List<String> list = Arrays.asList("alpha", "beta", "gamma");
    int index = Collections.indexOf(list, "delta", Comparator.naturalOrder());
    assertEquals(-1, index);
  }

  @Test
  public void testIndexOfListWithComparatorEmpty() {
    // Should return -1 for an empty list.
    List<String> list = new ArrayList<>();
    int index = Collections.indexOf(list, "any", Comparator.naturalOrder());
    assertEquals(-1, index);
  }

  @Test
  public void testIndexOfListWithComparatorFirstMatch() {
    // Should return the first match when duplicates exist.
    List<String> list = Arrays.asList("a", "b", "b", "c");
    int index = Collections.indexOf(list, "b", Comparator.naturalOrder());
    assertEquals(1, index);
  }

  @Test
  public void testIndexOfListWithCustomComparator() {
    // A case-insensitive comparator should find "BETA" in a lowercase list.
    List<String> list = Arrays.asList("alpha", "beta", "gamma");
    int index =
        Collections.indexOf(list, "BETA", String.CASE_INSENSITIVE_ORDER);
    assertEquals(1, index);
  }

  // --- indexOf(Object[], Comparable) ---

  @Test
  public void testIndexOfArrayFound() {
    Object[] array = {"alpha", "beta", "gamma"};
    int index = Collections.indexOf(array, "beta");
    assertEquals(1, index);
  }

  @Test
  public void testIndexOfArrayNotFound() {
    Object[] array = {"alpha", "beta", "gamma"};
    int index = Collections.indexOf(array, "delta");
    assertEquals(-1, index);
  }

  @Test
  public void testIndexOfArrayEmpty() {
    Object[] array = {};
    int index = Collections.indexOf(array, "any");
    assertEquals(-1, index);
  }

  @Test
  public void testIndexOfArrayFirstElement() {
    Object[] array = {"x", "y", "z"};
    int index = Collections.indexOf(array, "x");
    assertEquals(0, index);
  }

  @Test
  public void testIndexOfArrayLastElement() {
    Object[] array = {"x", "y", "z"};
    int index = Collections.indexOf(array, "z");
    assertEquals(2, index);
  }

  // --- indexOf(int[], int) ---

  @Test
  public void testIndexOfIntArrayFound() {
    int[] array = {10, 20, 30};
    assertEquals(1, Collections.indexOf(array, 20));
  }

  @Test
  public void testIndexOfIntArrayNotFound() {
    int[] array = {10, 20, 30};
    assertEquals(-1, Collections.indexOf(array, 99));
  }

  @Test
  public void testIndexOfIntArrayEmpty() {
    int[] array = {};
    assertEquals(-1, Collections.indexOf(array, 1));
  }

  @Test
  public void testIndexOfIntArrayFirstElement() {
    int[] array = {5, 10, 15};
    assertEquals(0, Collections.indexOf(array, 5));
  }

  // --- toString(Iterable) ---

  @Test
  public void testToStringEmpty() {
    // Empty iterable should produce "[]".
    List<String> list = new ArrayList<>();
    assertEquals("[]", Collections.toString(list));
  }

  @Test
  public void testToStringSingleElement() {
    List<String> list = java.util.Collections.singletonList("hello");
    assertEquals("[hello]", Collections.toString(list));
  }

  @Test
  public void testToStringMultipleElements() {
    List<String> list = Arrays.asList("a", "b", "c");
    assertEquals("[a,b,c]", Collections.toString(list));
  }

  @Test
  public void testToStringIntegers() {
    // Should work with non-String types via Object.toString().
    List<Integer> list = Arrays.asList(1, 2, 3);
    assertEquals("[1,2,3]", Collections.toString(list));
  }
}
