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
    // When cache reaches capacity, the eldest entry is evicted on next put.
    // Note: removeEldestEntry triggers when size() >= cacheSize, so a cache
    // with capacity 3 will evict to stay at 2 entries after each put beyond 2.
    var cache = new LRUCache<String, Integer>(3);
    cache.put("a", 1);
    cache.put("b", 2);
    cache.put("c", 3);
    // Size is 3 (>= cacheSize=3), so "a" (eldest) was evicted when "c" was put.
    assertFalse(cache.containsKey("a"));
    assertTrue(cache.containsKey("b"));
    assertTrue(cache.containsKey("c"));
  }

  @Test
  public void testAccessOrderEviction() {
    // Access-order mode: accessing an entry moves it to the end of the
    // eviction queue. The least recently accessed entry is evicted first.
    var cache = new LRUCache<String, Integer>(3);
    cache.put("a", 1);
    cache.put("b", 2);
    // Access "a" to make it recently used.
    cache.get("a");
    // Now put "c" — "b" should be evicted since "a" was accessed more recently.
    cache.put("c", 3);
    assertFalse(cache.containsKey("b"));
    assertTrue(cache.containsKey("a"));
    assertTrue(cache.containsKey("c"));
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
    // With cacheSize=1, removeEldestEntry fires when size() >= 1,
    // so every entry is evicted immediately. Effective capacity is 0.
    var cache = new LRUCache<String, Integer>(1);
    cache.put("a", 1);
    assertFalse(cache.containsKey("a"));
    assertEquals(0, cache.size());
  }

  @Test
  public void testSmallCapacityEviction() {
    // With cacheSize=2, removeEldestEntry fires when size() >= 2,
    // so the effective capacity is 1. The second put evicts the first.
    var cache = new LRUCache<String, Integer>(2);
    cache.put("a", 1);
    cache.put("b", 2);
    assertFalse(cache.containsKey("a"));
    assertTrue(cache.containsKey("b"));
    assertEquals(1, cache.size());
  }
}
