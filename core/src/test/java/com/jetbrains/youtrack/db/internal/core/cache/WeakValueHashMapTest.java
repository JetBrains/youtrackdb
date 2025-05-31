package com.jetbrains.youtrack.db.internal.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
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