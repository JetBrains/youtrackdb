package com.jetbrains.youtrack.db.internal.common.collection.closabledictionary;

import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * LRU list is used inside of {@link ClosableLinkedContainer}.
 *
 * @param <K> Key type
 * @param <V> Value type
 */
class ClosableLRUList<K, V extends ClosableItem> implements Iterable<ClosableEntry<K, V>> {

  private int size;

  private ClosableEntry<K, V> head;
  private ClosableEntry<K, V> tail;

  void remove(ClosableEntry<K, V> entry) {
    final var next = entry.getNext();
    final var prev = entry.getPrev();

    if (!(next != null || prev != null || entry == head)) {
      return;
    }

    assert prev == null || prev.getNext() == entry;

    assert next == null || next.getPrev() == entry;

    if (next != null) {
      next.setPrev(prev);
    }

    if (prev != null) {
      prev.setNext(next);
    }

    if (head == entry) {
      assert entry.getPrev() == null;
      head = next;
    }

    if (tail == entry) {
      assert entry.getNext() == null;
      tail = prev;
    }

    entry.setNext(null);
    entry.setPrev(null);

    size--;
  }

  boolean contains(ClosableEntry<K, V> entry) {
    return entry.getNext() != null || entry.getPrev() != null || entry == head;
  }

  void moveToTheTail(ClosableEntry<K, V> entry) {
    if (tail == entry) {
      assert entry.getNext() == null;
      return;
    }

    final var next = entry.getNext();
    final var prev = entry.getPrev();

    var newEntry = !(next != null || prev != null || entry == head);

    assert prev == null || prev.getNext() == entry;

    assert next == null || next.getPrev() == entry;

    if (prev != null) {
      prev.setNext(next);
    }

    if (next != null) {
      next.setPrev(prev);
    }

    if (head == entry) {
      assert entry.getPrev() == null;
      head = next;
    }

    entry.setPrev(tail);
    entry.setNext(null);
    if (tail != null) {
      assert tail.getNext() == null;
      tail.setNext(entry);
      tail = entry;
    } else {
      tail = head = entry;
    }

    if (newEntry) {
      size++;
    }
  }

  int size() {
    return size;
  }

  @Nullable
  ClosableEntry<K, V> poll() {
    if (head == null) {
      return null;
    }

    final var entry = head;

    var next = head.getNext();
    assert next == null || next.getPrev() == head;

    head = next;
    if (next != null) {
      next.setPrev(null);
    }

    assert head == null || head.getPrev() == null;

    if (head == null) {
      tail = null;
    }

    entry.setNext(null);
    assert entry.getPrev() == null;

    size--;

    return entry;
  }

  /**
   * @return Iterator to iterate from head to the tail.
   */
  public Iterator<ClosableEntry<K, V>> iterator() {
    return new Iterator<ClosableEntry<K, V>>() {
      private ClosableEntry<K, V> next = head;
      private ClosableEntry<K, V> current = null;

      @Override
      public boolean hasNext() {
        return next != null;
      }

      @Override
      public ClosableEntry<K, V> next() {
        if (next == null) {
          throw new NoSuchElementException();
        }

        current = next;
        next = next.getNext();
        return current;
      }

      @Override
      public void remove() {
        if (current == null) {
          throw new IllegalStateException("Method next was not called");
        }

        ClosableLRUList.this.remove(current);
        current = null;
      }
    };
  }

  boolean assertForwardStructure() {
    if (head == null) {
      return tail == null;
    }

    var current = head;

    while (current.getNext() != null) {
      var prev = current.getPrev();
      var next = current.getNext();

      assert prev == null || prev.getNext() == current;

      assert next == null || next.getPrev() == current;

      current = current.getNext();
    }

    return current == tail;
  }

  boolean assertBackwardStructure() {
    if (tail == null) {
      return head == null;
    }

    var current = tail;

    while (current.getPrev() != null) {
      var prev = current.getPrev();
      var next = current.getNext();

      assert prev == null || prev.getNext() == current;

      assert next == null || next.getPrev() == current;

      current = current.getPrev();
    }

    return current == head;
  }
}
