package com.jetbrains.youtrackdb.internal.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Tests for all RawPair/RawTriple variants: the generic record types
 * (RawPair, RawTriple) and the primitive-field specializations
 * (RawPairIntegerBoolean, RawPairIntegerInteger, RawPairIntegerObject,
 * RawPairLongInteger, RawPairLongLong, RawPairLongObject,
 * RawPairObjectInteger, PairIntegerObject, PairLongObject).
 */
public class RawPairVariantsTest {

  // =========================================================================
  // RawPair (Java record)
  // =========================================================================

  @Test
  public void testRawPairAccessors() {
    // Verifies that the record accessors return the constructed values.
    var pair = new RawPair<>("hello", 42);
    assertEquals("hello", pair.first());
    assertEquals(Integer.valueOf(42), pair.second());
    assertEquals("hello", pair.getFirst());
    assertEquals(Integer.valueOf(42), pair.getSecond());
  }

  @Test
  public void testRawPairEqualsIdentical() {
    // Two RawPair records with same components should be equal.
    var p1 = new RawPair<>("a", 1);
    var p2 = new RawPair<>("a", 1);
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testRawPairEqualsDifferentFirst() {
    var p1 = new RawPair<>("a", 1);
    var p2 = new RawPair<>("b", 1);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairEqualsDifferentSecond() {
    var p1 = new RawPair<>("a", 1);
    var p2 = new RawPair<>("a", 2);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairEqualsNull() {
    var pair = new RawPair<>("a", 1);
    assertNotEquals(null, pair);
  }

  @Test
  public void testRawPairEqualsDifferentType() {
    var pair = new RawPair<>("a", 1);
    assertNotEquals("a", pair);
  }

  // =========================================================================
  // RawTriple (Java record)
  // =========================================================================

  @Test
  public void testRawTripleAccessors() {
    var triple = new RawTriple<>("a", 1, true);
    assertEquals("a", triple.first());
    assertEquals(Integer.valueOf(1), triple.second());
    assertEquals(true, triple.third());
  }

  @Test
  public void testRawTripleStaticFactory() {
    // RawTriple.of should produce the same result as the constructor.
    var t1 = new RawTriple<>("a", 1, true);
    var t2 = RawTriple.of("a", 1, true);
    assertEquals(t1, t2);
  }

  @Test
  public void testRawTripleEqualsIdentical() {
    var t1 = new RawTriple<>("a", 1, true);
    var t2 = new RawTriple<>("a", 1, true);
    assertEquals(t1, t2);
    assertEquals(t1.hashCode(), t2.hashCode());
  }

  @Test
  public void testRawTripleEqualsDifferentThird() {
    var t1 = new RawTriple<>("a", 1, true);
    var t2 = new RawTriple<>("a", 1, false);
    assertNotEquals(t1, t2);
  }

  // =========================================================================
  // RawPairIntegerBoolean
  // =========================================================================

  @Test
  public void testRawPairIntegerBooleanAccessors() {
    var pair = new RawPairIntegerBoolean(5, true);
    assertEquals(5, pair.getFirst());
    assertEquals(true, pair.getSecond());
    assertEquals(5, pair.first);
    assertEquals(true, pair.second);
  }

  @Test
  public void testRawPairIntegerBooleanEqualsIdentical() {
    var p1 = new RawPairIntegerBoolean(5, true);
    var p2 = new RawPairIntegerBoolean(5, true);
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testRawPairIntegerBooleanEqualsSameInstance() {
    var pair = new RawPairIntegerBoolean(5, true);
    assertEquals(pair, pair);
  }

  @Test
  public void testRawPairIntegerBooleanNotEqualDifferentFirst() {
    var p1 = new RawPairIntegerBoolean(5, true);
    var p2 = new RawPairIntegerBoolean(6, true);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairIntegerBooleanNotEqualDifferentSecond() {
    var p1 = new RawPairIntegerBoolean(5, true);
    var p2 = new RawPairIntegerBoolean(5, false);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairIntegerBooleanNotEqualNull() {
    var pair = new RawPairIntegerBoolean(5, true);
    assertNotEquals(null, pair);
  }

  @Test
  public void testRawPairIntegerBooleanNotEqualDifferentType() {
    var pair = new RawPairIntegerBoolean(5, true);
    assertNotEquals("not a pair", pair);
  }

  @Test
  public void testRawPairIntegerBooleanHashCodeDiffers() {
    // Pairs with different second values should (usually) have different hashes.
    var p1 = new RawPairIntegerBoolean(5, true);
    var p2 = new RawPairIntegerBoolean(5, false);
    assertNotEquals(p1.hashCode(), p2.hashCode());
  }

  // =========================================================================
  // RawPairIntegerInteger
  // =========================================================================

  @Test
  public void testRawPairIntegerIntegerAccessors() {
    var pair = new RawPairIntegerInteger(10, 20);
    assertEquals(10, pair.getFirst());
    assertEquals(20, pair.getSecond());
  }

  @Test
  public void testRawPairIntegerIntegerEqualsIdentical() {
    var p1 = new RawPairIntegerInteger(10, 20);
    var p2 = new RawPairIntegerInteger(10, 20);
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testRawPairIntegerIntegerSameInstance() {
    var pair = new RawPairIntegerInteger(1, 2);
    assertEquals(pair, pair);
  }

  @Test
  public void testRawPairIntegerIntegerNotEqualDifferentFirst() {
    var p1 = new RawPairIntegerInteger(10, 20);
    var p2 = new RawPairIntegerInteger(11, 20);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairIntegerIntegerNotEqualDifferentSecond() {
    var p1 = new RawPairIntegerInteger(10, 20);
    var p2 = new RawPairIntegerInteger(10, 21);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairIntegerIntegerNotEqualNull() {
    assertNotEquals(null, new RawPairIntegerInteger(1, 2));
  }

  @Test
  public void testRawPairIntegerIntegerNotEqualDifferentType() {
    assertNotEquals("string", new RawPairIntegerInteger(1, 2));
  }

  // =========================================================================
  // RawPairIntegerObject
  // =========================================================================

  @Test
  public void testRawPairIntegerObjectAccessors() {
    var pair = new RawPairIntegerObject<>(7, "hello");
    assertEquals(7, pair.getFirst());
    assertEquals("hello", pair.getSecond());
  }

  @Test
  public void testRawPairIntegerObjectEqualsIdentical() {
    var p1 = new RawPairIntegerObject<>(7, "hello");
    var p2 = new RawPairIntegerObject<>(7, "hello");
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testRawPairIntegerObjectSameInstance() {
    var pair = new RawPairIntegerObject<>(7, "hello");
    assertEquals(pair, pair);
  }

  @Test
  public void testRawPairIntegerObjectNotEqualDifferentFirst() {
    var p1 = new RawPairIntegerObject<>(7, "hello");
    var p2 = new RawPairIntegerObject<>(8, "hello");
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairIntegerObjectNotEqualDifferentSecond() {
    var p1 = new RawPairIntegerObject<>(7, "hello");
    var p2 = new RawPairIntegerObject<>(7, "world");
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairIntegerObjectNotEqualNull() {
    assertNotEquals(null, new RawPairIntegerObject<>(1, "a"));
  }

  @Test
  public void testRawPairIntegerObjectNotEqualDifferentType() {
    assertNotEquals("string", new RawPairIntegerObject<>(1, "a"));
  }

  // =========================================================================
  // RawPairLongInteger
  // =========================================================================

  @Test
  public void testRawPairLongIntegerAccessors() {
    var pair = new RawPairLongInteger(100L, 200);
    assertEquals(100L, pair.getFirst());
    assertEquals(200, pair.getSecond());
  }

  @Test
  public void testRawPairLongIntegerEqualsIdentical() {
    var p1 = new RawPairLongInteger(100L, 200);
    var p2 = new RawPairLongInteger(100L, 200);
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testRawPairLongIntegerSameInstance() {
    var pair = new RawPairLongInteger(1L, 2);
    assertEquals(pair, pair);
  }

  @Test
  public void testRawPairLongIntegerNotEqualDifferentFirst() {
    var p1 = new RawPairLongInteger(100L, 200);
    var p2 = new RawPairLongInteger(101L, 200);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairLongIntegerNotEqualDifferentSecond() {
    var p1 = new RawPairLongInteger(100L, 200);
    var p2 = new RawPairLongInteger(100L, 201);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairLongIntegerNotEqualNull() {
    assertNotEquals(null, new RawPairLongInteger(1L, 2));
  }

  @Test
  public void testRawPairLongIntegerNotEqualDifferentType() {
    assertNotEquals("string", new RawPairLongInteger(1L, 2));
  }

  // =========================================================================
  // RawPairLongLong
  // =========================================================================

  @Test
  public void testRawPairLongLongAccessors() {
    var pair = new RawPairLongLong(10L, 20L);
    assertEquals(10L, pair.getFirst());
    assertEquals(20L, pair.getSecond());
  }

  @Test
  public void testRawPairLongLongEqualsIdentical() {
    var p1 = new RawPairLongLong(10L, 20L);
    var p2 = new RawPairLongLong(10L, 20L);
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testRawPairLongLongSameInstance() {
    var pair = new RawPairLongLong(1L, 2L);
    assertEquals(pair, pair);
  }

  @Test
  public void testRawPairLongLongNotEqualDifferentFirst() {
    var p1 = new RawPairLongLong(10L, 20L);
    var p2 = new RawPairLongLong(11L, 20L);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairLongLongNotEqualDifferentSecond() {
    var p1 = new RawPairLongLong(10L, 20L);
    var p2 = new RawPairLongLong(10L, 21L);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairLongLongNotEqualNull() {
    assertNotEquals(null, new RawPairLongLong(1L, 2L));
  }

  @Test
  public void testRawPairLongLongNotEqualDifferentType() {
    assertNotEquals("string", new RawPairLongLong(1L, 2L));
  }

  // =========================================================================
  // RawPairLongObject
  // =========================================================================

  @Test
  public void testRawPairLongObjectAccessors() {
    var pair = new RawPairLongObject<>(50L, "value");
    assertEquals(50L, pair.getFirst());
    assertEquals("value", pair.getSecond());
  }

  @Test
  public void testRawPairLongObjectEqualsIdentical() {
    // This test validates the bug fix: RawPairLongObject.equals previously
    // cast to RawPairIntegerObject, causing ClassCastException.
    var p1 = new RawPairLongObject<>(50L, "value");
    var p2 = new RawPairLongObject<>(50L, "value");
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testRawPairLongObjectSameInstance() {
    var pair = new RawPairLongObject<>(1L, "a");
    assertEquals(pair, pair);
  }

  @Test
  public void testRawPairLongObjectNotEqualDifferentFirst() {
    var p1 = new RawPairLongObject<>(50L, "value");
    var p2 = new RawPairLongObject<>(51L, "value");
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairLongObjectNotEqualDifferentSecond() {
    var p1 = new RawPairLongObject<>(50L, "value");
    var p2 = new RawPairLongObject<>(50L, "other");
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairLongObjectNotEqualNull() {
    assertNotEquals(null, new RawPairLongObject<>(1L, "a"));
  }

  @Test
  public void testRawPairLongObjectNotEqualDifferentType() {
    assertNotEquals("string", new RawPairLongObject<>(1L, "a"));
  }

  // =========================================================================
  // RawPairObjectInteger
  // =========================================================================

  @Test
  public void testRawPairObjectIntegerAccessors() {
    var pair = new RawPairObjectInteger<>("key", 99);
    assertEquals("key", pair.getFirst());
    assertEquals(99, pair.getSecond());
  }

  @Test
  public void testRawPairObjectIntegerEqualsIdentical() {
    var p1 = new RawPairObjectInteger<>("key", 99);
    var p2 = new RawPairObjectInteger<>("key", 99);
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testRawPairObjectIntegerSameInstance() {
    var pair = new RawPairObjectInteger<>("k", 1);
    assertEquals(pair, pair);
  }

  @Test
  public void testRawPairObjectIntegerNotEqualDifferentFirst() {
    var p1 = new RawPairObjectInteger<>("key1", 99);
    var p2 = new RawPairObjectInteger<>("key2", 99);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairObjectIntegerNotEqualDifferentSecond() {
    var p1 = new RawPairObjectInteger<>("key", 99);
    var p2 = new RawPairObjectInteger<>("key", 100);
    assertNotEquals(p1, p2);
  }

  @Test
  public void testRawPairObjectIntegerNotEqualNull() {
    assertNotEquals(null, new RawPairObjectInteger<>("k", 1));
  }

  @Test
  public void testRawPairObjectIntegerNotEqualDifferentType() {
    assertNotEquals("string", new RawPairObjectInteger<>("k", 1));
  }

  // =========================================================================
  // PairIntegerObject
  // =========================================================================

  @Test
  public void testPairIntegerObjectAccessors() {
    var pair = new PairIntegerObject<>(3, "three");
    assertEquals(3, pair.getKey());
    assertEquals("three", pair.getValue());
  }

  @Test
  public void testPairIntegerObjectEqualsIdentical() {
    // PairIntegerObject.equals compares only keys, not values.
    var p1 = new PairIntegerObject<>(3, "three");
    var p2 = new PairIntegerObject<>(3, "other");
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testPairIntegerObjectSameInstance() {
    var pair = new PairIntegerObject<>(1, "a");
    assertEquals(pair, pair);
  }

  @Test
  public void testPairIntegerObjectNotEqualDifferentKey() {
    var p1 = new PairIntegerObject<>(3, "three");
    var p2 = new PairIntegerObject<>(4, "three");
    assertNotEquals(p1, p2);
  }

  @Test
  public void testPairIntegerObjectNotEqualNull() {
    assertNotEquals(null, new PairIntegerObject<>(1, "a"));
  }

  @Test
  public void testPairIntegerObjectNotEqualDifferentType() {
    assertNotEquals("string", new PairIntegerObject<>(1, "a"));
  }

  @Test
  public void testPairIntegerObjectCompareTo() {
    var p1 = new PairIntegerObject<>(1, "a");
    var p2 = new PairIntegerObject<>(2, "b");
    assertEquals(true, p1.compareTo(p2) < 0);
    assertEquals(true, p2.compareTo(p1) > 0);
    assertEquals(0, p1.compareTo(new PairIntegerObject<>(1, "c")));
  }

  @Test
  public void testPairIntegerObjectToString() {
    var pair = new PairIntegerObject<>(5, "five");
    assertEquals("PairIntegerObject [first=5, second=five]", pair.toString());
  }

  // =========================================================================
  // PairLongObject
  // =========================================================================

  @Test
  public void testPairLongObjectAccessors() {
    var pair = new PairLongObject<>(100L, "hundred");
    assertEquals(100L, pair.getKey());
    assertEquals("hundred", pair.getValue());
  }

  @Test
  public void testPairLongObjectEqualsIdentical() {
    // PairLongObject.equals compares only keys, not values.
    var p1 = new PairLongObject<>(100L, "hundred");
    var p2 = new PairLongObject<>(100L, "other");
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
  }

  @Test
  public void testPairLongObjectSameInstance() {
    var pair = new PairLongObject<>(1L, "a");
    assertEquals(pair, pair);
  }

  @Test
  public void testPairLongObjectNotEqualDifferentKey() {
    var p1 = new PairLongObject<>(100L, "hundred");
    var p2 = new PairLongObject<>(101L, "hundred");
    assertNotEquals(p1, p2);
  }

  @Test
  public void testPairLongObjectNotEqualNull() {
    assertNotEquals(null, new PairLongObject<>(1L, "a"));
  }

  @Test
  public void testPairLongObjectNotEqualDifferentType() {
    assertNotEquals("string", new PairLongObject<>(1L, "a"));
  }

  @Test
  public void testPairLongObjectCompareTo() {
    var p1 = new PairLongObject<>(1L, "a");
    var p2 = new PairLongObject<>(2L, "b");
    assertEquals(true, p1.compareTo(p2) < 0);
    assertEquals(true, p2.compareTo(p1) > 0);
    assertEquals(0, p1.compareTo(new PairLongObject<>(1L, "c")));
  }

  @Test
  public void testPairLongObjectToString() {
    var pair = new PairLongObject<>(5L, "five");
    assertEquals("PairLongObject [first=5, second=five]", pair.toString());
  }
}
