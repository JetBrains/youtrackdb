package com.jetbrains.youtrackdb.internal.common.types;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModifiableLongTest {

  // --- Constructors and basic accessors ---

  @Test
  public void testDefaultConstructorInitializesToZero() {
    var ml = new ModifiableLong();
    assertEquals(0L, ml.getValue());
    assertEquals(0L, ml.value);
  }

  @Test
  public void testValueConstructor() {
    var ml = new ModifiableLong(42L);
    assertEquals(42L, ml.getValue());
  }

  @Test
  public void testSetValue() {
    var ml = new ModifiableLong();
    ml.setValue(99L);
    assertEquals(99L, ml.getValue());
  }

  // --- Increment ---

  @Test
  public void testIncrement() {
    var ml = new ModifiableLong(10L);
    ml.increment();
    assertEquals(11L, ml.getValue());
  }

  @Test
  public void testIncrementByValue() {
    var ml = new ModifiableLong(10L);
    ml.increment(5L);
    assertEquals(15L, ml.getValue());
  }

  // --- Decrement ---

  @Test
  public void testDecrement() {
    var ml = new ModifiableLong(10L);
    ml.decrement();
    assertEquals(9L, ml.getValue());
  }

  @Test
  public void testDecrementByValue() {
    var ml = new ModifiableLong(10L);
    ml.decrement(3L);
    assertEquals(7L, ml.getValue());
  }

  // --- compareTo ---

  @Test
  public void testCompareToLessThan() {
    var a = new ModifiableLong(1L);
    var b = new ModifiableLong(2L);
    assertTrue(a.compareTo(b) < 0);
  }

  @Test
  public void testCompareToEqual() {
    var a = new ModifiableLong(5L);
    var b = new ModifiableLong(5L);
    assertEquals(0, a.compareTo(b));
  }

  @Test
  public void testCompareToGreaterThan() {
    var a = new ModifiableLong(10L);
    var b = new ModifiableLong(5L);
    assertTrue(a.compareTo(b) > 0);
  }

  // --- equals / hashCode ---

  @Test
  public void testEqualsSameValue() {
    var a = new ModifiableLong(7L);
    var b = new ModifiableLong(7L);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void testEqualsDifferentValue() {
    var a = new ModifiableLong(7L);
    var b = new ModifiableLong(8L);
    assertNotEquals(a, b);
  }

  @Test
  public void testEqualsWrongType() {
    var ml = new ModifiableLong(7L);
    assertNotEquals("7", ml);
  }

  @Test
  public void testEqualsNull() {
    var ml = new ModifiableLong(7L);
    assertNotEquals(null, ml);
  }

  @Test
  public void testEqualsSameInstance() {
    var ml = new ModifiableLong(7L);
    assertEquals(ml, ml);
  }

  @Test
  public void testHashCodeConsistentWithLong() {
    // hashCode uses Long.valueOf(value).hashCode().
    var ml = new ModifiableLong(42L);
    assertEquals(Long.valueOf(42L).hashCode(), ml.hashCode());
  }

  // --- Number interface methods ---

  @Test
  public void testByteValue() {
    var ml = new ModifiableLong(127L);
    assertEquals((byte) 127, ml.byteValue());
  }

  @Test
  public void testShortValue() {
    var ml = new ModifiableLong(1000L);
    assertEquals((short) 1000, ml.shortValue());
  }

  @Test
  public void testIntValue() {
    var ml = new ModifiableLong(42L);
    assertEquals(42, ml.intValue());
  }

  @Test
  public void testLongValue() {
    var ml = new ModifiableLong(42L);
    assertEquals(42L, ml.longValue());
  }

  @Test
  public void testFloatValue() {
    var ml = new ModifiableLong(42L);
    assertEquals(42.0f, ml.floatValue(), 0.0f);
  }

  @Test
  public void testDoubleValue() {
    var ml = new ModifiableLong(42L);
    assertEquals(42.0, ml.doubleValue(), 0.0);
  }

  // --- toLong / toString ---

  @Test
  public void testToLong() {
    var ml = new ModifiableLong(99L);
    assertEquals(Long.valueOf(99L), ml.toLong());
  }

  @Test
  public void testToString() {
    var ml = new ModifiableLong(123L);
    assertEquals("123", ml.toString());
  }

  // --- Negative values ---

  @Test
  public void testNegativeValueConstructorAndAccessors() {
    var ml = new ModifiableLong(-42L);
    assertEquals(-42L, ml.getValue());
    assertEquals("-42", ml.toString());
  }

  @Test
  public void testCompareToNegativeVsPositive() {
    var neg = new ModifiableLong(-1L);
    var pos = new ModifiableLong(1L);
    assertTrue(neg.compareTo(pos) < 0);
    assertTrue(pos.compareTo(neg) > 0);
  }

  @Test
  public void testEqualsNegativeValues() {
    var a = new ModifiableLong(-7L);
    var b = new ModifiableLong(-7L);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  // --- Overflow / underflow boundaries ---

  @Test
  public void testIncrementAtMaxValue() {
    // Documents long overflow: MAX_VALUE + 1 wraps to MIN_VALUE.
    var ml = new ModifiableLong(Long.MAX_VALUE);
    ml.increment();
    assertEquals(Long.MIN_VALUE, ml.getValue());
  }

  @Test
  public void testDecrementAtMinValue() {
    // Documents long underflow: MIN_VALUE - 1 wraps to MAX_VALUE.
    var ml = new ModifiableLong(Long.MIN_VALUE);
    ml.decrement();
    assertEquals(Long.MAX_VALUE, ml.getValue());
  }
}
