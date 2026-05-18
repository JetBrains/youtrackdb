package com.jetbrains.youtrackdb.internal.common.concur.collection;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class CASObjectArray<T> {

  private final AtomicInteger size = new AtomicInteger();
  private final AtomicReferenceArray<AtomicReferenceArray<T>> containers =
      new AtomicReferenceArray<>(32);

  public boolean add(T value, int index) {
    Objects.requireNonNull(value);
    return doAdd(value, index) >= 0;
  }

  public int add(T value) {
    Objects.requireNonNull(value);
    return doAdd(value, -1);
  }

  /**
   * Core add logic shared by both {@code add(T)} and {@code add(T, int)}.
   *
   * @param requiredIndex the index the caller expects to write at, or {@code -1}
   *     to accept any index (unconditional append).
   * @return the index where the value was placed, or {@code -1} if
   *     {@code requiredIndex >= 0} and the current size did not match it.
   */
  private int doAdd(T value, int requiredIndex) {
    while (true) {
      final var newIndex = size.get();
      if (requiredIndex >= 0 && newIndex != requiredIndex) {
        return -1;
      }
      final var containerIndex = 31 - Integer.numberOfLeadingZeros(newIndex + 1);
      final var containerSize = 1 << containerIndex;
      final var indexInsideContainer = newIndex + 1 - containerSize;

      var container = containers.get(containerIndex);
      if (container == null) {
        container = new AtomicReferenceArray<>(containerSize);
        if (!containers.compareAndSet(containerIndex, null, container)) {
          container = containers.get(containerIndex);
        }
      }

      if (container.compareAndSet(indexInsideContainer, null, value)) {
        size.incrementAndGet();
        return newIndex;
      }
    }
  }

  public void set(int index, T value, T placeholder) {
    Objects.requireNonNull(value);
    Objects.requireNonNull(placeholder);

    if (this.size.get() <= index) {
      // Expand the array to accommodate the target index. Fill gap slots
      // [currentSize, index) with placeholders, then write the actual value
      // at the target index directly via add(value). This eliminates the
      // window where a concurrent reader (e.g., the snapshot scan in
      // AtomicOperationsTable) could observe a NOT_STARTED placeholder at the
      // target slot before the overwrite — a race that can violate snapshot
      // isolation on ARM's relaxed memory model.
      while (this.size.get() < index) {
        add(placeholder);
      }
      // size >= index now. If size == index, the target slot is the next to
      // be allocated — write the actual value directly, no placeholder window.
      if (add(value, index)) {
        return;
      }
      // size > index: the target slot was already expanded past (by a
      // concurrent add() or by the gap-filling loop overshooting due to
      // contention). Fall through to overwrite the placeholder.
    }

    final var containerIndex = 31 - Integer.numberOfLeadingZeros(index + 1);
    final var containerSize = 1 << containerIndex;
    final var indexInsideContainer = index + 1 - containerSize;

    AtomicReferenceArray<T> container;
    while (true) {
      container = containers.get(containerIndex);
      if (container == null) {
        Thread.yield();
      } else {
        break;
      }
    }

    container.set(indexInsideContainer, value);
  }

  public boolean compareAndSet(int index, T oldValue, T value) {
    Objects.requireNonNull(value);
    Objects.requireNonNull(oldValue);

    final var size = this.size.get();

    if (size <= index) {
      throw new ArrayIndexOutOfBoundsException("Requested " + index + ", size is " + size);
    }

    final var containerIndex = 31 - Integer.numberOfLeadingZeros(index + 1);
    final var containerSize = 1 << containerIndex;
    final var indexInsideContainer = index + 1 - containerSize;

    AtomicReferenceArray<T> container;
    while (true) {
      container = containers.get(containerIndex);
      if (container == null) {
        Thread.yield();
      } else {
        break;
      }
    }

    return container.compareAndSet(indexInsideContainer, oldValue, value);
  }

  public T get(int index) {
    final var size = this.size.get();

    if (size <= index) {
      throw new ArrayIndexOutOfBoundsException("Requested " + index + ", size is " + size);
    }

    final var containerIndex = 31 - Integer.numberOfLeadingZeros(index + 1);
    final var containerSize = 1 << containerIndex;
    final var indexInsideContainer = index + 1 - containerSize;

    AtomicReferenceArray<T> container;
    while (true) {
      container = containers.get(containerIndex);
      if (container == null) {
        Thread.yield();
      } else {
        break;
      }
    }

    T value;
    while (true) {
      value = container.get(indexInsideContainer);
      if (value == null) {
        Thread.yield();
      } else {
        break;
      }
    }

    return value;
  }

  public int size() {
    return size.get();
  }
}
