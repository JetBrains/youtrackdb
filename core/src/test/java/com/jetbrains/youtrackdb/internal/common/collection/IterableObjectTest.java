package com.jetbrains.youtrackdb.internal.common.collection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.NoSuchElementException;
import org.junit.Test;

public class IterableObjectTest {

  // =========================================================================
  // IterableObject — single-value iteration
  // =========================================================================

  @Test
  public void testHasNextAndNext() {
    // IterableObject wraps a single value: hasNext is true once, then false.
    var iterable = new IterableObject<>("hello");
    var it = iterable.iterator();
    assertTrue(it.hasNext());
    assertEquals("hello", it.next());
    assertFalse(it.hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void testNextPastEndThrows() {
    // Calling next() after the single value has been read should throw.
    var iterable = new IterableObject<>("hello");
    var it = iterable.iterator();
    it.next(); // consume the value
    it.next(); // should throw
  }

  @Test
  public void testReset() {
    // After reset, the value can be iterated again.
    var iterable = new IterableObject<>("hello");
    var it = iterable.iterator();
    it.next();
    assertFalse(it.hasNext());
    iterable.reset();
    assertTrue(it.hasNext());
    assertEquals("hello", it.next());
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testRemoveThrows() {
    // remove() is not supported.
    var iterable = new IterableObject<>("hello");
    var it = iterable.iterator();
    it.remove();
  }

  @Test
  public void testIsResetable() {
    var iterable = new IterableObject<>("hello");
    assertTrue(iterable.isResetable());
  }

  @Test
  public void testIteratorReturnsSelf() {
    // iterator() returns the IterableObject itself.
    var iterable = new IterableObject<>("hello");
    assertTrue(iterable == iterable.iterator());
  }

  @Test
  public void testForEachLoop() {
    // Should work in a for-each loop (tests the Iterable contract).
    var iterable = new IterableObject<>("value");
    var count = 0;
    for (var v : iterable) {
      assertEquals("value", v);
      count++;
    }
    assertEquals(1, count);
  }

  // =========================================================================
  // IterableObjectArray — array iteration
  // =========================================================================

  @Test
  public void testArrayIteration() {
    // Should iterate through all elements of the array.
    var array = new String[] {"a", "b", "c"};
    var iterable = new IterableObjectArray<String>(array);
    var it = iterable.iterator();
    assertTrue(it.hasNext());
    assertEquals("a", it.next());
    assertTrue(it.hasNext());
    assertEquals("b", it.next());
    assertTrue(it.hasNext());
    assertEquals("c", it.next());
    assertFalse(it.hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void testArrayNextPastEndThrows() {
    var array = new String[] {"a"};
    var iterable = new IterableObjectArray<String>(array);
    var it = iterable.iterator();
    it.next();
    it.next(); // should throw
  }

  @Test
  public void testArrayEmptyIteration() {
    // An empty array should produce an iterator with no elements.
    var array = new String[] {};
    var iterable = new IterableObjectArray<String>(array);
    var it = iterable.iterator();
    assertFalse(it.hasNext());
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testArrayRemoveThrows() {
    var array = new String[] {"a"};
    var iterable = new IterableObjectArray<String>(array);
    var it = iterable.iterator();
    it.remove();
  }

  @Test
  public void testArrayIterationWithPrimitiveArray() {
    // Should handle int[] via reflection-based Array.get().
    var array = new int[] {10, 20, 30};
    var iterable = new IterableObjectArray<Integer>(array);
    var it = iterable.iterator();
    assertEquals(Integer.valueOf(10), it.next());
    assertEquals(Integer.valueOf(20), it.next());
    assertEquals(Integer.valueOf(30), it.next());
    assertFalse(it.hasNext());
  }

  @Test
  public void testArrayForEachLoop() {
    // Should work in a for-each loop via the Iterable contract.
    var array = new String[] {"x", "y"};
    var iterable = new IterableObjectArray<String>(array);
    var result = new java.util.ArrayList<String>();
    for (var v : iterable) {
      result.add(v);
    }
    assertArrayEquals(new String[] {"x", "y"}, result.toArray());
  }

  @Test
  public void testArrayNewIteratorEachCall() {
    // Each call to iterator() should produce an independent iterator.
    var array = new String[] {"a", "b"};
    var iterable = new IterableObjectArray<String>(array);
    var it1 = iterable.iterator();
    it1.next(); // advance first iterator
    var it2 = iterable.iterator();
    // Second iterator should start from the beginning.
    assertEquals("a", it2.next());
  }
}
