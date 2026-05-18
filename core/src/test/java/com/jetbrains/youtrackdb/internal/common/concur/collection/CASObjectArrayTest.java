package com.jetbrains.youtrackdb.internal.common.concur.collection;

import org.junit.Assert;
import org.junit.Test;

public class CASObjectArrayTest {

  // --- add(T, int) ---

  /**
   * Verifies that add(value, index) succeeds and returns true when the current
   * size matches the requested index — the caller is appending at the expected
   * position.
   */
  @Test
  public void testAddWithIndexSucceedsWhenSizeMatchesIndex() {
    final var array = new CASObjectArray<Integer>();
    array.add(10);
    array.add(20);
    // size == 2, requesting index 2 → should succeed
    Assert.assertTrue(array.add(42, 2));
    Assert.assertEquals(3, array.size());
    Assert.assertEquals(42, array.get(2).intValue());
  }

  /**
   * Verifies that add(value, index) returns false without modifying the array
   * when the current size is larger than the requested index (stale index) or
   * smaller than it (gap).
   */
  @Test
  public void testAddWithIndexFailsWhenSizeDoesNotMatchIndex() {
    final var array = new CASObjectArray<Integer>();
    array.add(10); // size = 1

    // size (1) > requested index (0) — stale index
    Assert.assertFalse(array.add(42, 0));
    // size (1) < requested index (5) — gap
    Assert.assertFalse(array.add(42, 5));

    // Array must remain unchanged — only the original element
    Assert.assertEquals(1, array.size());
    Assert.assertEquals(10, array.get(0).intValue());
  }

  // --- set() expansion path ---

  /**
   * Verifies that set() correctly expands the array when the target index is
   * beyond the current size: gap slots are filled with placeholders, and the
   * actual value is written directly at the target index via add(value, index).
   */
  @Test
  public void testSetExpandsArrayAndWritesValueAtTargetIndex() {
    final var array = new CASObjectArray<Integer>();
    // Array is empty (size 0). Setting index 3 must expand: fill 0,1,2
    // with placeholders, then write the value at index 3.
    array.set(3, 42, -1);

    Assert.assertEquals(4, array.size());
    Assert.assertEquals(-1, array.get(0).intValue());
    Assert.assertEquals(-1, array.get(1).intValue());
    Assert.assertEquals(-1, array.get(2).intValue());
    Assert.assertEquals(42, array.get(3).intValue());
  }

  /**
   * Verifies that set() falls through to the overwrite path when size is
   * already past the target index — the gap-filling loop is skipped and the
   * existing value at the target index is simply overwritten.
   */
  @Test
  public void testSetOverwritesWhenSizeAlreadyPastIndex() {
    final var array = new CASObjectArray<Integer>();
    array.add(10);
    array.add(20);
    array.add(30); // size = 3

    // Target index 1 is well below size → no expansion, direct overwrite
    array.set(1, 99, -1);

    Assert.assertEquals(3, array.size());
    Assert.assertEquals(10, array.get(0).intValue());
    Assert.assertEquals(99, array.get(1).intValue());
    Assert.assertEquals(30, array.get(2).intValue());
  }

  @Test
  public void testAddSingleItem() {
    final var array = new CASObjectArray<Integer>();
    Assert.assertEquals(0, array.size());

    Assert.assertEquals(0, array.add(1));
    Assert.assertEquals(1, array.size());
    Assert.assertEquals(1, array.get(0).intValue());
  }

  @Test
  public void testAddTwoItems() {
    final var array = new CASObjectArray<Integer>();
    Assert.assertEquals(0, array.size());

    Assert.assertEquals(0, array.add(1));
    Assert.assertEquals(1, array.size());

    Assert.assertEquals(1, array.add(2));
    Assert.assertEquals(2, array.size());

    Assert.assertEquals(1, array.get(0).intValue());
    Assert.assertEquals(2, array.get(1).intValue());
  }

  @Test
  public void testAddThreeItems() {
    final var array = new CASObjectArray<Integer>();
    Assert.assertEquals(0, array.size());

    Assert.assertEquals(0, array.add(1));
    Assert.assertEquals(1, array.size());

    Assert.assertEquals(1, array.add(2));
    Assert.assertEquals(2, array.size());

    Assert.assertEquals(2, array.add(3));
    Assert.assertEquals(3, array.size());

    Assert.assertEquals(1, array.get(0).intValue());
    Assert.assertEquals(2, array.get(1).intValue());
    Assert.assertEquals(3, array.get(2).intValue());
  }

  @Test
  public void testAdd12Items() {
    final var array = new CASObjectArray<Integer>();

    for (var i = 0; i < 12; i++) {
      array.add(i + 1);
      Assert.assertEquals(i + 1, array.size());
    }

    for (var i = 0; i < 12; i++) {
      Assert.assertEquals(i + 1, array.get(i).intValue());
    }
  }

