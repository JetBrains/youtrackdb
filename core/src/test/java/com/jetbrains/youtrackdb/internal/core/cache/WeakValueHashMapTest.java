package com.jetbrains.youtrackdb.internal.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractMap.SimpleEntry;
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
    for (int i = 0; i < 100; i++) {
      map.put(new TestKey("k" + i), new TestValue("v" + i));
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
   */
  @Test
  public void putShouldBeBlockedDuringForEach() {
    var map = new WeakValueHashMap<TestKey, TestValue>();
    map.put(new TestKey("k1"), new TestValue("v1"));

    assertThatThrownBy(() ->
        map.forEach((k, v) ->
            map.put(new TestKey("k2"), new TestValue("v2"))
        )
    ).isInstanceOf(IllegalStateException.class)
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
    map.put(key, new TestValue("v1"));

    assertThatThrownBy(() ->
        map.forEach((k, v) -> map.remove(k))
    ).isInstanceOf(IllegalStateException.class)
        .hasMessage("Modification is not allowed");
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