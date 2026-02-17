package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import org.junit.Test;

public class LinkBagValueTest {

  @Test
  public void testValidArguments() {
    var value = new LinkBagValue(1, 0, 0);

    assert value.counter() == 1;
    assert value.secondaryCollectionId() == 0;
    assert value.secondaryPosition() == 0;
  }

  @Test
  public void testValidLargeArguments() {
    var value = new LinkBagValue(42, 100, 999_999L);

    assert value.counter() == 42;
    assert value.secondaryCollectionId() == 100;
    assert value.secondaryPosition() == 999_999L;
  }

  @Test(expected = AssertionError.class)
  public void testNegativeSecondaryCollectionId() {
    new LinkBagValue(1, -1, 0);
  }

  @Test(expected = AssertionError.class)
  public void testNegativeSecondaryPosition() {
    new LinkBagValue(1, 0, -1);
  }

  @Test(expected = AssertionError.class)
  public void testBothNegativeSecondaryFields() {
    new LinkBagValue(1, -1, -1);
  }

  @Test(expected = AssertionError.class)
  public void testNegativeSecondaryCollectionIdWithLargeNegative() {
    new LinkBagValue(1, Integer.MIN_VALUE, 0);
  }

  @Test(expected = AssertionError.class)
  public void testNegativeSecondaryPositionWithLargeNegative() {
    new LinkBagValue(1, 0, Long.MIN_VALUE);
  }
}
