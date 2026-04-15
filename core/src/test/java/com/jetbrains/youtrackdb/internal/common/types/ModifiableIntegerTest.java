package com.jetbrains.youtrackdb.internal.common.types;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModifiableIntegerTest {

  // --- Constructors and basic accessors ---

  @Test
  public void testDefaultConstructorInitializesToZero() {
    var mi = new ModifiableInteger();
    assertEquals(0, mi.getValue());
    assertEquals(0, mi.value);
  }

  @Test
  public void testValueConstructor() {
    var mi = new ModifiableInteger(42);
    assertEquals(42, mi.getValue());
  }

  @Test
  public void testSetValue() {
    var mi = new ModifiableInteger();
    mi.setValue(99);
    assertEquals(99, mi.getValue());
  }

  // --- Increment ---

  @Test
  public void testIncrement() {
    var mi = new ModifiableInteger(10);
    mi.increment();
    assertEquals(11, mi.getValue());
  }

  @Test
  public void testIncrementByValue() {
    var mi = new ModifiableInteger(10);
    mi.increment(5);
    assertEquals(15, mi.getValue());
  }

  @Test
  public void testIncrementWithMaxClamping() {
    // increment(value, max) should cap at max.
    var mi = new ModifiableInteger(90);
    mi.increment(20, 100);
    assertEquals(100, mi.getValue());
  }

  @Test
  public void testIncrementWithMaxNoClampNeeded() {
    // When result is below max, no clamping should occur.
    var mi = new ModifiableInteger(10);
    mi.increment(5, 100);
    assertEquals(15, mi.getValue());
  }

  @Test
  public void testIncrementWithMaxExactlyAtMax() {
    // When result exactly equals max, should stay at max.
    var mi = new ModifiableInteger(95);
    mi.increment(5, 100);
    assertEquals(100, mi.getValue());
  }

  // --- Decrement ---

  @Test
  public void testDecrement() {
    var mi = new ModifiableInteger(10);
    mi.decrement();
    assertEquals(9, mi.getValue());
  }

  @Test
  public void testDecrementByValue() {
    var mi = new ModifiableInteger(10);
    mi.decrement(3);
    assertEquals(7, mi.getValue());
  }

  // --- compareTo ---

  @Test
  public void testCompareToLessThan() {
    var a = new ModifiableInteger(1);
    var b = new ModifiableInteger(2);
    assertTrue(a.compareTo(b) < 0);
  }

  @Test
  public void testCompareToEqual() {
    var a = new ModifiableInteger(5);
    var b = new ModifiableInteger(5);
    assertEquals(0, a.compareTo(b));
  }

  @Test
  public void testCompareToGreaterThan() {
    var a = new ModifiableInteger(10);
    var b = new ModifiableInteger(5);
    assertTrue(a.compareTo(b) > 0);
  }

  // --- equals / hashCode ---

  @Test
  public void testEqualsSameValue() {
    var a = new ModifiableInteger(7);
    var b = new ModifiableInteger(7);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void testEqualsDifferentValue() {
    var a = new ModifiableInteger(7);
    var b = new ModifiableInteger(8);
    assertNotEquals(a, b);
  }

  @Test
  public void testEqualsWrongType() {
    var mi = new ModifiableInteger(7);
    assertNotEquals("7", mi);
  }

  @Test
  public void testEqualsNull() {
    var mi = new ModifiableInteger(7);
    assertNotEquals(null, mi);
  }

  @Test
  public void testEqualsSameInstance() {
    var mi = new ModifiableInteger(7);
    assertEquals(mi, mi);
  }

  @Test
  public void testHashCode() {
    // hashCode returns the integer value itself.
    var mi = new ModifiableInteger(42);
    assertEquals(42, mi.hashCode());
  }

  // --- Number interface methods ---

  @Test
  public void testByteValue() {
    var mi = new ModifiableInteger(127);
    assertEquals((byte) 127, mi.byteValue());
  }

  @Test
  public void testShortValue() {
    var mi = new ModifiableInteger(1000);
    assertEquals((short) 1000, mi.shortValue());
  }

  @Test
  public void testIntValue() {
    var mi = new ModifiableInteger(42);
    assertEquals(42, mi.intValue());
  }

  @Test
  public void testLongValue() {
    var mi = new ModifiableInteger(42);
    assertEquals(42L, mi.longValue());
  }

  @Test
  public void testFloatValue() {
    var mi = new ModifiableInteger(42);
    assertEquals(42.0f, mi.floatValue(), 0.0f);
  }

  @Test
  public void testDoubleValue() {
    var mi = new ModifiableInteger(42);
    assertEquals(42.0, mi.doubleValue(), 0.0);
  }

  // --- toInteger / toString ---

  @Test
  public void testToInteger() {
    var mi = new ModifiableInteger(99);
    assertEquals(Integer.valueOf(99), mi.toInteger());
  }

  @Test
  public void testToString() {
    var mi = new ModifiableInteger(123);
    assertEquals("123", mi.toString());
  }

  // --- Negative values ---

  @Test
  public void testNegativeValueConstructorAndAccessors() {
    var mi = new ModifiableInteger(-42);
    assertEquals(-42, mi.getValue());
    assertEquals("-42", mi.toString());
    assertEquals(-42, mi.hashCode());
  }

  @Test
  public void testCompareToNegativeVsPositive() {
    var neg = new ModifiableInteger(-5);
    var pos = new ModifiableInteger(5);
    assertTrue(neg.compareTo(pos) < 0);
    assertTrue(pos.compareTo(neg) > 0);
  }

  @Test
  public void testEqualsNegativeValues() {
    var a = new ModifiableInteger(-7);
    var b = new ModifiableInteger(-7);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  // --- Overflow / underflow boundaries ---

  @Test
  public void testIncrementOverflow() {
    // Documents integer overflow behavior: MAX_VALUE + 1 wraps to MIN_VALUE.
    var mi = new ModifiableInteger(Integer.MAX_VALUE);
    mi.increment();
    assertEquals(Integer.MIN_VALUE, mi.getValue());
  }

  @Test
  public void testDecrementUnderflow() {
    // Documents integer underflow behavior: MIN_VALUE - 1 wraps to MAX_VALUE.
    var mi = new ModifiableInteger(Integer.MIN_VALUE);
    mi.decrement();
    assertEquals(Integer.MAX_VALUE, mi.getValue());
  }

  @Test
  public void testIncrementWithMaxClampBypassedByOverflow() {
    // Documents that when value + iValue overflows, Math.min returns the
    // overflowed negative value, bypassing the max clamp.
    var mi = new ModifiableInteger(Integer.MAX_VALUE);
    mi.increment(1, 100);
    assertEquals(Integer.MIN_VALUE, mi.getValue());
  }
}
