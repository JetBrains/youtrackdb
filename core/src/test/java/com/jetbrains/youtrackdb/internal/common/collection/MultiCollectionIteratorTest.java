package com.jetbrains.youtrackdb.internal.common.collection;

import static org.assertj.core.api.Java6Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.Test;

public class MultiCollectionIteratorTest {

  @Test
  public void testMaps() {

    final var it = new MultiCollectionIterator<>();

    final var map1 = Map.of("key1", "value1", "key2", "value2");
    final var map2 = Map.of("key3", "value3");

    it.add(map1);
    it.add(map2);

    assertThat(it.size()).isEqualTo(3);

    var map = new HashMap<>();
    for (var entry : it) {
      assertThat(entry).isInstanceOf(Entry.class);
      map.put(((Entry<?, ?>) entry).getKey(), ((Entry<?, ?>) entry).getValue());
    }

    assertThat(map).isEqualTo(new HashMap<>() {{
      putAll(map1);
      putAll(map2);
    }});
  }
}
