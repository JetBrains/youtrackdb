package com.jetbrains.youtrackdb.internal.common.collection;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.assertj.core.api.Java6Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.common.util.Resettable;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
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

    var expected = new HashMap<>();
    expected.putAll(map1);
    expected.putAll(map2);
    assertThat(map).isEqualTo(expected);
  }

  /** Verify size() correctly sums multiple Collection sources. */
  @Test
  public void testCollectionSize() {
    var it = new MultiCollectionIterator<String>();
    it.add(List.of("a", "b", "c"));
    it.add(List.of("d"));

    assertThat(it.size()).isEqualTo(4);
    assertThat(it.isSizeable()).isTrue();
  }

  /** Verify iteration over Collection sources yields all elements. */
  @Test
  public void testCollectionIteration() {
    var it = new MultiCollectionIterator<String>();
    it.add(List.of("a", "b"));
    it.add(List.of("c"));

    var result = new ArrayList<String>();
    while (it.hasNext()) {
      result.add(it.next());
    }
    assertThat(result).containsExactly("a", "b", "c");
  }

  /** Verify size() correctly computes length for array sources. */
  @Test
  public void testArraySize() {
    var it = new MultiCollectionIterator<String>();
    it.add(new String[] {"x", "y"});
    it.add(new String[] {"z"});

    assertThat(it.size()).isEqualTo(3);
  }

  /** Verify iteration over array sources. */
  @Test
  public void testArrayIteration() {
    var it = new MultiCollectionIterator<String>();
    it.add(new String[] {"x", "y"});

    var result = new ArrayList<String>();
    while (it.hasNext()) {
      result.add(it.next());
    }
    assertThat(result).containsExactly("x", "y");
  }

  /** Verify size() counts elements from a Resettable Iterator by iterating and resetting. */
  @Test
  public void testResettableIteratorSize() {
    var resettableIter = new ResettableListIterator<>(List.of("a", "b", "c"));
    var it = new MultiCollectionIterator<String>();
    it.add(resettableIter);

    // size() should iterate and reset, yielding 3
    assertThat(it.size()).isEqualTo(3);
    // isSizeable() returns false for raw Iterator sources (even Resettable ones),
    // because the isSizeable check conservatively rejects all Iterators
    assertThat(it.isSizeable()).isFalse();
  }

  /** Verify size() falls back to counting a single element for unrecognized source types. */
  @Test
  public void testSingleObjectSize() {
    var it = new MultiCollectionIterator<String>();
    it.add("standalone");

    assertThat(it.size()).isEqualTo(1);
  }

  /** Verify remove() throws UnsupportedOperationException. */
  @Test
  public void testRemoveThrows() {
    var it = new MultiCollectionIterator<String>();
    it.add(List.of("a"));

    assertThatThrownBy(it::remove)
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** Verify add() throws when the iterator is already in use. */
  @Test
  public void testAddAfterIterationThrows() {
    var it = new MultiCollectionIterator<String>();
    it.add(List.of("a"));
    // Start iteration to set sourcesIterator
    it.hasNext();

    assertThatThrownBy(() -> it.add(List.of("b")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("in use");
  }

  /** Verify add() throws on type mismatch between map and collection sources. */
  @Test
  public void testAddTypeMismatchThrows() {
    var it = new MultiCollectionIterator<>();
    it.add(Map.of("k", "v"));

    assertThatThrownBy(() -> it.add(List.of("a")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mismatch");
  }

  /** Verify isSizeable() returns false when a raw Iterator (non-Resettable) is a source. */
  @Test
  public void testIsSizeableWithRawIterator() {
    var it = new MultiCollectionIterator<String>();
    it.add(List.of("a").iterator()); // plain Iterator, not Resettable

    assertThat(it.isSizeable()).isFalse();
  }

  /** Verify isSizeable() returns false when a Sizeable reports isSizeable()=false. */
  @Test
  public void testIsSizeableWithNonSizeableSizeable() {
    var it = new MultiCollectionIterator<>();
    it.add(new Sizeable() {
      @Override
      public int size() {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean isSizeable() {
        return false;
      }
    });

    assertThat(it.isSizeable()).isFalse();
  }

  /** Verify supportsFastContains() returns true for Set sources. */
  @Test
  public void testSupportsFastContainsWithSet() {
    var it = new MultiCollectionIterator<String>();
    it.add(Set.of("a", "b"));

    assertThat(it.supportsFastContains()).isTrue();
  }

  /** Verify supportsFastContains() returns false for List sources. */
  @Test
  public void testSupportsFastContainsWithList() {
    var it = new MultiCollectionIterator<String>();
    it.add(List.of("a", "b"));

    assertThat(it.supportsFastContains()).isFalse();
  }

  /** Verify contains() finds elements in Collection sources. */
  @Test
  public void testContainsWithCollection() {
    var it = new MultiCollectionIterator<String>();
    it.add(new HashSet<>(Set.of("a", "b")));
    it.add(new HashSet<>(Set.of("c")));

    assertThat(it.contains("b")).isTrue();
    assertThat(it.contains("c")).isTrue();
    assertThat(it.contains("z")).isFalse();
  }

  /** Verify reset() re-iterates Collection sources from the beginning. */
  @Test
  public void testResetWithCollections() {
    var it = new MultiCollectionIterator<String>();
    it.add(List.of("a", "b"));

    // First pass
    var first = new ArrayList<String>();
    while (it.hasNext()) {
      first.add(it.next());
    }
    assertThat(first).containsExactly("a", "b");

    // Reset and second pass
    it.reset();
    var second = new ArrayList<String>();
    while (it.hasNext()) {
      second.add(it.next());
    }
    assertThat(second).containsExactly("a", "b");
  }

  /** Verify that a Resettable Iterator source is reset when getNextPartial advances to it. */
  @Test
  public void testResettableIteratorIsResetOnIteration() {
    // Partially consume the iterator before adding it
    var resettableIter = new ResettableListIterator<>(List.of("a", "b", "c"));
    resettableIter.next(); // consume "a"

    var it = new MultiCollectionIterator<String>();
    it.add(resettableIter);

    // MultiCollectionIterator should reset the iterator, so all 3 elements are yielded
    var result = new ArrayList<String>();
    while (it.hasNext()) {
      result.add(it.next());
    }
    assertThat(result).containsExactly("a", "b", "c");
  }

  /**
   * Simple Resettable + Iterator implementation backed by a list, for testing
   * MultiCollectionIterator's handling of resettable iterator sources.
   */
  private static class ResettableListIterator<T>
      implements Iterator<T>, Resettable {
    private final List<T> items;
    private int index;

    ResettableListIterator(List<T> items) {
      this.items = new ArrayList<>(items);
      this.index = 0;
    }

    @Override
    public boolean hasNext() {
      return index < items.size();
    }

    @Override
    public T next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return items.get(index++);
    }

    @Override
    public void reset() {
      index = 0;
    }

    @Override
    public boolean isResetable() {
      return true;
    }
  }
}
