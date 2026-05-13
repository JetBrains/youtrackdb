package com.jetbrains.youtrackdb.internal.common.collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.NoSuchElementException;
import org.junit.Test;

public class LazyIteratorListWrapperTest {

  @Test
  public void testHasNextDelegatesToUnderlying() {
    var list = new ArrayList<>(Arrays.asList("a", "b"));
    var wrapper = new LazyIteratorListWrapper<>(list.listIterator());
    assertTrue(wrapper.hasNext());
  }

  @Test
  public void testNextDelegatesToUnderlying() {
    var list = new ArrayList<>(Arrays.asList("a", "b"));
    var wrapper = new LazyIteratorListWrapper<>(list.listIterator());
    assertEquals("a", wrapper.next());
    assertEquals("b", wrapper.next());
    assertFalse(wrapper.hasNext());
  }

  @Test
  public void testRemoveDelegatesToUnderlying() {
    // Calling remove after next should remove the last returned element.
    var list = new ArrayList<>(Arrays.asList("a", "b", "c"));
    var wrapper = new LazyIteratorListWrapper<>(list.listIterator());
    wrapper.next(); // "a"
    wrapper.remove(); // removes "a"
    assertEquals(2, list.size());
    assertEquals("b", list.get(0));
    assertEquals("c", list.get(1));
  }

  @Test
  public void testUpdateDelegatesToSetAndReturnsNull() {
    // update() calls underlying.set() and returns null.
    var list = new ArrayList<>(Arrays.asList("a", "b", "c"));
    var wrapper = new LazyIteratorListWrapper<>(list.listIterator());
    wrapper.next(); // "a"
    var result = wrapper.update("A");
    assertNull(result);
    assertEquals(Arrays.asList("A", "b", "c"), list);
  }

  @Test
  public void testFullIteration() {
    // Iterate through all elements.
    var list = new ArrayList<>(Arrays.asList("x", "y", "z"));
    var wrapper = new LazyIteratorListWrapper<>(list.listIterator());
    var result = new ArrayList<String>();
    while (wrapper.hasNext()) {
      result.add(wrapper.next());
    }
    assertEquals(Arrays.asList("x", "y", "z"), result);
  }

  @Test
  public void testEmptyList() {
    var list = new ArrayList<String>();
    var wrapper = new LazyIteratorListWrapper<>(list.listIterator());
    assertFalse(wrapper.hasNext());
  }

  @Test(expected = NoSuchElementException.class)
  public void testNextOnEmptyThrows() {
    var list = new ArrayList<String>();
    var wrapper = new LazyIteratorListWrapper<>(list.listIterator());
    wrapper.next();
  }
}
