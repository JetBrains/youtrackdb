package com.jetbrains.youtrackdb.internal.common.collection;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Test;

/** Tests for {@link MultiValue} utility methods on identifiable collections. */
public class IdentifiableMultiValueTest {

  @Test
  public void testListSize() {
    var collection = new ArrayList<String>();
    MultiValue.add(collection, "foo");
    MultiValue.add(collection, "bar");
    MultiValue.add(collection, "baz");

    Assert.assertEquals(MultiValue.getSize(collection), 3);
  }

  @Test
  public void testArraySize() {
    var collection = new String[] {"foo", "bar", "baz"};
    Assert.assertEquals(MultiValue.getSize(collection), 3);
  }

  @Test
  public void testListFirstLast() {
    var collection = new ArrayList<String>();
    MultiValue.add(collection, "foo");
    MultiValue.add(collection, "bar");
    MultiValue.add(collection, "baz");

    Assert.assertEquals(MultiValue.getFirstValue(collection), "foo");
    Assert.assertEquals(MultiValue.getLastValue(collection), "baz");
  }

  @Test
  public void testArrayFirstLast() {
    var collection = new String[] {"foo", "bar", "baz"};
    Assert.assertEquals(MultiValue.getFirstValue(collection), "foo");
    Assert.assertEquals(MultiValue.getLastValue(collection), "baz");
  }

  @Test
  public void testListValue() {
    Assert.assertNull(MultiValue.getValue(null, 0));
    var collection = new ArrayList<String>();
    MultiValue.add(collection, "foo");
    MultiValue.add(collection, "bar");
    MultiValue.add(collection, "baz");

    Assert.assertNull(MultiValue.getValue(new Object(), 0));

    Assert.assertEquals(MultiValue.getValue(collection, 0), "foo");
    Assert.assertEquals(MultiValue.getValue(collection, 2), "baz");
    Assert.assertNull(MultiValue.getValue(new Object(), 3));
  }

  @Test
  public void testListRemove() {
    var collection = new ArrayList<String>();
    MultiValue.add(collection, "foo");
    MultiValue.add(collection, "bar");
    MultiValue.add(collection, "baz");

    MultiValue.remove(collection, "bar", true);
    Assert.assertEquals(2, collection.size());
    Assert.assertEquals("foo", collection.get(0));
    Assert.assertEquals("baz", collection.get(1));
  }

  @Test
  public void testToString() {
    var collection = new ArrayList<String>();
    MultiValue.add(collection, 1);
    MultiValue.add(collection, 2);
    MultiValue.add(collection, 3);
    Assert.assertEquals("[1, 2, 3]", MultiValue.toString(collection));
  }

  @Test
  public void testToStringEmptyCollection() {
    Assert.assertEquals("[]", MultiValue.toString(new ArrayList<>()));
  }

  /** MultiValue.toString on a Map should produce {key:value, ...} format. */
  @Test
  public void testMapToString() {
    // LinkedHashMap preserves insertion order; the expected string depends on it.
    var map = new LinkedHashMap<String, Object>();
    map.put("a", 1);
    map.put("b", 2);
    Assert.assertEquals("{a:1, b:2}", MultiValue.toString(map));
  }

  @Test
  public void testToStringEmptyMap() {
    Assert.assertEquals("{}", MultiValue.toString(new LinkedHashMap<>()));
  }

  /**
   * An Iterable that also implements Identifiable is NOT a multi-value — it represents a single
   * record, not a collection of records.
   */
  @Test
  public void testIsMultiValueReturnsFalseForIdentifiableIterable() {
    Assert.assertFalse(MultiValue.isMultiValue(IdentifiableIterable.class));
    Assert.assertFalse(MultiValue.isMultiValue(new IdentifiableIterable()));
  }

  /**
   * Removing a collection that contains Map elements from another collection should treat each Map
   * as a single element rather than decomposing it into keys.
   */
  @Test
  public void testRemoveCollectionContainingMapFromCollection() {
    var target = new ArrayList<Object>();
    Map<String, Object> embeddedMap = new HashMap<>();
    embeddedMap.put("name", "Alice");
    target.add("keep");
    target.add(embeddedMap);
    target.add("also-keep");

    // The element to remove is a list containing the map
    var toRemove = new ArrayList<Object>();
    toRemove.add(embeddedMap);

    MultiValue.remove(target, toRemove, false);
    Assert.assertEquals(2, target.size());
    Assert.assertEquals("keep", target.get(0));
    Assert.assertEquals("also-keep", target.get(1));
  }

  /**
   * Removing a collection that contains a nested collection should recursively remove the inner
   * collection's elements from the target (the isMultiValue branch).
   */
  @Test
  public void testRemoveCollectionContainingNestedCollectionFromCollection() {
    var target = new ArrayList<Object>();
    target.add("a");
    target.add("b");
    target.add("c");

    var inner = new ArrayList<Object>();
    inner.add("b");

    var toRemove = new ArrayList<Object>();
    toRemove.add(inner);

    MultiValue.remove(target, toRemove, false);
    Assert.assertEquals(2, target.size());
    Assert.assertEquals("a", target.get(0));
    Assert.assertEquals("c", target.get(1));
  }

