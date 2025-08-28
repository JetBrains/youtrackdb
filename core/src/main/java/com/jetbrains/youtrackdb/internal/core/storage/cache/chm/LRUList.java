package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

public final class LRUList implements Iterable<CacheEntry> {

  private int size;

  private CacheEntry head;
  private CacheEntry tail;

  void remove(final CacheEntry entry) {
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
    entry.setContainer(null);

    size--;
  }

  boolean contains(final CacheEntry entry) {
    return entry.getContainer() == this;
  }

  void moveToTheTail(final CacheEntry entry) {
    if (tail == entry) {
      assert entry.getNext() == null;
      return;
    }

    final var next = entry.getNext();
    final var prev = entry.getPrev();

    final var newEntry = entry.getContainer() == null;
    assert entry.getContainer() == null || entry.getContainer() == this;

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
      entry.setContainer(this);
      size++;
    } else {
      assert entry.getContainer() == this;
    }
  }

  int size() {
    return size;
  }

  @Nullable
  CacheEntry poll() {
    if (head == null) {
      return null;
    }

    final var entry = head;

    final var next = head.getNext();
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

    entry.setContainer(null);
    return entry;
  }

  CacheEntry peek() {
    return head;
  }

  @Override
  public Iterator<CacheEntry> iterator() {
    return new Iterator<CacheEntry>() {
      private CacheEntry next = tail;

      @Override
      public boolean hasNext() {
        return next != null;
      }

      @Override
      public CacheEntry next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        final var result = next;
        next = next.getPrev();

        return result;
      }
    };
  }
}
