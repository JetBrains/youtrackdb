package com.jetbrains.youtrackdb.internal.common.collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LRUCacheTest {

  @Test
  public void testPutAndGet() {
    // Basic put/get: stored values should be retrievable.
    var cache = new LRUCache<String, Integer>(5);
    cache.put("a", 1);
    cache.put("b", 2);
    assertEquals(Integer.valueOf(1), cache.get("a"));
    assertEquals(Integer.valueOf(2), cache.get("b"));
  }

  @Test
  public void testSizeTracking() {
    var cache = new LRUCache<String, Integer>(5);
    assertEquals(0, cache.size());
    cache.put("a", 1);
    assertEquals(1, cache.size());
    cache.put("b", 2);
    assertEquals(2, cache.size());
  }

  @Test
  public void testEvictionWhenExceedingCapacity() {
    // removeEldestEntry fires only AFTER size() exceeds cacheSize, so a cache with
    // capacity 3 holds exactly 3 entries; eviction kicks in on the 4th put.
    var cache = new LRUCache<String, Integer>(3);
    cache.put("a", 1);
    cache.put("b", 2);
    cache.put("c", 3);
    // First three puts must all be retained — capacity equals 3.
    assertEquals(3, cache.size());
    assertTrue(cache.containsKey("a"));
    assertTrue(cache.containsKey("b"));
    assertTrue(cache.containsKey("c"));
    // The fourth put pushes size past cacheSize, evicting the eldest (a).
    cache.put("d", 4);
    assertEquals(3, cache.size());
    assertFalse(cache.containsKey("a"));
    assertTrue(cache.containsKey("b"));
    assertTrue(cache.containsKey("c"));
    assertTrue(cache.containsKey("d"));
  }

  @Test
  public void testAccessOrderEviction() {
    // Access-order mode: accessing an entry moves it to the end of the
    // eviction queue. The least recently accessed entry is evicted first.
    var cache = new LRUCache<String, Integer>(3);
    cache.put("a", 1);
    cache.put("b", 2);
    cache.put("c", 3);
    // Access "a" to make it the most-recently-used — it must survive the next eviction.
    cache.get("a");
    // The fourth put pushes size past capacity; eviction targets "b" (now eldest).
    cache.put("d", 4);
    assertEquals(3, cache.size());
    assertFalse(cache.containsKey("b"));
    assertTrue(cache.containsKey("a"));
    assertTrue(cache.containsKey("c"));
    assertTrue(cache.containsKey("d"));
  }

  @Test
  public void testGetMissingKeyReturnsNull() {
    var cache = new LRUCache<String, Integer>(5);
    assertNull(cache.get("nonexistent"));
  }

  @Test
  public void testPutOverwritesExistingKey() {
    var cache = new LRUCache<String, Integer>(5);
    cache.put("a", 1);
    cache.put("a", 2);
    assertEquals(Integer.valueOf(2), cache.get("a"));
    assertEquals(1, cache.size());
  }

  @Test
  public void testCapacityOneAlwaysEvicts() {
    // With cacheSize=1 the cache holds exactly one entry; the second put evicts the first.
    var cache = new LRUCache<String, Integer>(1);
    cache.put("a", 1);
    assertTrue(cache.containsKey("a"));
    assertEquals(1, cache.size());
    cache.put("b", 2);
    assertFalse(cache.containsKey("a"));
    assertTrue(cache.containsKey("b"));
    assertEquals(1, cache.size());
  }

  @Test
  public void testSmallCapacityEviction() {
    // With cacheSize=2 the steady-state holds exactly two entries; the third put evicts
    // the eldest.
    var cache = new LRUCache<String, Integer>(2);
    cache.put("a", 1);
    cache.put("b", 2);
    assertTrue(cache.containsKey("a"));
    assertTrue(cache.containsKey("b"));
    assertEquals(2, cache.size());
    cache.put("c", 3);
    assertFalse(cache.containsKey("a"));
    assertTrue(cache.containsKey("b"));
    assertTrue(cache.containsKey("c"));
    assertEquals(2, cache.size());
  }
}
