package com.jetbrains.youtrackdb.internal.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.ref.WeakReference;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Ignore;
import org.junit.Test;

public class WeakValueHashMapTest {

  public record TestKey(String value) {

  }

  public record TestValue(String value) {

  }

  // we need to think of a better way to test this
  @Test
  @Ignore("System.gc() is not guaranteed invoke garbage collector")
  public void testWeakRefClear() throws InterruptedException {
    final var map = new WeakValueHashMap<TestKey, TestValue>();

    checkMapEmpty(map);
    var key = new TestKey("1");
    var value = new TestValue("123");
    map.put(key, value);

    checkMapContainsOnly(map, new TestKey("1"), new TestValue("123"));
    System.gc();
    Thread.sleep(1000);
    checkMapContainsOnly(map, new TestKey("1"), new TestValue("123"));

    key = null;
    value = null;
    System.gc();

    Thread.sleep(1000);
    checkMapEmpty(map);
  }

  @Test
  public void testPutRemove() {
    final var map = new WeakValueHashMap<TestKey, TestValue>();
    checkMapEmpty(map);

    var key = new TestKey("1");
    var value = new TestValue("123");

    map.put(key, value);
    checkMapContainsOnly(map, key, value);

    map.remove(key);
    checkMapEmpty(map);
  }

  private void checkMapEmpty(Map<TestKey, TestValue> map) {
    assertThat(map).isEmpty();
    assertThat(map.keySet()).isEmpty();
    assertThat(map.values()).isEmpty();
    assertThat(map.size()).isEqualTo(0);
    assertThat(map.get(new TestKey("1"))).isNull();
    assertThat(map.entrySet()).isEmpty();
  }

