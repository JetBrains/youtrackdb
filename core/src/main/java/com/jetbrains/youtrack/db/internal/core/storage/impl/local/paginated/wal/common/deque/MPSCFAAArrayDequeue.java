package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.common.deque;

import static com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.common.deque.Node.BUFFER_SIZE;

import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

public final class MPSCFAAArrayDequeue<T> extends AtomicReference<Node<T>> {

  private volatile Node<T> head;
  private static final Object taken = new Object();

  public MPSCFAAArrayDequeue() {
    final var dummyNode = new Node<T>();
    dummyNode.enqidx.set(0);

    set(dummyNode);
    head = dummyNode;
  }

  public void offer(T record) {
    while (true) {
      final var tail = get();
      final var idx = tail.enqidx.getAndIncrement();

      if (idx > BUFFER_SIZE - 1) { // This node is full
        if (tail != get()) {
          continue;
        }
        final var next = tail.getNext();
        if (next == null) {
          final var newNode = new Node<T>(record, tail);

          if (tail.casNext(null, newNode)) {
            compareAndSet(tail, newNode);
            return;
          }
        } else {
          compareAndSet(tail, next);
        }
        continue;
      }

      if (tail.items.compareAndSet(idx, null, record)) {
        return;
      }
    }
  }

  @Nullable
  public T poll() {
    while (true) {
      var head = this.head;

      final var deqidx = head.deqidx;
      final var enqidx = head.enqidx.get();

      if ((deqidx >= enqidx || deqidx >= BUFFER_SIZE) && head.getNext() == null) {
        return null;
      }

      if (deqidx >= BUFFER_SIZE) {
        this.head = head.getNext();

        head.clearPrev(); // allow gc to clear previous items
        continue;
      }

      final var idx = head.deqidx++;

      @SuppressWarnings("unchecked") final var item = head.items.getAndSet(idx, (T) taken);
      if (item == null) {
        continue;
      }

      return item;
    }
  }

  @Nullable
  public T peek() {
    var head = this.head;

    while (true) {
      final var deqidx = head.deqidx;
      final var enqidx = head.enqidx.get();

      if (deqidx >= enqidx || deqidx >= BUFFER_SIZE) {
        if (head.getNext() == null) {
          return null;
        }

        head = head.getNext();
        continue;
      }

      final var idx = deqidx;
      final var item = head.items.get(idx);

      if (item == null || item == taken) {
        continue;
      }

      return item;
    }
  }

  @Nullable
  public Cursor<T> peekFirst() {
    var head = this.head;

    while (true) {
      final var deqidx = head.deqidx;
      final var enqidx = head.enqidx.get();

      if (deqidx >= enqidx || deqidx >= BUFFER_SIZE) {
        if (head.getNext() == null) {
          return null;
        }

        head = head.getNext();
        continue;
      }

      final var idx = deqidx;
      final var item = head.items.get(idx);
      if (item == null || item == taken) {
        continue;
      }

      return new Cursor<>(head, idx, item);
    }
  }

  @Nullable
  public static <T> Cursor<T> next(Cursor<T> cursor) {
    if (cursor == null) {
      return null;
    }

    var node = cursor.node;
    var idx = cursor.itemIndex + 1;

    while (node != null) {
      var enqidx = node.enqidx.get();

      if (idx >= enqidx || idx >= BUFFER_SIZE) {
        if (enqidx < BUFFER_SIZE) {
          return null; // reached the end of the queue
        } else {
          node = node.getNext();
          idx = 0;
          continue;
        }
      }

      final var item = node.items.get(idx);
      if (item == null) {
        continue; // counters may be updated but item itslef is not updated yet
      }
      if (item == taken) {
        return null;
      }

      return new Cursor<>(node, idx, item);
    }

    return null;
  }

  @Nullable
  public Cursor<T> peekLast() {
    while (true) {
      var tail = get();

      final var enqidx = tail.enqidx.get();
      final var deqidx = tail.deqidx;
      if (deqidx >= enqidx || deqidx >= BUFFER_SIZE) {
        return null; // we remove only from the head, so if tail is empty it means that queue is
        // empty
      }

      var idx = enqidx;
      if (idx >= BUFFER_SIZE) {
        idx = BUFFER_SIZE;
      }

      if (idx <= 0) {
        return null; // No more items in the node
      }

      final var item = tail.items.get(idx - 1);
      if (item == null || item == taken) {
        continue; // concurrent modification
      }

      return new Cursor<>(tail, idx - 1, item);
    }
  }

  @Nullable
  public static <T> Cursor<T> prev(Cursor<T> cursor) {
    if (cursor == null) {
      return null;
    }

    var node = cursor.node;
    var idx = cursor.itemIndex - 1;

    while (node != null) {
      var deqidx = node.deqidx;

      if (deqidx > idx
          || deqidx >= BUFFER_SIZE) { // idx == enqidx -1, that is why we use >, but not >=
        if (deqidx > 0) {
          return null; // reached the end of the queue
        } else {
          node = node.getPrev();
          idx = BUFFER_SIZE - 1;
          continue;
        }
      }

      final var item = node.items.get(idx); // reached end of the queue
      if (item == null) {
        continue; // counters may be updated but values are still not updated
      }
      if (item == taken) {
        return null;
      }

      return new Cursor<>(node, idx, item);
    }

    return null;
  }
}
