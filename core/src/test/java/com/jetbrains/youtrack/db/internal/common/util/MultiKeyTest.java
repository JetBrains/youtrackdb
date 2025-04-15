package com.jetbrains.youtrack.db.internal.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class MultiKeyTest {

  @Test
  public void testEqualsDifferentSize() {
    final var multiKey = new MultiKey(Collections.singletonList("a"));
    final var anotherMultiKey = new MultiKey(Arrays.asList("a", "b"));

    assertNotEquals(multiKey, anotherMultiKey);
  }

  @Test
  public void testEqualsDifferentItems() {
    final var multiKey = new MultiKey(Arrays.asList("b", "c"));
    final var anotherMultiKey = new MultiKey(Arrays.asList("a", "b"));

    assertNotEquals(multiKey, anotherMultiKey);
  }

  @Test
  public void testEqualsTheSame() {
    final var multiKey = new MultiKey(Collections.singletonList("a"));
    assertEquals(multiKey, multiKey);
  }

  @Test
  public void testEqualsNull() {
    final var multiKey = new MultiKey(Collections.singletonList("a"));
    assertNotEquals(null, multiKey);
  }

  @Test
  public void testEqualsDifferentClass() {
    final var multiKey = new MultiKey(Collections.singletonList("a"));
    assertNotEquals("a", multiKey);
  }

  @Test
  public void testEmptyKeyEquals() {
    final Map<MultiKey, Object> multiKeyMap = new HashMap<MultiKey, Object>();

    final var multiKey = new MultiKey(Collections.emptyList());
    multiKeyMap.put(multiKey, new Object());

    final var anotherMultiKey = new MultiKey(Collections.emptyList());
    final var mapResult = multiKeyMap.get(anotherMultiKey);

    assertNotNull(mapResult);
  }

  @Test
  public void testOneKeyMap() {
    final Map<MultiKey, Object> multiKeyMap = new HashMap<MultiKey, Object>();

    final var multiKey = new MultiKey(Collections.singletonList("a"));
    multiKeyMap.put(multiKey, new Object());

    final var anotherMultiKey = new MultiKey(Collections.singletonList("a"));
    final var mapResult = multiKeyMap.get(anotherMultiKey);

    assertNotNull(mapResult);
  }

  @Test
  public void testOneKeyNotInMap() {
    final Map<MultiKey, Object> multiKeyMap = new HashMap<MultiKey, Object>();

    final var multiKey = new MultiKey(Collections.singletonList("a"));
    multiKeyMap.put(multiKey, new Object());

    final var anotherMultiKey = new MultiKey(Collections.singletonList("b"));
    final var mapResult = multiKeyMap.get(anotherMultiKey);

    assertNull(mapResult);
  }

  @Test
  public void testTwoKeyMap() {
    final Map<MultiKey, Object> multiKeyMap = new HashMap<MultiKey, Object>();

    final var multiKey = new MultiKey(Arrays.asList("a", "b"));
    multiKeyMap.put(multiKey, new Object());

    final var anotherMultiKey = new MultiKey(Arrays.asList("a", "b"));
    final var mapResult = multiKeyMap.get(anotherMultiKey);

    assertNotNull(mapResult);
  }

  @Test
  public void testTwoKeyMapReordered() {
    final Map<MultiKey, Object> multiKeyMap = new HashMap<MultiKey, Object>();

    final var multiKey = new MultiKey(Arrays.asList("a", "b"));
    multiKeyMap.put(multiKey, new Object());

    final var anotherMultiKey = new MultiKey(Arrays.asList("b", "a"));
    final var mapResult = multiKeyMap.get(anotherMultiKey);

    assertNotNull(mapResult);
  }
}