  /**
   * Reproduces the race condition from the CI failure:
   * Thread A iterates the cache via forEach() (sets stopModification=true),
   * while Thread B calls clear() during pool shutdown.
   * Before the fix, clear() threw IllegalStateException("Modification is not allowed").
   * After the fix, clear() succeeds and forEach() catches the resulting
   * ConcurrentModificationException from the HashMap iterator gracefully.
   */
  @Test
  public void clearShouldSucceedWhileForEachIsInProgress() throws Exception {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    // Keep strong references to values so GC cannot clear the WeakReferences
    // before forEach iterates — otherwise the callback may never fire.
    var strongRefs = new TestValue[100];
    for (int i = 0; i < 100; i++) {
      strongRefs[i] = new TestValue("v" + i);
      map.put(new TestKey("k" + i), strongRefs[i]);
    }

    // Latch to ensure forEach callback is executing when clear() is called
    var forEachStarted = new CountDownLatch(1);
    var clearDone = new CountDownLatch(1);
    var clearError = new AtomicReference<Throwable>();
    var iteratorError = new AtomicReference<Throwable>();

    // Thread A: iterate via forEach with a callback that pauses until clear() is done
    var iteratorThread = new Thread(() -> {
      try {
        map.forEach((k, v) -> {
          forEachStarted.countDown();
          try {
            // Wait until clear() finishes — this keeps stopModification=true
            if (!clearDone.await(5, TimeUnit.SECONDS)) {
              throw new AssertionError(
                  "Timed out waiting for clear() to complete");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      } catch (Throwable t) {
        iteratorError.set(t);
      }
    });
    iteratorThread.start();

    // Wait for forEach to be inside its callback (stopModification=true)
    assertThat(forEachStarted.await(5, TimeUnit.SECONDS)).isTrue();

    // Thread B (this thread): clear() should not throw even though forEach is active
    try {
      map.clear();
    } catch (Throwable t) {
      clearError.set(t);
    } finally {
      clearDone.countDown();
    }

    iteratorThread.join(5_000);

    assertThat(clearError.get())
        .as("clear() must not throw during concurrent forEach iteration")
        .isNull();
    assertThat(iteratorError.get())
        .as("forEach() must handle concurrent clear gracefully")
        .isNull();
    assertThat(iteratorThread.isAlive())
        .as("iterator thread should have completed")
        .isFalse();
  }

  /**
   * Verifies that put() is still blocked during forEach() iteration
   * to prevent ConcurrentModificationException on the underlying HashMap.
   * Strong references to key and value must be kept to prevent GC from
   * clearing the WeakReference before forEach iterates.
   */
  @Test
  public void putShouldBeBlockedDuringForEach() {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    var key = new TestKey("k1");
    var value = new TestValue("v1");
    map.put(key, value);

    assertThatThrownBy(() -> map.forEach((k, v) -> map.put(new TestKey("k2"), new TestValue("v2"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Modification is not allowed");
  }

  /**
   * Verifies that remove() is still blocked during forEach() iteration
   * to prevent ConcurrentModificationException on the underlying HashMap.
   */
  @Test
  public void removeShouldBeBlockedDuringForEach() {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    var key = new TestKey("k1");
    var value = new TestValue("v1");
    map.put(key, value);

    assertThatThrownBy(() -> map.forEach((k, v) -> map.remove(k)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Modification is not allowed");
  }

  /**
   * Verifies that stale entries are not evicted on every get/put/remove call but are
   * evicted after 64 operations (the amortized eviction interval). Uses reflection to
   * manually clear and enqueue a WeakReference (deterministic, no System.gc() dependency)
   * and inspects the internal referenceMap directly to observe stale entry presence.
   */
  @Test
  public void amortizedEvictionFiresOnceEvery64Operations() throws Exception {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    var key = new TestKey("evict-me");
    map.put(key, new TestValue("value"));

    // Manually clear the WeakReference and enqueue it via reflection, simulating GC.
    simulateGcForKey(map, key);

    // The put above was operation 0 (counter 0 -> 1, and (0 & 63) == 0 triggers eviction
    // before the value was stale). Now perform 62 get() calls (operations 1..62 in the
    // counter). None should trigger eviction because (counter & 63) != 0 for values 1..62.
    for (int i = 0; i < 62; i++) {
      map.get(new TestKey("nonexistent"));
    }

    // Stale entry should still be present in the internal map — eviction hasn't fired.
    assertThat(getInternalMapSize(map))
        .as("Stale entry should still be present before 64th operation")
        .isEqualTo(1);

    // Operation 63 (counter becomes 63, 63 & 63 == 63 != 0) — still no eviction.
    map.get(new TestKey("nonexistent"));
    assertThat(getInternalMapSize(map))
        .as("Stale entry should still be present at operation 63")
        .isEqualTo(1);

    // Operation 64 (counter becomes 64, 64 & 63 == 0) — this triggers eviction.
    map.get(new TestKey("nonexistent"));
    assertThat(getInternalMapSize(map))
        .as("Stale entry should be evicted after 64th operation")
        .isEqualTo(0);
  }

  /**
   * Verifies that size() forces full eviction regardless of the amortized counter,
   * ensuring accurate results even when the eviction cycle hasn't fired yet.
   */
  @Test
  public void sizeAlwaysEvictsStaleEntries() throws Exception {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    var key = new TestKey("k1");
    map.put(key, new TestValue("v1"));

    simulateGcForKey(map, key);

    // Only 1 operation so far (the put). size() should force full eviction immediately.
    assertThat(map.size())
        .as("size() should force full eviction and report 0 for GC'd entries")
        .isEqualTo(0);
  }

  /**
   * Verifies that entrySet() forces full eviction regardless of the amortized counter.
   */
  @Test
  public void entrySetAlwaysEvictsStaleEntries() throws Exception {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    var key = new TestKey("k1");
    map.put(key, new TestValue("v1"));

    simulateGcForKey(map, key);

    assertThat(map.entrySet())
        .as("entrySet() should force full eviction and return empty set")
        .isEmpty();
  }

  /**
   * Verifies that forEach() forces full eviction regardless of the amortized counter.
   */
  @Test
  public void forEachAlwaysEvictsStaleEntries() throws Exception {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    var key = new TestKey("k1");
    map.put(key, new TestValue("v1"));

    simulateGcForKey(map, key);

    var visited = new ArrayList<TestKey>();
    map.forEach((k, v) -> visited.add(k));
    assertThat(visited)
        .as("forEach() should force full eviction and skip GC'd entries")
        .isEmpty();
  }

  /**
   * Verifies that the cleanup callback fires at the correct time — on the eviction
   * cycle, not before — and receives the correct key.
   */
  @Test
  public void cleanupCallbackFiresOnEvictionCycle() throws Exception {
    var callbackKeys = new ArrayList<TestKey>();
    var map = new WeakValueHashMap<TestKey, TestValue>(false, callbackKeys::add);
    var key = new TestKey("cb-key");
    map.put(key, new TestValue("val"));

    simulateGcForKey(map, key);

    // 62 get() calls (operations 1..62) — callback should NOT have fired.
    for (int i = 0; i < 62; i++) {
      map.get(new TestKey("miss"));
    }
    assertThat(callbackKeys)
        .as("Cleanup callback should not fire before eviction cycle")
        .isEmpty();

    // Operations 63 and 64 — the 64th triggers eviction and the callback.
    map.get(new TestKey("miss"));
    map.get(new TestKey("miss"));
    assertThat(callbackKeys)
        .as("Cleanup callback should fire with the correct key after eviction")
        .containsExactly(key);
  }

  /**
   * Verifies that mixed put/get/remove operations all increment the amortized eviction
   * counter. If any operation failed to call amortizedEviction(), the counter would
   * drift and eviction would fire at the wrong time.
   */
  @Test
  public void mixedOperationsAllContributeToEvictionCounter() throws Exception {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    var staleKey = new TestKey("stale");
    map.put(staleKey, new TestValue("val"));

    simulateGcForKey(map, staleKey);

    // Mix of 63 operations: 21 puts + 21 gets + 21 removes = 63 total (operations 1..63).
    var strongRefs = new TestValue[21];
    for (int i = 0; i < 21; i++) {
      strongRefs[i] = new TestValue("v" + i);
      map.put(new TestKey("tmp" + i), strongRefs[i]);
    }
    for (int i = 0; i < 21; i++) {
      map.get(new TestKey("miss" + i));
    }
    for (int i = 0; i < 21; i++) {
      map.remove(new TestKey("tmp" + i));
    }

    // Stale entry should still be present (63 ops since last eviction at counter 0).
    assertThat(getInternalMapSize(map))
        .as("Stale entry should survive 63 mixed operations")
        .isEqualTo(1);

    // 64th operation triggers eviction.
    map.get(new TestKey("trigger"));
    assertThat(getInternalMapSize(map))
        .as("Stale entry should be evicted after 64th mixed operation")
        .isEqualTo(0);
  }

  /**
   * Verifies that eviction still fires correctly when the operation counter wraps around
   * Integer.MAX_VALUE. The bitmask check (counter & 63) == 0 works for all int values
   * including negative ones, so overflow is harmless.
   */
  @Test
  public void evictionWorksAfterCounterOverflow() throws Exception {
    var map = new WeakValueHashMap<TestKey, TestValue>();

    // Set operationCounter to Integer.MAX_VALUE - 1 via reflection, so the next two
    // operations will be MAX_VALUE-1 and MAX_VALUE, then it wraps to MIN_VALUE.
    setOperationCounter(map, Integer.MAX_VALUE - 1);

    var key = new TestKey("overflow");
    // This put is the operation at counter MAX_VALUE-1; (MAX_VALUE-1 & 63) is 62, no eviction.
    map.put(key, new TestValue("val"));
    simulateGcForKey(map, key);

    // Advance counter through the overflow. We need to reach a value where (counter & 63) == 0.
    // MAX_VALUE = 2^31 - 1 = 2147483647. MAX_VALUE & 63 = 63. So counter sequence is:
    // MAX_VALUE-1 (put above), MAX_VALUE, MIN_VALUE, MIN_VALUE+1, ...
    // MIN_VALUE & 63 = 0, so the second operation after the put triggers eviction.
    map.get(new TestKey("a")); // counter = MAX_VALUE, & 63 = 63, no eviction
    assertThat(getInternalMapSize(map))
        .as("Stale entry should survive before overflow eviction")
        .isEqualTo(1);

    map.get(new TestKey("b")); // counter = MIN_VALUE, & 63 = 0, eviction fires
    assertThat(getInternalMapSize(map))
        .as("Stale entry should be evicted after counter overflow")
        .isEqualTo(0);
  }

  // -- Reflection helpers for deterministic testing without System.gc() --

  /**
   * Simulates GC collection of the value associated with the given key by clearing
   * the WeakReference's referent and enqueueing it in the reference queue, via reflection.
   */
  @SuppressWarnings("unchecked")
  private static <K, V> void simulateGcForKey(WeakValueHashMap<K, V> map, K key)
      throws Exception {
    var refMapField = WeakValueHashMap.class.getDeclaredField("referenceMap");
    refMapField.setAccessible(true);
    var referenceMap = (Map<K, ?>) refMapField.get(map);

    var weakRef = (WeakReference<?>) referenceMap.get(key);
    assertThat(weakRef).as("Key must exist in the map to simulate GC").isNotNull();

    // clear() and enqueue() are public methods on WeakReference — no module-system
    // reflection needed. This simulates GC clearing and enqueueing the reference.
    weakRef.clear();
    weakRef.enqueue();
  }

  /**
   * Returns the raw size of the internal referenceMap, bypassing eviction.
   * This allows observing stale entries that haven't been cleaned up yet.
   */
  private static int getInternalMapSize(WeakValueHashMap<?, ?> map) throws Exception {
    var refMapField = WeakValueHashMap.class.getDeclaredField("referenceMap");
    refMapField.setAccessible(true);
    return ((Map<?, ?>) refMapField.get(map)).size();
  }

  /**
   * Sets the operationCounter to a specific value via reflection, for testing
   * boundary conditions like integer overflow.
   */
  private static void setOperationCounter(WeakValueHashMap<?, ?> map, int value)
      throws Exception {
    var counterField = WeakValueHashMap.class.getDeclaredField("operationCounter");
    counterField.setAccessible(true);
    counterField.setInt(map, value);
  }

  private void checkMapContainsOnly(Map<TestKey, TestValue> map, TestKey key, TestValue value) {

    assertThat(map).isNotEmpty();
    assertThat(map).hasSize(1);
    assertThat(map.keySet()).containsExactly(key);
    assertThat(map.values()).containsExactly(value);
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.get(key)).isEqualTo(value);
    assertThat(map.entrySet()).containsExactly(new SimpleEntry<>(key, value));
  }
}