  @Test
  public void testSetSingleItem() {
    final var array = new CASObjectArray<Integer>();

    Assert.assertEquals(0, array.add(1));
    array.set(0, 21, -1);

    Assert.assertEquals(21, array.get(0).intValue());
  }

  @Test
  public void testSetTwoItems() {
    final var array = new CASObjectArray<Integer>();

    Assert.assertEquals(0, array.add(1));
    Assert.assertEquals(1, array.add(2));

    array.set(0, 21, -1);
    array.set(1, 22, -1);

    Assert.assertEquals(21, array.get(0).intValue());
    Assert.assertEquals(22, array.get(1).intValue());
  }

  @Test
  public void testSetThreeItems() {
    final var array = new CASObjectArray<Integer>();

    Assert.assertEquals(0, array.add(1));
    Assert.assertEquals(1, array.add(2));
    Assert.assertEquals(2, array.add(3));

    array.set(0, 21, -1);
    array.set(1, 22, -1);
    array.set(2, 23, -1);

    Assert.assertEquals(21, array.get(0).intValue());
    Assert.assertEquals(22, array.get(1).intValue());
    Assert.assertEquals(23, array.get(2).intValue());
  }

  @Test
  public void testSet12Items() {
    final var array = new CASObjectArray<Integer>();

    for (var i = 0; i < 12; i++) {
      Assert.assertEquals(i, array.add(i + 1));
      Assert.assertEquals(i + 1, array.size());
    }

    for (var i = 0; i < 12; i++) {
      array.set(i, 21 + i, -1);
    }

    for (var i = 0; i < 12; i++) {
      Assert.assertEquals(i + 21, array.get(i).intValue());
    }
  }

  @Test
  public void testCompareAndSetSingleItem() {
    final var array = new CASObjectArray<Integer>();

    Assert.assertEquals(0, array.add(1));
    Assert.assertFalse(array.compareAndSet(0, 12, 21));
    Assert.assertEquals(1, array.get(0).intValue());

    Assert.assertTrue(array.compareAndSet(0, 1, 22));
    Assert.assertEquals(22, array.get(0).intValue());
  }

  @Test
  public void testCompareAndSetTwoItems() {
    final var array = new CASObjectArray<Integer>();

    Assert.assertEquals(0, array.add(1));
    Assert.assertEquals(1, array.add(2));

    Assert.assertFalse(array.compareAndSet(0, 22, 21));
    Assert.assertEquals(1, array.get(0).intValue());
    Assert.assertTrue(array.compareAndSet(0, 1, 21));

    Assert.assertFalse(array.compareAndSet(1, 23, 22));
    Assert.assertEquals(2, array.get(1).intValue());
    Assert.assertTrue(array.compareAndSet(1, 2, 22));

    Assert.assertEquals(21, array.get(0).intValue());
    Assert.assertEquals(22, array.get(1).intValue());
  }

  @Test
  public void testCompareAndSetThreeItems() {
    final var array = new CASObjectArray<Integer>();

    Assert.assertEquals(0, array.add(1));
    Assert.assertEquals(1, array.add(2));
    Assert.assertEquals(2, array.add(3));

    Assert.assertFalse(array.compareAndSet(0, 22, 21));
    Assert.assertEquals(1, array.get(0).intValue());
    Assert.assertTrue(array.compareAndSet(0, 1, 21));

    Assert.assertFalse(array.compareAndSet(1, 23, 22));
    Assert.assertEquals(2, array.get(1).intValue());
    Assert.assertTrue(array.compareAndSet(1, 2, 22));

    Assert.assertFalse(array.compareAndSet(2, 24, 23));
    Assert.assertEquals(3, array.get(2).intValue());
    Assert.assertTrue(array.compareAndSet(2, 3, 23));

    Assert.assertEquals(21, array.get(0).intValue());
    Assert.assertEquals(22, array.get(1).intValue());
    Assert.assertEquals(23, array.get(2).intValue());
  }

  @Test
  public void testCompareAndSet12Items() {
    final var array = new CASObjectArray<Integer>();

    for (var i = 0; i < 12; i++) {
      Assert.assertEquals(i, array.add(i + 1));
      Assert.assertEquals(i + 1, array.size());
    }

    for (var i = 0; i < 12; i++) {
      Assert.assertFalse(array.compareAndSet(i, 22 + i, 21 + i));
    }

    for (var i = 0; i < 12; i++) {
      Assert.assertEquals(i + 1, array.get(i).intValue());
    }

    for (var i = 0; i < 12; i++) {
      Assert.assertTrue(array.compareAndSet(i, i + 1, 21 + i));
    }

    for (var i = 0; i < 12; i++) {
      Assert.assertEquals(i + 21, array.get(i).intValue());
    }
  }
}
