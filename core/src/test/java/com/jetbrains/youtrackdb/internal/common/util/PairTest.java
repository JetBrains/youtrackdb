package com.jetbrains.youtrackdb.internal.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PairTest {

  // --- Pair: equals ---

  @Test
  public void testPairEqualsSameInstance() {
    // Verifies reflexive equality: a pair is equal to itself.
    var pair = new Pair<>("key", "value");
    assertEquals(pair, pair);
  }

  @Test
  public void testPairEqualsMatchingKeys() {
    // Two pairs with the same key (but different values) should be equal,
    // because Pair.equals compares keys only.
    var pair1 = new Pair<>("key", "value1");
    var pair2 = new Pair<>("key", "value2");
    assertEquals(pair1, pair2);
  }

  @Test
  public void testPairEqualsDifferentKeys() {
    // Two pairs with different keys should not be equal.
    var pair1 = new Pair<>("key1", "value");
    var pair2 = new Pair<>("key2", "value");
    assertNotEquals(pair1, pair2);
  }

  @Test
  public void testPairEqualsNullKeyBothSides() {
    // Two pairs with null keys should be equal.
    var pair1 = new Pair<String, String>(null, "value1");
    var pair2 = new Pair<String, String>(null, "value2");
    assertEquals(pair1, pair2);
  }

  @Test
  public void testPairEqualsNullKeyOneSide() {
    // A pair with a null key should not equal a pair with a non-null key.
    var pairNull = new Pair<String, String>(null, "value");
    var pairNonNull = new Pair<>("key", "value");
    assertNotEquals(pairNull, pairNonNull);
  }

  @Test
  public void testPairEqualsNonNullKeyVsNullKey() {
    // A pair with a non-null key should not equal a pair with a null key.
    var pairNonNull = new Pair<>("key", "value");
    var pairNull = new Pair<String, String>(null, "value");
    assertNotEquals(pairNonNull, pairNull);
  }

  @Test
  public void testPairEqualsNonPairObject() {
    // A pair should not equal a non-Pair object.
    var pair = new Pair<>("key", "value");
    assertNotEquals("key", pair);
  }

  @Test
  public void testPairEqualsNull() {
    // A pair should not equal null.
    var pair = new Pair<>("key", "value");
    assertNotEquals(null, pair);
  }

  // --- Pair: hashCode ---

  @Test
  public void testPairHashCodeConsistentWithEquals() {
    // Equal pairs must have the same hash code.
    var pair1 = new Pair<>("key", "value1");
    var pair2 = new Pair<>("key", "value2");
    assertEquals(pair1.hashCode(), pair2.hashCode());
  }

  @Test
  public void testPairHashCodeNullKey() {
    // A pair with null key should produce hashCode based on 0.
    var pair = new Pair<String, String>(null, "value");
    assertEquals(31, pair.hashCode());
  }

  // --- Pair: toString ---

  @Test
  public void testPairToStringNonArrayValue() {
    // toString should format as "key:value" for non-array values.
    var pair = new Pair<>("hello", "world");
    assertEquals("hello:world", pair.toString());
  }

  @Test
  public void testPairToStringArrayValue() {
    // toString should use Arrays.toString for array values.
    var pair = new Pair<>("key", new Object[] {"a", "b"});
    assertEquals("key:[a, b]", pair.toString());
  }

  @Test
  public void testPairToStringNullValue() {
    // toString should handle null values without error.
    var pair = new Pair<>("key", null);
    assertEquals("key:null", pair.toString());
  }

  // --- Pair: compareTo ---

  @Test
  public void testPairCompareToEqual() {
    // Pairs with equal keys should compare as 0.
    var pair1 = new Pair<>("abc", "v1");
    var pair2 = new Pair<>("abc", "v2");
    assertEquals(0, pair1.compareTo(pair2));
  }

  @Test
  public void testPairCompareToLessThan() {
    // Pair with smaller key should compare as negative.
    var pair1 = new Pair<>("aaa", "v1");
    var pair2 = new Pair<>("bbb", "v2");
    assertEquals(true, pair1.compareTo(pair2) < 0);
  }

  @Test
  public void testPairCompareToGreaterThan() {
    // Pair with larger key should compare as positive.
    var pair1 = new Pair<>("zzz", "v1");
    var pair2 = new Pair<>("aaa", "v2");
    assertEquals(true, pair1.compareTo(pair2) > 0);
  }

  // --- Pair: init / getters / setters ---

  @Test
  public void testPairDefaultConstructorAndInit() {
    // Default constructor creates empty pair; init sets key and value.
    var pair = new Pair<String, String>();
    assertNull(pair.getKey());
    assertNull(pair.getValue());
    pair.init("k", "v");
    assertEquals("k", pair.getKey());
    assertEquals("v", pair.getValue());
  }

  @Test
  public void testPairSetValueReturnsOldValue() {
    // setValue should return the previous value.
    var pair = new Pair<>("key", "old");
    var old = pair.setValue("new");
    assertEquals("old", old);
    assertEquals("new", pair.getValue());
  }

  @Test
  public void testPairEntryConstructor() {
    // Pair(Entry) constructor should copy key and value.
    Map<String, String> map = new HashMap<>();
    map.put("k", "v");
    var entry = map.entrySet().iterator().next();
    var pair = new Pair<>(entry);
    assertEquals("k", pair.getKey());
    assertEquals("v", pair.getValue());
  }

  // --- Pair: convertToMap / convertFromMap ---

  @Test
  public void testConvertToMap() {
    // convertToMap should produce a map with all pairs' keys and values.
    List<Pair<String, Integer>> pairs = new ArrayList<>();
    pairs.add(new Pair<>("a", 1));
    pairs.add(new Pair<>("b", 2));
    Map<String, Integer> map = Pair.convertToMap(pairs);
    assertEquals(2, map.size());
    assertEquals(Integer.valueOf(1), map.get("a"));
    assertEquals(Integer.valueOf(2), map.get("b"));
  }

  @Test
  public void testConvertFromMap() {
    // convertFromMap should produce a list of pairs from map entries.
    Map<String, Integer> map = new HashMap<>();
    map.put("x", 10);
    map.put("y", 20);
    List<Pair<String, Integer>> pairs = Pair.convertFromMap(map);
    assertEquals(2, pairs.size());
    // Verify all entries are present (order may vary)
    Map<String, Integer> rebuilt = Pair.convertToMap(pairs);
    assertEquals(map, rebuilt);
  }

  @Test
  public void testConvertToMapEmpty() {
    // convertToMap with empty list should produce empty map.
    List<Pair<String, Integer>> pairs = new ArrayList<>();
    Map<String, Integer> map = Pair.convertToMap(pairs);
    assertEquals(0, map.size());
  }

  // --- Triple: basics ---

  @Test
  public void testTripleConstructorAndGetters() {
    // Triple constructor sets key and value (a Pair internally).
    var triple = new Triple<>("key", "val", "sub");
    assertEquals("key", triple.getKey());
    assertNotNull(triple.getValue());
    assertEquals("val", triple.getValue().getKey());
    assertEquals("sub", triple.getValue().getValue());
  }

  @Test
  public void testTripleDefaultConstructorAndInit() {
    // Default constructor creates empty triple; init sets all fields.
    var triple = new Triple<String, String, String>();
    assertNull(triple.getKey());
    assertNull(triple.getValue());
    triple.init("k", "v", "sv");
    assertEquals("k", triple.getKey());
    assertEquals("v", triple.getValue().getKey());
    assertEquals("sv", triple.getValue().getValue());
  }

  // --- Triple: equals ---

  @Test
  public void testTripleEqualsSameInstance() {
    var triple = new Triple<>("key", "val", "sub");
    assertEquals(triple, triple);
  }

  @Test
  public void testTripleEqualsMatchingKeys() {
    // Two triples with the same key should be equal (ignores value).
    var t1 = new Triple<>("key", "val1", "sub1");
    var t2 = new Triple<>("key", "val2", "sub2");
    assertEquals(t1, t2);
  }

  @Test
  public void testTripleEqualsDifferentKeys() {
    var t1 = new Triple<>("key1", "val", "sub");
    var t2 = new Triple<>("key2", "val", "sub");
    assertNotEquals(t1, t2);
  }

  @Test
  public void testTripleEqualsNullKeyBothSides() {
    var t1 = new Triple<String, String, String>(null, "v1", "s1");
    var t2 = new Triple<String, String, String>(null, "v2", "s2");
    assertEquals(t1, t2);
  }

  @Test
  public void testTripleEqualsNullKeyOneSide() {
    var tNull = new Triple<String, String, String>(null, "v", "s");
    var tNonNull = new Triple<>("key", "v", "s");
    assertNotEquals(tNull, tNonNull);
  }

  @Test
  public void testTripleEqualsNonTripleObject() {
    var triple = new Triple<>("key", "val", "sub");
    assertNotEquals("key", triple);
  }

  @Test
  public void testTripleEqualsNull() {
    var triple = new Triple<>("key", "val", "sub");
    assertNotEquals(null, triple);
  }

  // --- Triple: hashCode ---

  @Test
  public void testTripleHashCodeConsistentWithEquals() {
    var t1 = new Triple<>("key", "val1", "sub1");
    var t2 = new Triple<>("key", "val2", "sub2");
    assertEquals(t1.hashCode(), t2.hashCode());
  }

  @Test
  public void testTripleHashCodeNullKey() {
    var triple = new Triple<String, String, String>(null, "v", "s");
    assertEquals(31, triple.hashCode());
  }

  // --- Triple: compareTo ---

  @Test
  public void testTripleCompareToEqual() {
    var t1 = new Triple<>("abc", "v1", "s1");
    var t2 = new Triple<>("abc", "v2", "s2");
    assertEquals(0, t1.compareTo(t2));
  }

  @Test
  public void testTripleCompareToLessThan() {
    var t1 = new Triple<>("aaa", "v1", "s1");
    var t2 = new Triple<>("bbb", "v2", "s2");
    assertEquals(true, t1.compareTo(t2) < 0);
  }

  // --- Triple: toString ---

  @Test
  public void testTripleToString() {
    // toString should format as "key:value/subvalue".
    var triple = new Triple<>("k", "v", "sv");
    assertEquals("k:v/sv", triple.toString());
  }

  // --- Triple: setValue / setSubValue ---

  @Test
  public void testTripleSetValueReturnsOldValue() {
    var triple = new Triple<>("k", "v", "sv");
    var oldValue = triple.setValue(new Pair<>("v2", "sv2"));
    assertEquals("v", oldValue.getKey());
    assertEquals("sv", oldValue.getValue());
    assertEquals("v2", triple.getValue().getKey());
    assertEquals("sv2", triple.getValue().getValue());
  }

  @Test
  public void testTripleSetSubValue() {
    // setSubValue updates only the sub-value of the internal pair.
    var triple = new Triple<>("k", "v", "sv");
    triple.setSubValue("newSv");
    assertEquals("v", triple.getValue().getKey());
    assertEquals("newSv", triple.getValue().getValue());
  }
}