  /**
   * MultiValue.array on an Iterator (isIterable=true, isMultiValue=false) should convert the
   * iterator contents to an array.
   */
  @Test
  public void testArrayFromIterator() {
    Iterator<String> it = List.of("x", "y", "z").iterator();
    Object[] result = MultiValue.array(it);
    Assert.assertNotNull(result);
    Assert.assertEquals(3, result.length);
    Assert.assertEquals("x", result[0]);
    Assert.assertEquals("y", result[1]);
    Assert.assertEquals("z", result[2]);
  }

  @Test
  public void testArrayFromEmptyIterator() {
    Iterator<String> it = List.<String>of().iterator();
    Object[] result = MultiValue.array(it);
    Assert.assertNotNull(result);
    Assert.assertEquals(0, result.length);
  }

  // --- getSize with Map, null, and single values ---

  @Test
  public void testGetSizeMap() {
    var map = new HashMap<String, Object>();
    map.put("a", 1);
    map.put("b", 2);
    Assert.assertEquals(2, MultiValue.getSize(map));
  }

  @Test
  public void testGetSizeNull() {
    // Null should return 0.
    Assert.assertEquals(0, MultiValue.getSize(null));
  }

  @Test
  public void testGetSizeNonMultiValue() {
    // A plain object that is not a collection/array/map returns 0.
    Assert.assertEquals(0, MultiValue.getSize("hello"));
  }

  // --- isEmpty ---

  @Test
  public void testIsEmptyWithEmptyCollection() {
    Assert.assertTrue(MultiValue.isEmpty(new ArrayList<>()));
  }

  @Test
  public void testIsEmptyWithNonEmptyCollection() {
    var list = new ArrayList<String>();
    list.add("a");
    Assert.assertFalse(MultiValue.isEmpty(list));
  }

  @Test
  public void testIsEmptyWithEmptyArray() {
    Assert.assertTrue(MultiValue.isEmpty(new String[] {}));
  }

  @Test
  public void testIsEmptyWithNonEmptyArray() {
    Assert.assertFalse(MultiValue.isEmpty(new String[] {"a"}));
  }

  // --- getFirstValue / getLastValue with maps ---

  @Test
  public void testGetFirstValueMap() {
    // For a Map, getFirstValue returns the first value from the entry set.
    var map = new LinkedHashMap<String, Object>();
    map.put("x", 10);
    map.put("y", 20);
    Assert.assertEquals(10, MultiValue.getFirstValue(map));
  }

  @Test
  public void testGetLastValueMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("x", 10);
    map.put("y", 20);
    Assert.assertEquals(20, MultiValue.getLastValue(map));
  }

  // --- getFirstValue / getLastValue with null / empty ---

  @Test
  public void testGetFirstValueNull() {
    Assert.assertNull(MultiValue.getFirstValue(null));
  }

  @Test
  public void testGetLastValueNull() {
    Assert.assertNull(MultiValue.getLastValue(null));
  }

  @Test
  public void testGetFirstValueEmptyList() {
    Assert.assertNull(MultiValue.getFirstValue(new ArrayList<>()));
  }

  @Test
  public void testGetLastValueEmptyList() {
    Assert.assertNull(MultiValue.getLastValue(new ArrayList<>()));
  }

  // --- add to collection ---

  @Test
  public void testAddSingleValueToCollection() {
    var list = new ArrayList<Object>();
    MultiValue.add(list, "item");
    Assert.assertEquals(1, list.size());
    Assert.assertEquals("item", list.get(0));
  }

  @Test
  public void testAddMultipleValues() {
    var list = new ArrayList<Object>();
    MultiValue.add(list, "a");
    MultiValue.add(list, "b");
    MultiValue.add(list, "c");
    Assert.assertEquals(3, list.size());
  }

  // --- remove with allOccurrences flag ---

  @Test
  public void testRemoveAllOccurrences() {
    var list = new ArrayList<Object>();
    list.add("a");
    list.add("b");
    list.add("a");
    list.add("c");
    MultiValue.remove(list, "a", true);
    Assert.assertEquals(2, list.size());
    Assert.assertFalse(list.contains("a"));
  }

  @Test
  public void testRemoveFirstOccurrenceOnly() {
    var list = new ArrayList<Object>();
    list.add("a");
    list.add("b");
    list.add("a");
    MultiValue.remove(list, "a", false);
    Assert.assertEquals(2, list.size());
    // First "a" removed, second "a" remains.
    Assert.assertEquals("b", list.get(0));
    Assert.assertEquals("a", list.get(1));
  }

  /**
   * Stub that implements both Iterable and Identifiable — used to verify that isMultiValue returns
   * false for such types (a record that happens to be iterable is not a multi-value collection).
   */
  private static class IdentifiableIterable
      implements Iterable<Object>, Identifiable {

    @Nonnull
    @Override
    public RID getIdentity() {
      return new RecordId(-1, -1);
    }

    @Override
    public int compareTo(@Nonnull Identifiable o) {
      return 0;
    }

    @Nonnull
    @Override
    public Iterator<Object> iterator() {
      return List.<Object>of().iterator();
    }
  }
}
