package com.jetbrains.youtrack.db.internal.common.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class BitSetMapTest {

  @Test
  public void testComputeIfAbsentReturnsTrueForNewKey() {
    var map = new BitSetMap();
    var result = map.computeIfAbsent(5, k -> true);
    assertTrue(result, "Expected true for new key returning true from function");
  }

  @Test
  public void testComputeIfAbsentReturnsFalseForNewKey() {
    var map = new BitSetMap();
    var result = map.computeIfAbsent(5, k -> false);
    assertFalse(result, "Expected false for new key returning false from function");
  }

  @Test
  public void testComputeIfAbsentUsesCachedValueWhenPresent_True() {
    var map = new BitSetMap();
    var callCount = new AtomicInteger();
    map.computeIfAbsent(3, k -> {
      callCount.incrementAndGet();
      return true;
    });
    var secondCall = map.computeIfAbsent(3, k -> {
      callCount.incrementAndGet();
      return false;
    });
    assertTrue(secondCall, "Expected cached value true to be returned");
    assertEquals(1, callCount.get(), "Expected valueFunction to be called only once");
  }

  @Test
  public void testComputeIfAbsentUsesCachedValueWhenPresent_False() {
    var map = new BitSetMap();
    var callCount = new AtomicInteger();
    map.computeIfAbsent(7, k -> {
      callCount.incrementAndGet();
      return false;
    });
    var secondCall = map.computeIfAbsent(7, k -> {
      callCount.incrementAndGet();
      return true;
    });
    assertFalse(secondCall, "Expected cached value false to be returned");
    assertEquals(1, callCount.get(), "Expected valueFunction to be called only once");
  }

  @Test
  public void testComputeIfAbsentWithZeroKey() {
    var map = new BitSetMap();
    var result = map.computeIfAbsent(0, k -> true);
    assertTrue(result, "Expected true for key=0");
  }

  @Test
  public void testComputeIfAbsentWithLargeKey() {
    var map = new BitSetMap();
    var largeKey = 100_000;
    var result = map.computeIfAbsent(largeKey, k -> k > 0);
    assertTrue(result, "Expected true for large key > 0");
  }
}